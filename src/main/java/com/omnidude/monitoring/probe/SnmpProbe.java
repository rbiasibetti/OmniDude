package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import com.omnidude.snmp.SnmpClient;
import java.util.List;
import java.util.Set;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import org.springframework.stereotype.Component;

/** Alcançabilidade SNMP: GET de {@code sysUpTime.0} (SNMPv1/v2c). */
@Component
public class SnmpProbe implements Probe {

    static final OID SYS_UPTIME = new OID("1.3.6.1.2.1.1.3.0");

    private final SnmpClient client;

    public SnmpProbe(SnmpClient client) {
        this.client = client;
    }

    @Override
    public Set<CheckType> types() {
        return Set.of(CheckType.SNMP);
    }

    @Override
    public ProbeResult execute(Target target) {
        SnmpCredentials cred = target.snmp();
        if (cred == null) {
            return ProbeResult.fail("sem perfil SNMP");
        }
        if (!AddressValidator.isValidIp(target.address())) {
            return ProbeResult.fail("alvo inválido");
        }
        long start = System.nanoTime();
        try {
            List<VariableBinding> vbs = client.get(target.address(), target.port() == null ? 161 : target.port(), cred,
                    target.timeoutMs(), SYS_UPTIME);
            if (vbs.isEmpty() || vbs.get(0).getVariable().isException()) {
                return ProbeResult.fail("sysUpTime indisponível");
            }
            return ProbeResult.ok(TcpProbe.elapsedMs(start));
        } catch (java.io.IOException e) {
            return ProbeResult.fail(e.getMessage());
        }
    }
}
