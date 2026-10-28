package com.omnidude.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.device.Status;
import com.omnidude.monitoring.probe.ProbeResult;
import com.omnidude.monitoring.state.StatusEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusEngineTest {

    private static ServiceCheck check(CheckType type, int failThreshold, int recoverThreshold, Integer warnMs) {
        ServiceCheck c = new ServiceCheck();
        c.setId((long) type.ordinal() + 1);
        c.setType(type);
        c.setFailThreshold(failThreshold);
        c.setRecoverThreshold(recoverThreshold);
        c.setWarnLatencyMs(warnMs);
        return c;
    }

    @Test
    void firstSuccessMakesCheckUp() {
        ServiceCheck c = check(CheckType.ICMP, 3, 1, null);
        Status before = StatusEngine.applyResult(c, ProbeResult.ok(5), 100);
        assertThat(before).isEqualTo(Status.UNKNOWN);
        assertThat(c.getStatus()).isEqualTo(Status.UP);
        assertThat(c.getStatusSince()).isEqualTo(100);
        assertThat(c.getLastResponseMs()).isEqualTo(5);
    }

    @Test
    void needsConsecutiveFailuresToGoDown() {
        ServiceCheck c = check(CheckType.ICMP, 3, 1, null);
        StatusEngine.applyResult(c, ProbeResult.ok(5), 1);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 2);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 3);
        assertThat(c.getStatus()).isEqualTo(Status.UP);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 4);
        assertThat(c.getStatus()).isEqualTo(Status.DOWN);
        assertThat(c.getStatusSince()).isEqualTo(4);
    }

    @Test
    void isolatedFailureIsForgivenAfterSuccess() {
        ServiceCheck c = check(CheckType.ICMP, 3, 1, null);
        StatusEngine.applyResult(c, ProbeResult.ok(5), 1);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 2);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 3);
        StatusEngine.applyResult(c, ProbeResult.ok(5), 4);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 5);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 6);
        assertThat(c.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void recoveryThresholdDelaysReturnToUp() {
        ServiceCheck c = check(CheckType.ICMP, 1, 2, null);
        StatusEngine.applyResult(c, ProbeResult.fail("x"), 1);
        assertThat(c.getStatus()).isEqualTo(Status.DOWN);
        StatusEngine.applyResult(c, ProbeResult.ok(3), 2);
        assertThat(c.getStatus()).isEqualTo(Status.DOWN);
        StatusEngine.applyResult(c, ProbeResult.ok(3), 3);
        assertThat(c.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void highLatencyIsWarningAndRecovers() {
        ServiceCheck c = check(CheckType.ICMP, 3, 1, 100);
        StatusEngine.applyResult(c, ProbeResult.ok(250), 1);
        assertThat(c.getStatus()).isEqualTo(Status.WARNING);
        StatusEngine.applyResult(c, ProbeResult.ok(20), 2);
        assertThat(c.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void statusSinceOnlyChangesOnTransition() {
        ServiceCheck c = check(CheckType.ICMP, 3, 1, null);
        StatusEngine.applyResult(c, ProbeResult.ok(1), 10);
        StatusEngine.applyResult(c, ProbeResult.ok(1), 20);
        assertThat(c.getStatusSince()).isEqualTo(10);
    }

    @Test
    void deviceIsDownWhenIcmpAnchorIsDown() {
        ServiceCheck icmp = check(CheckType.ICMP, 1, 1, null);
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        icmp.setStatus(Status.DOWN);
        tcp.setStatus(Status.UP);
        assertThat(StatusEngine.deviceStatus(List.of(icmp, tcp))).isEqualTo(Status.DOWN);
    }

    @Test
    void deviceIsWarningWhenIcmpUpButServiceDown() {
        ServiceCheck icmp = check(CheckType.ICMP, 1, 1, null);
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        icmp.setStatus(Status.UP);
        tcp.setStatus(Status.DOWN);
        assertThat(StatusEngine.deviceStatus(List.of(icmp, tcp))).isEqualTo(Status.WARNING);
    }

    @Test
    void deviceIsUnknownUntilAnchorHasResult() {
        ServiceCheck icmp = check(CheckType.ICMP, 1, 1, null);
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        tcp.setStatus(Status.UP);
        assertThat(StatusEngine.deviceStatus(List.of(icmp, tcp))).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void snmpIsAnchorWhenThereIsNoIcmp() {
        ServiceCheck snmp = check(CheckType.SNMP, 1, 1, null);
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        snmp.setStatus(Status.DOWN);
        tcp.setStatus(Status.UP);
        assertThat(StatusEngine.deviceStatus(List.of(snmp, tcp))).isEqualTo(Status.DOWN);
    }

    @Test
    void withoutAnchorAnyDownMeansDown() {
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        tcp.setStatus(Status.DOWN);
        assertThat(StatusEngine.deviceStatus(List.of(tcp))).isEqualTo(Status.DOWN);
        assertThat(StatusEngine.deviceStatus(List.of())).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void disabledChecksAreIgnored() {
        ServiceCheck icmp = check(CheckType.ICMP, 1, 1, null);
        ServiceCheck tcp = check(CheckType.TCP, 1, 1, null);
        icmp.setStatus(Status.UP);
        tcp.setStatus(Status.DOWN);
        tcp.setEnabled(false);
        assertThat(StatusEngine.deviceStatus(List.of(icmp, tcp))).isEqualTo(Status.UP);
    }
}
