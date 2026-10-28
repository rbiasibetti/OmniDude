package com.omnidude.snmp;

import com.omnidude.discovery.MacAddress;
import com.omnidude.snmp.SnmpClient.SnmpException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/** Lê a lldpRemTable (IEEE 8802-1AB, LLDP-MIB): os vizinhos que o equipamento enxerga em cada porta. */
@Component
public class LldpReader {

    /** Um vizinho. {@code localPort} é o lldpRemLocalPortNum (na maioria dos agentes, o ifIndex). */
    public record Neighbor(int localPort, int remIndex, String chassisMac, String chassisText, String portId,
                           String portDesc, String sysName, List<String> mgmtIps) {
    }

    private static final OID REM_ENTRY = new OID("1.0.8802.1.1.2.1.4.1.1");
    private static final OID MAN_ADDR_IF_SUBTYPE = new OID("1.0.8802.1.1.2.1.4.2.1.3");

    private final SnmpClient client;

    public LldpReader(SnmpClient client) {
        this.client = client;
    }

    public List<Neighbor> read(String ip, SnmpAccess a) throws SnmpException {
        Map<String, Builder> rows = new LinkedHashMap<>();
        int base = REM_ENTRY.size();
        for (VariableBinding vb : client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), REM_ENTRY)) {
            OID o = vb.getOid();
            if (o.size() != base + 4) {
                continue;                                       // coluna . timeMark . localPort . remIndex
            }
            int col = o.get(base);
            int localPort = o.get(base + 2);
            int remIndex = o.get(base + 3);
            Builder b = rows.computeIfAbsent(localPort + "/" + remIndex, k -> new Builder(localPort, remIndex));
            switch (col) {
                case 4 -> b.chassisSubtype = vb.getVariable().toInt();
                case 5 -> b.chassisId = bytes(vb);
                case 6 -> b.portSubtype = vb.getVariable().toInt();
                case 7 -> b.portId = bytes(vb);
                case 8 -> b.portDesc = SnmpInventory.text(vb.getVariable());
                case 9 -> b.sysName = SnmpInventory.text(vb.getVariable());
                default -> { }
            }
        }
        if (!rows.isEmpty()) {
            try {                                               // endereços de gerência: opcionais
                int mb = MAN_ADDR_IF_SUBTYPE.size();
                for (VariableBinding vb : client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), MAN_ADDR_IF_SUBTYPE)) {
                    OID o = vb.getOid();
                    if (o.size() < mb + 5) {
                        continue;
                    }
                    int subtype = o.get(mb + 3);
                    int len = o.get(mb + 4);
                    if (subtype == 1 && len == 4 && o.size() == mb + 5 + 4) {
                        Builder b = rows.get(o.get(mb + 1) + "/" + o.get(mb + 2));
                        if (b != null) {
                            b.mgmtIps.add(o.get(mb + 5) + "." + o.get(mb + 6) + "." + o.get(mb + 7) + "." + o.get(mb + 8));
                        }
                    }
                }
            } catch (SnmpException ignored) {
                // sem a tabela de endereços: o casamento cai para o MAC do chassi
            }
        }
        List<Neighbor> out = new ArrayList<>();
        rows.values().forEach(b -> out.add(b.build()));
        return out;
    }

    private static byte[] bytes(VariableBinding vb) {
        return vb.getVariable() instanceof OctetString os ? os.toByteArray() : new byte[0];
    }

    private static final class Builder {
        final int localPort;
        final int remIndex;
        int chassisSubtype;
        byte[] chassisId = new byte[0];
        int portSubtype;
        byte[] portId = new byte[0];
        String portDesc;
        String sysName;
        final List<String> mgmtIps = new ArrayList<>();

        Builder(int localPort, int remIndex) {
            this.localPort = localPort;
            this.remIndex = remIndex;
        }

        Neighbor build() {
            String mac = chassisSubtype == 4 ? MacAddress.fromBytes(chassisId).orElse(null) : null;
            String chassisText = mac == null && chassisId.length > 0 && chassisSubtype != 4
                    ? new String(chassisId, StandardCharsets.UTF_8).trim() : null;
            if (chassisSubtype == 5 && chassisId.length == 5 && chassisId[0] == 1) {   // networkAddress IPv4
                mgmtIps.add((chassisId[1] & 0xFF) + "." + (chassisId[2] & 0xFF) + "." + (chassisId[3] & 0xFF) + "."
                        + (chassisId[4] & 0xFF));
            }
            String port = portSubtype == 3 ? MacAddress.fromBytes(portId).orElse(null)
                    : new String(portId, StandardCharsets.UTF_8).trim();
            return new Neighbor(localPort, remIndex, mac, chassisText, port, portDesc, sysName, List.copyOf(mgmtIps));
        }
    }
}
