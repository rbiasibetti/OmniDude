package com.omnidude.topology;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Gateway padrão do servidor onde o OmniDude roda e o tamanho da rede local que o contém. Só o Linux
 * ({@code /proc/net/route}) é lido; nos demais sistemas o gateway é desconhecido e o chamador usa outro critério.
 */
@Component
public class GatewayResolver {

    /** IPv4 do gateway padrão, se o sistema o informa. */
    public Optional<String> defaultGateway() {
        try {
            Path route = Path.of("/proc/net/route");
            if (!Files.isReadable(route)) {
                return Optional.empty();
            }
            return parseDefaultGateway(Files.readString(route, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Linhas: {@code Iface Destination Gateway Flags ...}, em hexadecimal little-endian; destino 0 é a rota padrão. */
    static Optional<String> parseDefaultGateway(String text) {
        for (String line : text.split("\\R")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4 || f[0].equals("Iface") || !f[1].equals("00000000")) {
                continue;
            }
            try {
                long gw = Long.parseLong(f[2], 16);
                int flags = Integer.parseInt(f[3], 16);
                if (gw == 0 || (flags & 0x2) == 0) {                  // precisa do flag RTF_GATEWAY
                    continue;
                }
                return Optional.of((gw & 0xFF) + "." + ((gw >> 8) & 0xFF) + "." + ((gw >> 16) & 0xFF) + "."
                        + ((gw >> 24) & 0xFF));
            } catch (NumberFormatException ignored) {
                // linha estranha: segue
            }
        }
        return Optional.empty();
    }

    /** Tamanho do prefixo da interface local cuja sub-rede contém o IP; 24 se nenhuma contém (ou não há interfaces). */
    public int prefixLengthFor(String ip) {
        return localPrefix(ip).orElse(24);
    }

    /** Prefixo da sub-rede local (mesmo segmento L2 do servidor) que contém o IP; vazio se o IP está fora delas. */
    public java.util.OptionalInt localPrefix(String ip) {
        try {
            long target = toLong(InetAddress.getByName(ip).getAddress());
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && !ia.getAddress().isLoopbackAddress()
                            && sameSubnet(toLong(ia.getAddress().getAddress()), target, ia.getNetworkPrefixLength())) {
                        return java.util.OptionalInt.of(ia.getNetworkPrefixLength());
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // sem interface legível: fora das sub-redes locais
        }
        return java.util.OptionalInt.empty();
    }

    /** {@code ip} e {@code reference} estão na mesma sub-rede de tamanho {@code prefix}? */
    public static boolean sameSubnet(String ip, String reference, int prefix) {
        try {
            return sameSubnet(toLong(InetAddress.getByName(ip).getAddress()),
                    toLong(InetAddress.getByName(reference).getAddress()), prefix);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameSubnet(long a, long b, int prefix) {
        if (prefix <= 0 || prefix > 32) {
            return false;
        }
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return (a & mask) == (b & mask);
    }

    private static long toLong(byte[] ipv4) {
        if (ipv4.length != 4) {
            throw new IllegalArgumentException("não é IPv4");
        }
        return ((ipv4[0] & 0xFFL) << 24) | ((ipv4[1] & 0xFFL) << 16) | ((ipv4[2] & 0xFFL) << 8) | (ipv4[3] & 0xFFL);
    }
}
