package com.omnidude.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.monitoring.probe.SnmpCredentials;
import com.omnidude.snmp.IfTablePoller.Reading;
import com.omnidude.snmp.SnmpClient.SnmpException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.snmp4j.security.UsmUser;

class IfTablePollerTest {

    private final SnmpClient client = new SnmpClient();
    private final IfTablePoller poller = new IfTablePoller(client);
    private final SnmpInventory inventory = new SnmpInventory(client);
    private FakeSnmpAgent agent;

    @BeforeEach
    void start() throws IOException {
        client.start();
    }

    @AfterEach
    void stop() throws IOException {
        client.stop();
        if (agent != null) {
            agent.close();
        }
    }

    private TestMib mib() {
        TestMib m = new TestMib()
                .system("Switch", "1.3.6.1.4.1.9.1.716", "sw", 1000)
                .iface(1, "Gi0/1", "aa:bb:cc:00:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "aa:bb:cc:00:00:02", 6, 2, 1, 1000)
                .iface(3, "Gi0/3", "aa:bb:cc:00:00:03", 6, 1, 1, 1000)
                .iface(4, "Gi0/4", "aa:bb:cc:00:00:04", 6, 1, 1, 1000);
        m.setCounters(1, 5_000_000_000L, 700L, 3, 4);
        m.setCounters(2, 10L, 20L, 0, 0);
        m.setCounters(3, 30L, 40L, 0, 0);
        m.setCounters(4, 50L, 60L, 0, 0);
        return m;
    }

    private SnmpAccess access(String version, String community) {
        return new SnmpAccess(1L, new SnmpCredentials(version, community, 0), agent.port(), 1500);
    }

    @Test
    void readsHighCapacityCountersErrorsAndOperStatusForSeveralInterfaces() throws Exception {
        agent = new FakeSnmpAgent("c", "127.0.0.1", 0, mib().build());
        Map<Integer, Reading> r = poller.read("127.0.0.1", access("2c", "c"), List.of(1, 2, 3, 4));
        assertThat(r).hasSize(4);                                   // 4 interfaces: duas consultas (3 + 1)
        assertThat(r.get(1).bits()).isEqualTo(64);
        assertThat(r.get(1).inOctets()).as("acima de 2^32 só existe nos 64 bits").isEqualTo(5_000_000_000L);
        assertThat(r.get(1).outOctets()).isEqualTo(700L);
        assertThat(r.get(1).inErrors()).isEqualTo(3L);
        assertThat(r.get(1).outErrors()).isEqualTo(4L);
        assertThat(r.get(1).operStatus()).isEqualTo(1);
        assertThat(r.get(2).operStatus()).isEqualTo(2);
        assertThat(r.get(4).inOctets()).isEqualTo(50L);
    }

    @Test
    void fallsBackToThirtyTwoBitCountersWithoutIfXTable() throws Exception {
        TestMib m = mib();
        m.removeHighCapacityCounters(1);
        agent = new FakeSnmpAgent("c", "127.0.0.1", 0, m.build());
        Reading r = poller.read("127.0.0.1", access("2c", "c"), List.of(1)).get(1);
        assertThat(r.bits()).isEqualTo(32);
        assertThat(r.inOctets()).as("contador de 32 bits truncado como num agente real")
                .isEqualTo(5_000_000_000L & 0xFFFFFFFFL);
    }

    @Test
    void snmpV1UsesOnlyThirtyTwoBitCounters() throws Exception {
        agent = new FakeSnmpAgent("c", "127.0.0.1", 0, mib().build());
        Reading r = poller.read("127.0.0.1", access("1", "c"), List.of(1, 3)).get(3);
        assertThat(r.bits()).isEqualTo(32);
        assertThat(r.inOctets()).isEqualTo(30L);
        assertThat(r.hasCounters()).isTrue();
    }

    @Test
    void interfaceWithoutCountersIsReportedWithoutThem() throws Exception {
        TestMib m = mib();
        m.removeHighCapacityCounters(2);
        agent = new FakeSnmpAgent("c", "127.0.0.1", 0, m.build());
        Reading missing = poller.read("127.0.0.1", access("2c", "c"), List.of(99)).get(99);
        assertThat(missing.hasCounters()).isFalse();
        assertThat(missing.operStatus()).isNull();
    }

    @Test
    void timeoutRaisesSnmpException() throws Exception {
        agent = new FakeSnmpAgent("c", "127.0.0.1", 0, mib().build());
        assertThatThrownBy(() -> poller.read("127.0.0.1", access("2c", "outra"), List.of(1)))
                .isInstanceOf(SnmpException.class);
    }

    // --- SNMPv3 ----------------------------------------------------------------------------------------------

    private static final String USER = "monitor";

    static Stream<Arguments> v3Levels() {
        return Stream.of(
                Arguments.of("NO_AUTH_NO_PRIV", null, null, null, null),
                Arguments.of("AUTH_NO_PRIV", "SHA", "auth-pass-123", null, null),
                Arguments.of("AUTH_PRIV", "SHA", "auth-pass-123", "AES128", "priv-pass-123"),
                Arguments.of("AUTH_PRIV", "SHA256", "auth-pass-123", "AES192", "priv-pass-123"),
                Arguments.of("AUTH_PRIV", "SHA512", "auth-pass-123", "AES256", "priv-pass-123"));
    }

    private static UsmUser agentUser(String level, String auth, String authPass, String priv, String privPass) {
        return SnmpClient.usmUser(new SnmpCredentials.V3(USER, level, auth, authPass, priv, privPass));
    }

    private SnmpAccess v3Access(String level, String auth, String authPass, String priv, String privPass) {
        return new SnmpAccess(1L, new SnmpCredentials("3", null, 0,
                new SnmpCredentials.V3(USER, level, auth, authPass, priv, privPass)), agent.port(), 3000);
    }

    @ParameterizedTest(name = "{0} {1}/{3}")
    @MethodSource("v3Levels")
    void snmpV3AtEverySecurityLevel(String level, String auth, String authPass, String priv, String privPass)
            throws Exception {
        agent = FakeSnmpAgent.v3(agentUser(level, auth, authPass, priv, privPass), mib().build());
        SnmpAccess access = v3Access(level, auth, authPass, priv, privPass);

        Reading r = poller.read("127.0.0.1", access, List.of(1, 2)).get(1);
        assertThat(r.inOctets()).isEqualTo(5_000_000_000L);
        assertThat(inventory.readSystem("127.0.0.1", access).sysName()).isEqualTo("sw");
        assertThat(inventory.readInterfaces("127.0.0.1", access)).hasSize(4);          // walk (GETBULK) em v3
    }

    @Test
    void snmpV3WrongPassphraseFails() throws Exception {
        agent = FakeSnmpAgent.v3(agentUser("AUTH_PRIV", "SHA", "auth-pass-123", "AES128", "priv-pass-123"),
                mib().build());
        assertThatThrownBy(() -> poller.read("127.0.0.1",
                v3Access("AUTH_PRIV", "SHA", "senha-errada-1", "AES128", "priv-pass-123"), List.of(1)))
                .isInstanceOf(SnmpException.class);
        assertThatThrownBy(() -> poller.read("127.0.0.1",
                v3Access("AUTH_PRIV", "SHA", "auth-pass-123", "AES128", "priv-errada-12"), List.of(1)))
                .isInstanceOf(SnmpException.class);
    }

    @Test
    void sameUsernameWithDifferentPassphrasesDoesNotCollide() throws Exception {
        agent = FakeSnmpAgent.v3(agentUser("AUTH_NO_PRIV", "SHA", "primeira-senha", null, null), mib().build());
        try (FakeSnmpAgent other = FakeSnmpAgent.v3(agentUser("AUTH_NO_PRIV", "SHA", "segunda-senha-x", null, null),
                mib().build())) {
            SnmpAccess a = v3Access("AUTH_NO_PRIV", "SHA", "primeira-senha", null, null);
            SnmpAccess b = new SnmpAccess(2L, new SnmpCredentials("3", null, 0, new SnmpCredentials.V3(USER,
                    "AUTH_NO_PRIV", "SHA", "segunda-senha-x", null, null)), other.port(), 3000);
            assertThat(poller.read("127.0.0.1", a, List.of(1))).hasSize(1);
            assertThat(poller.read("127.0.0.1", b, List.of(1))).hasSize(1);
            assertThat(poller.read("127.0.0.1", a, List.of(1))).hasSize(1);
        }
    }

    @Test
    void credentialsNeverShowSecretsInToString() {
        var v3 = new SnmpCredentials.V3(USER, "AUTH_PRIV", "SHA", "auth-pass-123", "AES128", "priv-pass-123");
        String text = new SnmpCredentials("3", null, 1, v3).toString();
        assertThat(text).doesNotContain("auth-pass-123").doesNotContain("priv-pass-123").contains(USER);
    }
}
