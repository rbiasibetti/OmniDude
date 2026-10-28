package com.omnidude.monitoring.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.monitoring.CheckType;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TcpProbeTest {

    private final TcpProbe probe = new TcpProbe();

    private Target target(String ip, Integer port, int timeoutMs) {
        return new Target(CheckType.TCP, ip, port, timeoutMs, Map.of(), null);
    }

    @Test
    void openPortSucceeds() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            ProbeResult r = probe.execute(target("127.0.0.1", server.getLocalPort(), 1000));
            assertThat(r.success()).isTrue();
            assertThat(r.responseMs()).isBetween(0, 1000);
        }
    }

    @Test
    void closedPortFails() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ProbeResult r = probe.execute(target("127.0.0.1", port, 1000));
        assertThat(r.success()).isFalse();
    }

    @Test
    void unroutableAddressTimesOut() {
        ProbeResult r = probe.execute(target("192.0.2.1", 80, 700));
        assertThat(r.success()).isFalse();
    }

    @Test
    void rejectsInvalidTargets() {
        assertThat(probe.execute(target("-f", 80, 500)).success()).isFalse();
        assertThat(probe.execute(target("127.0.0.1", null, 500)).success()).isFalse();
    }
}
