package com.omnidude.snmp;

import com.omnidude.discovery.MacAddress;
import com.omnidude.snmp.LldpReader.Neighbor;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.topology.LinkOrigin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/**
 * Vizinhos anunciados por protocolos de fabricante, lidos por SNMP: CDP (Cisco, {@code cdpCacheTable}) e MNDP
 * (MikroTik, {@code mtxrNeighborTable}). Devolvem o mesmo {@link Neighbor} do LLDP; quem chama marca a origem.
 */
@Component
public class VendorNeighborReader {

    public record Found(LinkOrigin origin, Neighbor neighbor) {
    }

    private static final OID CDP_ENTRY = new OID("1.3.6.1.4.1.9.9.23.1.2.1.1");
    private static final OID MNDP_ENTRY = new OID("1.3.6.1.4.1.14988.1.1.11.1.1");

    private final SnmpClient client;

    public VendorNeighborReader(SnmpClient client) {
        this.client = client;
    }

    /** CDP e MNDP; cada protocolo que o agente não suporta simplesmente não contribui. Timeout é propagado. */
    public List<Found> read(String ip, SnmpAccess a) throws SnmpException {
        List<Found> out = new ArrayList<>();
        for (Neighbor n : cdp(ip, a)) {
            out.add(new Found(LinkOrigin.CDP, n));
        }
        for (Neighbor n : mndp(ip, a)) {
            out.add(new Found(LinkOrigin.MNDP, n));
        }
        return out;
    }

    /** Índice: coluna . ifIndex . deviceIndex. Colunas: 3 tipo do endereço, 4 endereço, 6 deviceId, 7 porta remota. */
    List<Neighbor> cdp(String ip, SnmpAccess a) throws SnmpException {
        Map<String, Row> rows = new LinkedHashMap<>();
        int base = CDP_ENTRY.size();
        for (VariableBinding vb : walk(ip, a, CDP_ENTRY)) {
            OID o = vb.getOid();
            if (o.size() != base + 3) {
                continue;
            }
            int col = o.get(base);
            Row r = rows.computeIfAbsent(o.get(base + 1) + "/" + o.get(base + 2), k -> new Row(o.get(base + 1)));
            switch (col) {
                case 3 -> r.addrType = vb.getVariable().toInt();
                case 4 -> {
                    if (vb.getVariable() instanceof OctetString os && os.length() == 4) {
                        byte[] b = os.toByteArray();
                        r.ip = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
                    }
                }
                case 6 -> r.name = SnmpInventory.text(vb.getVariable());
                case 7 -> r.port = SnmpInventory.text(vb.getVariable());
                default -> { }
            }
        }
        List<Neighbor> out = new ArrayList<>();
        for (Row r : rows.values()) {
            out.add(new Neighbor(r.ifIndex, 0, null, null, r.port, r.port, r.name,
                    r.ip != null && (r.addrType == 0 || r.addrType == 1) ? List.of(r.ip) : List.of()));
        }
        return out;
    }

    /** Índice: coluna . índice. Colunas: 2 IP, 3 MAC, 6 identidade, 8 ifIndex local (MIKROTIK-MIB). */
    List<Neighbor> mndp(String ip, SnmpAccess a) throws SnmpException {
        Map<Integer, Row> rows = new LinkedHashMap<>();
        int base = MNDP_ENTRY.size();
        for (VariableBinding vb : walk(ip, a, MNDP_ENTRY)) {
            OID o = vb.getOid();
            if (o.size() != base + 2) {
                continue;
            }
            Row r = rows.computeIfAbsent(o.get(base + 1), k -> new Row(0));
            switch (o.get(base)) {
                case 2 -> {
                    if (vb.getVariable() instanceof OctetString os && os.length() == 4) {   // IpAddress
                        byte[] b = os.toByteArray();
                        r.ip = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
                    } else {
                        r.ip = vb.getVariable().toString();
                    }
                }
                case 3 -> {
                    if (vb.getVariable() instanceof OctetString os) {
                        r.mac = MacAddress.fromBytes(os.toByteArray()).orElse(null);
                    }
                }
                case 6 -> r.name = SnmpInventory.text(vb.getVariable());
                case 8 -> r.ifIndex = vb.getVariable().toInt();
                default -> { }
            }
        }
        List<Neighbor> out = new ArrayList<>();
        for (Row r : rows.values()) {
            out.add(new Neighbor(r.ifIndex, 0, r.mac, null, null, null, r.name,
                    r.ip == null || r.ip.isBlank() || "0.0.0.0".equals(r.ip) ? List.of() : List.of(r.ip)));
        }
        return out;
    }

    private List<VariableBinding> walk(String ip, SnmpAccess a, OID root) throws SnmpException {
        return client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), root);
    }

    private static final class Row {
        int ifIndex;
        int addrType;
        String ip;
        String mac;
        String name;
        String port;

        Row(int ifIndex) {
            this.ifIndex = ifIndex;
        }
    }
}
