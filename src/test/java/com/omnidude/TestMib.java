package com.omnidude;

import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.snmp4j.smi.Counter32;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.Variable;

/** Monta a MIB de um equipamento de mentira: sistema, IF-MIB e vizinhos LLDP. */
public class TestMib {

    private final NavigableMap<OID, Variable> m = new ConcurrentSkipListMap<>();

    private OID oid(String base, int... suffix) {
        OID o = new OID(base);
        for (int s : suffix) {
            o.append(s);
        }
        return o;
    }

    public TestMib system(String descr, String objectId, String name, long uptimeTicks) {
        m.put(oid("1.3.6.1.2.1.1.1.0"), new OctetString(descr));
        m.put(oid("1.3.6.1.2.1.1.2.0"), new OID(objectId));
        m.put(oid("1.3.6.1.2.1.1.3.0"), new TimeTicks(uptimeTicks));
        m.put(oid("1.3.6.1.2.1.1.5.0"), new OctetString(name));
        return this;
    }

    /** @param mac "aa:bb:cc:dd:ee:ff" ou null; @param type 6 = ethernet, 24 = loopback de software. */
    public TestMib iface(int idx, String name, String mac, int type, int oper, int admin, long mbps) {
        String t = "1.3.6.1.2.1.2.2.1.";
        m.put(oid(t + "1", idx), new Integer32(idx));
        m.put(oid(t + "2", idx), new OctetString("descr-" + name));
        m.put(oid(t + "3", idx), new Integer32(type));
        m.put(oid(t + "5", idx), new Gauge32(mbps >= 4294 ? 4294967295L : mbps * 1_000_000));
        m.put(oid(t + "6", idx), mac == null ? new OctetString() : OctetString.fromHexString(mac.replace(":", " ").trim(), ' '));
        m.put(oid(t + "7", idx), new Integer32(admin));
        m.put(oid(t + "8", idx), new Integer32(oper));
        String x = "1.3.6.1.2.1.31.1.1.1.";
        m.put(oid(x + "1", idx), new OctetString(name));
        m.put(oid(x + "15", idx), new Gauge32(mbps));
        m.put(oid(x + "18", idx), new OctetString("alias-" + name));
        return this;
    }

    /** Contadores de tráfego de 64 bits (ifXTable) mais os erros e o estado operacional; {@code setCounters} os altera. */
    public TestMib counters(int idx, long inOctets, long outOctets) {
        setCounters(idx, inOctets, outOctets, 0, 0);
        return this;
    }

    /** Atualiza os contadores de 64 bits e os de 32 bits (ifTable, truncados como num agente real) e os erros. */
    public void setCounters(int idx, long inOctets, long outOctets, long inErrors, long outErrors) {
        m.put(oid("1.3.6.1.2.1.31.1.1.1.6", idx), new Counter64(inOctets));
        m.put(oid("1.3.6.1.2.1.31.1.1.1.10", idx), new Counter64(outOctets));
        m.put(oid("1.3.6.1.2.1.2.2.1.10", idx), new Counter32(inOctets & 0xFFFFFFFFL));
        m.put(oid("1.3.6.1.2.1.2.2.1.16", idx), new Counter32(outOctets & 0xFFFFFFFFL));
        m.put(oid("1.3.6.1.2.1.2.2.1.14", idx), new Counter32(inErrors));
        m.put(oid("1.3.6.1.2.1.2.2.1.20", idx), new Counter32(outErrors));
    }

    /** Sem a ifXTable de contadores: o agente só tem os de 32 bits (como um equipamento antigo ou SNMPv1). */
    public void removeHighCapacityCounters(int idx) {
        m.remove(oid("1.3.6.1.2.1.31.1.1.1.6", idx));
        m.remove(oid("1.3.6.1.2.1.31.1.1.1.10", idx));
    }

    public void setOperStatus(int idx, int oper) {
        m.put(oid("1.3.6.1.2.1.2.2.1.8", idx), new Integer32(oper));
    }

    private static OID macOid(String base, String mac, int... prefix) {
        OID o = new OID(base);
        for (int p : prefix) {
            o.append(p);
        }
        for (String part : mac.split(":")) {
            o.append(Integer.parseInt(part, 16));
        }
        return o;
    }

    /** Entrada da FDB (BRIDGE-MIB): MAC aprendido na porta de bridge {@code port}; status 3 = aprendido, 4 = do próprio. */
    public TestMib fdb(String mac, int port, int status) {
        m.put(macOid("1.3.6.1.2.1.17.4.3.1.2", mac), new Integer32(port));
        m.put(macOid("1.3.6.1.2.1.17.4.3.1.3", mac), new Integer32(status));
        return this;
    }

