package com.omnidude.snmp;

import com.omnidude.discovery.MacAddress;
import com.omnidude.snmp.SnmpClient.SnmpException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/** Leituras SNMP de inventário: grupo system e IF-MIB. Só rede e interpretação; quem grava é o serviço de chamada. */
@Component
public class SnmpInventory {

    public record SystemInfo(String sysDescr, String sysObjectId, String sysName, Long uptimeTicks) {
    }

    public record InterfaceInfo(int ifIndex, String name, String descr, String alias, String mac, Long speedBps,
                                int type, Integer adminStatus, Integer operStatus) {

        /** ifType 24 = softwareLoopback: nunca interessa monitorar. */
        public boolean loopback() {
            return type == 24;
        }
    }

    static final OID SYS_DESCR = new OID("1.3.6.1.2.1.1.1.0");
    static final OID SYS_OBJECT_ID = new OID("1.3.6.1.2.1.1.2.0");
    static final OID SYS_UPTIME = new OID("1.3.6.1.2.1.1.3.0");
    static final OID SYS_NAME = new OID("1.3.6.1.2.1.1.5.0");

    private static final String IF_ENTRY = "1.3.6.1.2.1.2.2.1.";
    private static final String IFX_ENTRY = "1.3.6.1.2.1.31.1.1.1.";
    private static final int MAX_TEXT = 500;

    private final SnmpClient client;

    public SnmpInventory(SnmpClient client) {
        this.client = client;
    }

    public SystemInfo readSystem(String ip, SnmpAccess a) throws SnmpException {
        List<VariableBinding> vbs = client.get(ip, a.port(), a.credentials(), a.timeoutMs(),
                SYS_DESCR, SYS_OBJECT_ID, SYS_UPTIME, SYS_NAME);
        Map<OID, Variable> byOid = new TreeMap<>();
        for (VariableBinding vb : vbs) {
            if (!vb.getVariable().isException()) {
                byOid.put(vb.getOid(), vb.getVariable());
            }
        }
        if (byOid.isEmpty()) {
            throw new SnmpException("o agente não expõe o grupo system");
        }
        Variable up = byOid.get(SYS_UPTIME);
        return new SystemInfo(text(byOid.get(SYS_DESCR)), text(byOid.get(SYS_OBJECT_ID)), text(byOid.get(SYS_NAME)),
                up == null ? null : up.toLong());
    }

    /** IF-MIB: ifTable (obrigatória) + ifXTable (opcional, agentes antigos não têm). */
    public List<InterfaceInfo> readInterfaces(String ip, SnmpAccess a) throws SnmpException {
        Map<Integer, Row> rows = new TreeMap<>();
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "2")) {
            rows.computeIfAbsent(index(vb), Row::new).descr = text(vb.getVariable());
        }
        if (rows.isEmpty()) {
            throw new SnmpException("o agente não expõe a ifTable");
        }
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "3")) {
            Row r = rows.get(index(vb));
            if (r != null) {
                r.type = vb.getVariable().toInt();
            }
        }
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "5")) {
            Row r = rows.get(index(vb));
            if (r != null) {
                r.ifSpeed = vb.getVariable().toLong();
            }
        }
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "6")) {
            Row r = rows.get(index(vb));
            if (r != null && vb.getVariable() instanceof OctetString os) {
                r.mac = MacAddress.fromBytes(os.toByteArray()).orElse(null);
            }
        }
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "7")) {
            Row r = rows.get(index(vb));
            if (r != null) {
                r.admin = vb.getVariable().toInt();
            }
        }
        for (VariableBinding vb : walk(ip, a, IF_ENTRY + "8")) {
            Row r = rows.get(index(vb));
            if (r != null) {
                r.oper = vb.getVariable().toInt();
            }
        }
        try {                                                   // ifXTable: nome curto, alias e velocidade em Mbps
            for (VariableBinding vb : walk(ip, a, IFX_ENTRY + "1")) {
                Row r = rows.get(index(vb));
                if (r != null) {
                    r.name = text(vb.getVariable());
                }
            }
            for (VariableBinding vb : walk(ip, a, IFX_ENTRY + "15")) {
                Row r = rows.get(index(vb));
                if (r != null) {
                    r.highSpeedMbps = vb.getVariable().toLong();
                }
            }
            for (VariableBinding vb : walk(ip, a, IFX_ENTRY + "18")) {
                Row r = rows.get(index(vb));
                if (r != null) {
                    r.alias = text(vb.getVariable());
                }
            }
        } catch (SnmpException ignored) {
            // sem ifXTable: ficam o ifDescr e o ifSpeed
        }
        List<InterfaceInfo> out = new ArrayList<>();
        for (Row r : rows.values()) {
            Long speed = null;      // if explícito: um ternário misturando long e Long faria unboxing do null (NPE)
            if (r.highSpeedMbps != null && r.highSpeedMbps > 0) {
                speed = r.highSpeedMbps * 1_000_000L;
            } else if (r.ifSpeed != null && r.ifSpeed > 0) {
                speed = r.ifSpeed;
            }
            String name = r.name != null && !r.name.isBlank() ? r.name : r.descr;
            out.add(new InterfaceInfo(r.index, name, r.descr, r.alias == null || r.alias.isBlank() ? null : r.alias,
                    r.mac, speed, r.type, r.admin, r.oper));
        }
        return out;
    }

    private List<VariableBinding> walk(String ip, SnmpAccess a, String oid) throws SnmpException {
        return client.walk(ip, a.port(), a.credentials(), a.timeoutMs(), new OID(oid));
    }

    private static int index(VariableBinding vb) {
        return vb.getOid().last();
    }

    /** Texto legível; controles são trocados por espaço e o tamanho é limitado. */
    static String text(Variable v) {
        if (v == null || v.isException()) {
            return null;
        }
        String s = v instanceof OctetString os ? new String(os.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                : v.toString();
        s = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace('\n', ' ').trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static final class Row {
        final int index;
        String name;
        String descr;
        String alias;
        String mac;
        Long ifSpeed;
        Long highSpeedMbps;
        int type;
        Integer admin;
        Integer oper;

        Row(int index) {
            this.index = index;
        }
    }
}
