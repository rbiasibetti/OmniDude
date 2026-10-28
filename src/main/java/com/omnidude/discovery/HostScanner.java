package com.omnidude.discovery;

import com.omnidude.monitoring.CheckType;
import com.omnidude.monitoring.probe.ExternalPingProbe;
import com.omnidude.monitoring.probe.Target;
import com.omnidude.monitoring.probe.TcpProbe;
import com.omnidude.snmp.SnmpAccess;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.snmp.SnmpInventory;
import com.omnidude.snmp.SnmpInventory.SystemInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Component;

/**
 * Sonda um host: ICMP e portas TCP em paralelo (vivo = qualquer resposta); se vivo, tenta cada perfil SNMP, na ordem,
 * até um responder. Não grava nada.
 */
@Component
public class HostScanner {

    public record SnmpHit(Long profileId, SystemInfo system) {
    }

    public record Scan(String ip, boolean alive, List<Integer> openPorts, SnmpHit snmp, PassiveIdentity.Identity identity) {
    }

    private final ExternalPingProbe ping;
    private final TcpProbe tcp;
    private final SnmpInventory snmp;
    private final ExecutorService executor;
    private final PassiveIdentity passive;
    private final boolean passiveEnabled;

    public HostScanner(ExternalPingProbe ping, TcpProbe tcp, SnmpInventory snmp, ExecutorService checkExecutor,
                       PassiveIdentity passive, com.omnidude.config.OmniDudeProperties props) {
        this.passive = passive;
        this.passiveEnabled = props.discovery() == null || props.discovery().passive();
        this.ping = ping;
        this.tcp = tcp;
        this.snmp = snmp;
        this.executor = checkExecutor;
    }

    public Scan scan(String ip, List<Integer> ports, List<SnmpAccess> profiles, int icmpTimeoutMs, int tcpTimeoutMs) {
        CompletableFuture<Boolean> icmp = CompletableFuture.supplyAsync(
                () -> ping.execute(new Target(ip, null, icmpTimeoutMs)).success(), executor);
        Map<Integer, CompletableFuture<Boolean>> tcps = new TreeMap<>();
        for (int port : ports) {
            tcps.put(port, CompletableFuture.supplyAsync(() -> tcp.execute(
                    new Target(CheckType.TCP, ip, port, tcpTimeoutMs, Map.of(), null)).success(), executor));
        }
        List<Integer> open = new ArrayList<>();
        tcps.forEach((port, f) -> {
            if (f.join()) {
                open.add(port);
            }
        });
        boolean alive = icmp.join() || !open.isEmpty();
        SnmpHit hit = null;
        PassiveIdentity.Identity identity = PassiveIdentity.Identity.NONE;
        if (alive && passiveEnabled) {
            identity = passive.identify(ip, Math.max(300, tcpTimeoutMs));
        }
        if (alive) {
            for (SnmpAccess access : profiles) {
                try {
                    hit = new SnmpHit(access.profileId(), snmp.readSystem(ip, access));
                    break;
                } catch (SnmpException ignored) {
                    // este perfil não abriu o agente; tenta o próximo
                }
            }
        }
        return new Scan(ip, alive, open, hit, identity);
    }
}
