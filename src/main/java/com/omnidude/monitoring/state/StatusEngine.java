package com.omnidude.monitoring.state;

import com.omnidude.device.Status;
import com.omnidude.monitoring.CheckType;
import com.omnidude.monitoring.ServiceCheck;
import com.omnidude.monitoring.probe.ProbeResult;
import java.util.Collection;
import java.util.List;

/** Máquina de estados com histerese (seção 4.1 do plano). Sem I/O: só altera os objetos recebidos. */
public final class StatusEngine {

    private static final int COUNTER_CAP = 1_000_000;

    private StatusEngine() {
    }

    /** Aplica um resultado à checagem e devolve o status anterior. */
    public static Status applyResult(ServiceCheck check, ProbeResult result, long now) {
        Status before = check.getStatus();
        Status after = before;
        check.setLastCheckAt(now);
        check.setLastResponseMs(result.success() ? result.responseMs() : null);

        if (result.success()) {
            check.setConsecutiveFailures(0);
            check.setConsecutiveSuccesses(Math.min(check.getConsecutiveSuccesses() + 1, COUNTER_CAP));
            if (before != Status.DOWN || check.getConsecutiveSuccesses() >= check.getRecoverThreshold()) {
                after = isSlow(check, result) ? Status.WARNING : Status.UP;
            }
        } else {
            check.setConsecutiveSuccesses(0);
            check.setConsecutiveFailures(Math.min(check.getConsecutiveFailures() + 1, COUNTER_CAP));
            if (check.getConsecutiveFailures() >= check.getFailThreshold()) {
                after = Status.DOWN;
            }
        }
        if (after != before) {
            check.setStatus(after);
            check.setStatusSince(now);
        }
        return before;
    }

    private static boolean isSlow(ServiceCheck check, ProbeResult result) {
        return check.getWarnLatencyMs() != null && result.responseMs() != null
                && result.responseMs() > check.getWarnLatencyMs();
    }

    /** A checagem que define "o dispositivo responde": ICMP; sem ela, SNMP; sem nenhuma das duas, null. */
    public static ServiceCheck anchorOf(Collection<ServiceCheck> checks) {
        ServiceCheck snmp = null;
        for (ServiceCheck c : checks) {
            if (!c.isEnabled()) {
                continue;
            }
            if (c.getType() == CheckType.ICMP) {
                return c;
            }
            if (c.getType() == CheckType.SNMP && snmp == null) {
                snmp = c;
            }
        }
        return snmp;
    }

    public static Status deviceStatus(Collection<ServiceCheck> allChecks) {
        List<ServiceCheck> active = allChecks.stream().filter(ServiceCheck::isEnabled).toList();
        ServiceCheck anchor = anchorOf(active);
        boolean anyDown = false;
        boolean anyWarning = false;
        boolean anyKnown = false;
        for (ServiceCheck c : active) {
            if (c.getStatus() == Status.UNKNOWN) {
                continue;
            }
            anyKnown = true;
            anyDown |= c.getStatus() == Status.DOWN;
            anyWarning |= c.getStatus() == Status.WARNING;
        }
        if (anchor != null) {
            if (anchor.getStatus() == Status.DOWN) {
                return Status.DOWN;
            }
            if (anchor.getStatus() == Status.UNKNOWN) {
                return Status.UNKNOWN;
            }
            return anyDown || anyWarning ? Status.WARNING : Status.UP;
        }
        if (!anyKnown) {
            return Status.UNKNOWN;
        }
        return anyDown ? Status.DOWN : anyWarning ? Status.WARNING : Status.UP;
    }
}
