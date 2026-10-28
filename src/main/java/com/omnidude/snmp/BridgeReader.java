package com.omnidude.snmp;

import com.omnidude.discovery.MacAddress;
import com.omnidude.snmp.SnmpClient.SnmpException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/**
 * Tabela de encaminhamento (FDB) de um switch ou AP: em que porta cada MAC foi aprendido. Lê a BRIDGE-MIB
 * ({@code dot1dTpFdbTable}) e, para os que só têm VLANs, a Q-BRIDGE-MIB ({@code dot1qTpFdbTable}). Não lê a FDB
 * por VLAN com community indexada ({@code community@vlan}, Cisco): só o que o agente expõe no contexto padrão.
 */
@Component
public class BridgeReader {

    /** MACs aprendidos por porta (ifIndex) e os MACs do próprio equipamento (status "self"). */
    public record Fdb(Map<Integer, Set<String>> learnedByIfIndex, Set<String> selfMacs) {
        public boolean isEmpty() {
            return learnedByIfIndex.isEmpty();
        }
    }

    private static final OID BASE_PORT_IFINDEX = new OID("1.3.6.1.2.1.17.1.4.1.2");
    private static final OID DOT1D_PORT = new OID("1.3.6.1.2.1.17.4.3.1.2");
    private static final OID DOT1D_STATUS = new OID("1.3.6.1.2.1.17.4.3.1.3");
    private static final OID DOT1Q_PORT = new OID("1.3.6.1.2.1.17.7.1.2.2.1.2");
    private static final OID DOT1Q_STATUS = new OID("1.3.6.1.2.1.17.7.1.2.2.1.3");
    private static final int STATUS_LEARNED = 3;
    private static final int STATUS_SELF = 4;

    private final SnmpClient client;

    public BridgeReader(SnmpClient client) {
        this.client = client;
    }

    public Fdb read(String ip, SnmpAccess a) throws SnmpException {
        Map<Integer, Integer> portToIfIndex = new HashMap<>();
        for (VariableBinding vb : walk(ip, a, BASE_PORT_IFINDEX)) {
            portToIfIndex.put(vb.getOid().last(), vb.getVariable().toInt());
        }
        Map<Integer, Set<String>> learned = new HashMap<>();
        Set<String> self = new HashSet<>();
        collect(ip, a, DOT1D_PORT, DOT1D_STATUS, portToIfIndex, learned, self);
        collect(ip, a, DOT1Q_PORT, DOT1Q_STATUS, portToIfIndex, learned, self);
        return new Fdb(learned, self);
    }

    private void collect(String ip, SnmpAccess a, OID portCol, OID statusCol, Map<Integer, Integer> portToIfIndex,
                         Map<Integer, Set<String>> learned, Set<String> self) throws SnmpException {
        List<VariableBinding> ports = walk(ip, a, portCol);
        if (ports.isEmpty()) {
            return;
        }
        Map<String, Integer> status = new HashMap<>();
        for (VariableBinding vb : walk(ip, a, statusCol)) {
            status.put(suffix(vb.getOid(), statusCol), vb.getVariable().toInt());
        }
        for (VariableBinding vb : ports) {
            String key = suffix(vb.getOid(), portCol);
            String mac = macFromSuffix(vb.getOid());
            if (mac == null) {
                continue;
            }
            int st = status.getOrDefault(key, STATUS_LEARNED);          // sem a coluna de status: assume aprendido
            int bridgePort = vb.getVariable().toInt();
            if (st == STATUS_SELF) {
                self.add(mac);
            } else if (st == STATUS_LEARNED && bridgePort > 0) {
                // Agentes que não publicam dot1dBasePortIfIndex usam a própria porta como ifIndex (o caso comum).
                int ifIndex = portToIfIndex.getOrDefault(bridgePort, bridgePort);
                learned.computeIfAbsent(ifIndex, k -> new HashSet<>()).add(mac);
            }
        }
    }

    private List<VariableBinding> walk(String ip, SnmpAccess a, OID root) throws SnmpException {
        return client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), root);
    }

    /** O sufixo do índice depois da coluna ({@code mac} em dot1d; {@code fdbId.mac} em dot1q). */
    private static String suffix(OID oid, OID column) {
        return oid.toDottedString().substring(column.toDottedString().length());
    }

    /** Os 6 últimos subidentificadores do índice são os octetos do MAC. */
    private static String macFromSuffix(OID oid) {
        int n = oid.size();
        if (n < 6) {
            return null;
        }
        byte[] mac = new byte[6];
        for (int i = 0; i < 6; i++) {
            int v = oid.get(n - 6 + i);
            if (v < 0 || v > 255) {
                return null;
            }
            mac[i] = (byte) v;
        }
        return MacAddress.fromBytes(mac).orElse(null);
    }
}
