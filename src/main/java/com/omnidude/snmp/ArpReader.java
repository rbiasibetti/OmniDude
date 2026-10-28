package com.omnidude.snmp;

import com.omnidude.discovery.MacAddress;
import com.omnidude.snmp.SnmpClient.SnmpException;
import java.util.HashMap;
import java.util.Map;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/**
 * Tabela ARP de um roteador ou switch L3 (IP-MIB): IP → MAC dos hosts que ele enxerga, mesmo em outras sub-redes que
 * não a do servidor. Lê {@code ipNetToMediaTable} e, se vier vazia, a {@code ipNetToPhysicalTable} (IPv4).
 */
@Component
public class ArpReader {

    private static final OID MEDIA_PHYS = new OID("1.3.6.1.2.1.4.22.1.2");
    private static final OID MEDIA_TYPE = new OID("1.3.6.1.2.1.4.22.1.4");
    private static final OID PHYSICAL_PHYS = new OID("1.3.6.1.2.1.4.35.1.4");
    private static final int TYPE_INVALID = 2;

    private final SnmpClient client;

    public ArpReader(SnmpClient client) {
        this.client = client;
    }

    /** IP → MAC (entradas inválidas e MACs nulos ou de broadcast ficam de fora). */
    public Map<String, String> read(String ip, SnmpAccess a) throws SnmpException {
        Map<String, String> out = new HashMap<>();
        Map<String, Integer> types = new HashMap<>();
        for (VariableBinding vb : walk(ip, a, MEDIA_TYPE)) {
            types.put(ipOf(vb.getOid(), MEDIA_TYPE.size() + 1), vb.getVariable().toInt());   // índice: ifIndex.a.b.c.d
        }
        for (VariableBinding vb : walk(ip, a, MEDIA_PHYS)) {
            String addr = ipOf(vb.getOid(), MEDIA_PHYS.size() + 1);
            if (addr != null && types.getOrDefault(addr, 0) != TYPE_INVALID) {
                mac(vb).ifPresent(m -> out.put(addr, m));
            }
        }
        if (out.isEmpty()) {
            for (VariableBinding vb : walk(ip, a, PHYSICAL_PHYS)) {     // índice: ifIndex.tipo(1=ipv4).4.a.b.c.d
                OID o = vb.getOid();
                int base = PHYSICAL_PHYS.size();
                if (o.size() == base + 7 && o.get(base + 1) == 1 && o.get(base + 2) == 4) {
                    mac(vb).ifPresent(m -> out.put(ipOf(o, base + 3), m));
                }
            }
        }
        return out;
    }

    private static java.util.Optional<String> mac(VariableBinding vb) {
        return vb.getVariable() instanceof OctetString os ? MacAddress.fromBytes(os.toByteArray())
                : java.util.Optional.empty();
    }

    /** Os 4 subidentificadores a partir de {@code from} formam o IPv4; {@code null} se o índice não tem esse formato. */
    private static String ipOf(OID oid, int from) {
        if (oid.size() < from + 4) {
            return null;
        }
        return oid.get(from) + "." + oid.get(from + 1) + "." + oid.get(from + 2) + "." + oid.get(from + 3);
    }

    private java.util.List<VariableBinding> walk(String ip, SnmpAccess a, OID root) throws SnmpException {
        return client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), root);
    }
}
