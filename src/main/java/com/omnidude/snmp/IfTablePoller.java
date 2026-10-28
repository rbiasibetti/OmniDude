package com.omnidude.snmp;

import com.omnidude.snmp.SnmpClient.SnmpException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/**
 * Lê contadores de tráfego, erros e estado operacional de interfaces conhecidas. Usa GET direto nos índices pedidos
 * (poucos pacotes quando só algumas portas são monitoradas) em vez de percorrer as colunas inteiras. Prefere os
 * contadores de 64 bits (ifXTable); sem eles — agente antigo ou SNMPv1 — cai nos de 32 bits da ifTable.
 */
@Component
public class IfTablePoller {

    /** {@code bits} = 64 ou 32; octetos {@code null} quando a interface não expõe contadores. */
    public record Reading(int ifIndex, Long inOctets, Long outOctets, int bits, Long inErrors, Long outErrors,
                          Integer operStatus) {

        public boolean hasCounters() {
            return inOctets != null && outOctets != null;
        }
    }

    private static final String IF = "1.3.6.1.2.1.2.2.1.";
    private static final String IFX = "1.3.6.1.2.1.31.1.1.1.";
    private static final int OIDS_PER_PDU = 21;                // 3 interfaces × 7 colunas

    private static final int OPER = 0, HC_IN = 1, HC_OUT = 2, IN32 = 3, OUT32 = 4, IN_ERR = 5, OUT_ERR = 6;

    private final SnmpClient client;

    public IfTablePoller(SnmpClient client) {
        this.client = client;
    }

    public Map<Integer, Reading> read(String ip, SnmpAccess access, Collection<Integer> ifIndexes) throws SnmpException {
        boolean v1 = "1".equals(access.credentials().version());     // Counter64 não existe em SNMPv1
        List<Integer> indexes = new ArrayList<>(ifIndexes);
        Map<Integer, Reading> out = new HashMap<>();
        for (int from = 0; from < indexes.size(); from += 3) {
            List<Integer> chunk = indexes.subList(from, Math.min(from + 3, indexes.size()));
            List<OID> oids = new ArrayList<>();
            for (int idx : chunk) {
                for (OID o : columns(idx, v1)) {
                    oids.add(o);
                }
            }
            if (oids.size() > OIDS_PER_PDU) {
                throw new IllegalStateException("PDU grande demais");
            }
            Map<OID, Variable> values = new HashMap<>();
            for (VariableBinding vb : client.get(ip, access.port(), access.credentials(), access.timeoutMs(),
                    oids.toArray(new OID[0]))) {
                if (!vb.getVariable().isException()) {
                    values.put(vb.getOid(), vb.getVariable());
                }
            }
            for (int idx : chunk) {
                out.put(idx, reading(idx, values, v1));
            }
        }
        return out;
    }

    private static OID[] columns(int idx, boolean v1) {
        OID[] cols = new OID[7];
        cols[OPER] = new OID(IF + "8." + idx);
        cols[HC_IN] = new OID(IFX + "6." + idx);
        cols[HC_OUT] = new OID(IFX + "10." + idx);
        cols[IN32] = new OID(IF + "10." + idx);
        cols[OUT32] = new OID(IF + "16." + idx);
        cols[IN_ERR] = new OID(IF + "14." + idx);
        cols[OUT_ERR] = new OID(IF + "20." + idx);
        if (v1) {                                              // sem os OIDs HC: o agente v1 responderia noSuchName
            OID[] v1Cols = new OID[5];
            v1Cols[0] = cols[OPER];
            v1Cols[1] = cols[IN32];
            v1Cols[2] = cols[OUT32];
            v1Cols[3] = cols[IN_ERR];
            v1Cols[4] = cols[OUT_ERR];
            return v1Cols;
        }
        return cols;
    }

    private static Reading reading(int idx, Map<OID, Variable> v, boolean v1) {
        Long hcIn = v1 ? null : num(v.get(new OID(IFX + "6." + idx)));
        Long hcOut = v1 ? null : num(v.get(new OID(IFX + "10." + idx)));
        Long in32 = num(v.get(new OID(IF + "10." + idx)));
        Long out32 = num(v.get(new OID(IF + "16." + idx)));
        Long inErr = num(v.get(new OID(IF + "14." + idx)));
        Long outErr = num(v.get(new OID(IF + "20." + idx)));
        Long oper = num(v.get(new OID(IF + "8." + idx)));
        Integer operStatus = oper == null ? null : oper.intValue();
        if (hcIn != null && hcOut != null) {
            return new Reading(idx, hcIn, hcOut, 64, inErr, outErr, operStatus);
        }
        return new Reading(idx, in32, out32, 32, inErr, outErr, operStatus);
    }

    private static Long num(Variable v) {
        return v == null || v.isException() ? null : v.toLong();
    }
}