    /** Só o dot1q (Q-BRIDGE-MIB): entrada da FDB na VLAN {@code fdbId}. */
    public TestMib qFdb(int fdbId, String mac, int port) {
        m.put(macOid("1.3.6.1.2.1.17.7.1.2.2.1.2", mac, fdbId), new Integer32(port));
        m.put(macOid("1.3.6.1.2.1.17.7.1.2.2.1.3", mac, fdbId), new Integer32(3));
        return this;
    }

    public TestMib bridgePort(int port, int ifIndex) {
        m.put(oid("1.3.6.1.2.1.17.1.4.1.2", port), new Integer32(ifIndex));
        return this;
    }

    /** Apaga as entradas de FDB (dot1d) para refazer a tabela. */
    public void clearFdb() {
        m.keySet().removeIf(o -> o.toDottedString().startsWith("1.3.6.1.2.1.17.4.3.1."));
    }

    /** Entrada ARP (ipNetToMediaTable). */
    public TestMib arp(int ifIndex, String ip, String mac) {
        String[] a = ip.split("\\.");
        m.put(arpOid("1.3.6.1.2.1.4.22.1.2", ifIndex, a), OctetString.fromHexString(mac.replace(":", " "), ' '));
        m.put(arpOid("1.3.6.1.2.1.4.22.1.4", ifIndex, a), new Integer32(3));
        return this;
    }

    private static OID arpOid(String base, int ifIndex, String[] ip) {
        OID o = new OID(base);
        o.append(ifIndex);
        for (String p : ip) {
            o.append(Integer.parseInt(p));
        }
        return o;
    }

    /** Vizinho CDP (cdpCacheTable) visto no {@code ifIndex}. */
    public TestMib cdpNeighbor(int ifIndex, int idx, String deviceId, String remotePort, String ip) {
        String e = "1.3.6.1.4.1.9.9.23.1.2.1.1.";
        String[] a = ip.split("\\.");
        m.put(oid(e + "3", ifIndex, idx), new Integer32(1));
        m.put(oid(e + "4", ifIndex, idx), new OctetString(new byte[] {(byte) Integer.parseInt(a[0]), (byte) Integer.parseInt(a[1]),
                (byte) Integer.parseInt(a[2]), (byte) Integer.parseInt(a[3])}));
        m.put(oid(e + "6", ifIndex, idx), new OctetString(deviceId));
        m.put(oid(e + "7", ifIndex, idx), new OctetString(remotePort));
        return this;
    }

    /** Vizinho MNDP (mtxrNeighborTable). */
    public TestMib mndpNeighbor(int idx, String ip, String mac, String identity, int ifIndex) {
        String e = "1.3.6.1.4.1.14988.1.1.11.1.1.";
        String[] a = ip.split("\\.");
        m.put(oid(e + "2", idx), new OctetString(new byte[] {(byte) Integer.parseInt(a[0]), (byte) Integer.parseInt(a[1]),
                (byte) Integer.parseInt(a[2]), (byte) Integer.parseInt(a[3])}));
        m.put(oid(e + "3", idx), OctetString.fromHexString(mac.replace(":", " "), ' '));
        m.put(oid(e + "6", idx), new OctetString(identity));
        m.put(oid(e + "8", idx), new Integer32(ifIndex));
        return this;
    }

    /** Vizinho LLDP visto na porta local {@code localPort}, anunciando o IP de gerência e/ou o chassi em MAC. */
    public TestMib lldpNeighbor(int localPort, int remIndex, String chassisMac, String remPortId, String remPortDesc,
                                String remSysName, String mgmtIp) {
        String e = "1.0.8802.1.1.2.1.4.1.1.";
        m.put(oid(e + "4", 0, localPort, remIndex), new Integer32(4));            // chassis subtype: macAddress
        m.put(oid(e + "5", 0, localPort, remIndex), OctetString.fromHexString(chassisMac.replace(":", " "), ' '));
        m.put(oid(e + "6", 0, localPort, remIndex), new Integer32(5));            // port subtype: interfaceName
        m.put(oid(e + "7", 0, localPort, remIndex), new OctetString(remPortId));
        m.put(oid(e + "8", 0, localPort, remIndex), new OctetString(remPortDesc));
        m.put(oid(e + "9", 0, localPort, remIndex), new OctetString(remSysName));
        if (mgmtIp != null) {
            String[] a = mgmtIp.split("\\.");
            OID ifSubtype = oid("1.0.8802.1.1.2.1.4.2.1.3", 0, localPort, remIndex, 1, 4);
            for (String p : a) {
                ifSubtype.append(Integer.parseInt(p));
            }
            m.put(ifSubtype, new Integer32(2));
        }
        return this;
    }

    public NavigableMap<OID, Variable> build() {
        return m;
    }
}
