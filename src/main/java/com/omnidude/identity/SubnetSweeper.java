package com.omnidude.identity;

import com.omnidude.discovery.CidrRange;
import com.omnidude.topology.GatewayResolver;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Faz o kernel resolver o ARP de toda a sub-rede local de um IP: um datagrama UDP de 1 byte para a porta 9 (discard) de
 * cada endereço obriga o sistema a perguntar "quem tem este IP?", e quem existe responde com o MAC, que depois se lê da
 * tabela ARP. Não precisa de privilégio, não abre conexão nem espera resposta. Só para sub-redes em que o servidor tem
 * uma interface (mesmo segmento L2), de redes privadas e de no máximo 1.024 endereços.
 */
@Component
public class SubnetSweeper {

    private static final Logger log = LoggerFactory.getLogger(SubnetSweeper.class);
    static final int MAX_PREFIX_BITS = 22;
    private static final long SETTLE_MS = 1500;

    private final GatewayResolver gateways;

    public SubnetSweeper(GatewayResolver gateways) {
        this.gateways = gateways;
    }

    /** Chave estável da sub-rede local de {@code ip} ("10.0.0.0/24"), ou {@code null} se ela não pode ser varrida. */
    public String subnetOf(String ip) {
        OptionalInt prefix = gateways.localPrefix(ip);
        if (prefix.isEmpty() || prefix.getAsInt() < MAX_PREFIX_BITS || prefix.getAsInt() > 30) {
            return null;
        }
        long mask = (0xFFFFFFFFL << (32 - prefix.getAsInt())) & 0xFFFFFFFFL;
        long net = CidrRange.toLong(ip) & mask;
        String cidr = ((net >> 24) & 255) + "." + ((net >> 16) & 255) + "." + ((net >> 8) & 255) + "." + (net & 255) + "/" + prefix.getAsInt();
        try {
            return CidrRange.parse(cidr).insideAllowedNetworks() ? cidr : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Varre a sub-rede de {@code ip}. Devolve quantos endereços foram sondados (0 se não se aplica). */
    public int sweep(String ip) {
        String cidr = subnetOf(ip);
        if (cidr == null) {
            return 0;
        }
        int sent = 0;
        byte[] payload = {0};
        try (DatagramSocket socket = new DatagramSocket()) {
            for (String host : CidrRange.parse(cidr).hosts()) {
                try {
                    socket.send(new DatagramPacket(payload, 1, InetAddress.getByName(host), 9));
                    sent++;
                } catch (IOException e) {
                    // sem rota para este endereço: segue
                }
                if (sent % 64 == 0) {
                    Thread.sleep(2);                                  // não despeja a sub-rede inteira de uma vez
                }
            }
            Thread.sleep(SETTLE_MS);                                  // tempo para os ARPs voltarem à tabela do kernel
        } catch (IOException e) {
            log.debug("Varredura ARP de {} falhou: {}", cidr, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sent;
    }
}
