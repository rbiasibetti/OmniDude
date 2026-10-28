package com.omnidude.monitoring.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.FakeSnmpAgent;
import com.omnidude.monitoring.CheckType;
import com.omnidude.snmp.SnmpClient;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SnmpProbeTest {

    private static final String COMMUNITY = "s3gredo";

    private final SnmpClient client = new SnmpClient();
    private final SnmpProbe probe = new SnmpProbe(client);
    private FakeSnmpAgent agent;

    @BeforeEach
    void start() throws IOException {
        client.start();
        agent = new FakeSnmpAgent(COMMUNITY);
    }

    @AfterEach
    void stop() throws IOException {
        client.stop();
        agent.close();
    }

    private ProbeResult run(String community, String version, int timeoutMs, int retries) {
        return probe.execute(new Target(CheckType.SNMP, "127.0.0.1", agent.port(), timeoutMs, Map.of(),
                new SnmpCredentials(version, community, retries)));
    }

    @Test
    void v2cWithCorrectCommunitySucceeds() {
        ProbeResult r = run(COMMUNITY, "2c", 1000, 0);
        assertThat(r.success()).as(r.detail()).isTrue();
    }

    @Test
    void v1WithCorrectCommunitySucceeds() {
        assertThat(run(COMMUNITY, "1", 1000, 0).success()).isTrue();
    }

    @Test
    void wrongCommunityTimesOut() {
        ProbeResult r = run("errada", "2c", 300, 0);
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("timeout");
    }

    @Test
    void missingProfileFailsClearly() {
        ProbeResult r = probe.execute(new Target(CheckType.SNMP, "127.0.0.1", agent.port(), 300, Map.of(), null));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("perfil");
    }

    @Test
    void credentialsNeverAppearInToString() {
        assertThat(new SnmpCredentials("2c", "topsecret", 1).toString()).doesNotContain("topsecret");
    }
}
