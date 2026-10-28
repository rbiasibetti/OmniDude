package com.omnidude.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.monitoring.probe.SnmpCredentials;
import com.omnidude.snmp.LldpReader.Neighbor;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.snmp.SnmpInventory.InterfaceInfo;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnmpInventoryTest {

    private static final String COMMUNITY = "inv-community";

    private final SnmpClient client = new SnmpClient();
    private final SnmpInventory inventory = new SnmpInventory(client);
    private final LldpReader lldp = new LldpReader(client);
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

    private SnmpAccess access(String version, int timeoutMs) {
        return new SnmpAccess(1L, new SnmpCredentials(version, COMMUNITY, 0), agent.port(), timeoutMs);
    }

    private TestMib switchMib() {
        return new TestMib()
                .system("Cisco IOS Software, C2960 Switch\nVersion 15.0", "1.3.6.1.4.1.9.1.716", "sw-core", 360000)
                .iface(1, "Gi0/1", "aa:bb:cc:00:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "aa:bb:cc:00:00:02", 6, 2, 1, 100)
                .iface(3, "Gi0/3", null, 6, 2, 2, 10)
                .iface(24, "Lo0", null, 24, 1, 1, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2c", "1"})
    void readsSystemGroup(String version) throws IOException {
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, switchMib().build());
        var sys = inventory.readSystem("127.0.0.1", access(version, 1000));
        assertThat(sys.sysName()).isEqualTo("sw-core");
        assertThat(sys.sysObjectId()).isEqualTo("1.3.6.1.4.1.9.1.716");
        assertThat(sys.sysDescr()).as("quebra de linha vira espaço").isEqualTo("Cisco IOS Software, C2960 Switch Version 15.0");
        assertThat(sys.uptimeTicks()).isEqualTo(360000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2c", "1"})
    void readsInterfacesWithBulkAndNext(String version) throws IOException {
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, switchMib().build());
        List<InterfaceInfo> ifs = inventory.readInterfaces("127.0.0.1", access(version, 1000));
        assertThat(ifs).extracting(InterfaceInfo::ifIndex).containsExactly(1, 2, 3, 24);
        InterfaceInfo gi1 = ifs.get(0);
        assertThat(gi1.name()).isEqualTo("Gi0/1");
        assertThat(gi1.descr()).isEqualTo("descr-Gi0/1");
        assertThat(gi1.alias()).isEqualTo("alias-Gi0/1");
        assertThat(gi1.mac()).isEqualTo("aa:bb:cc:00:00:01");
        assertThat(gi1.speedBps()).as("ifHighSpeed (Mbps) vira bps").isEqualTo(1_000_000_000L);
        assertThat(gi1.operStatus()).isEqualTo(1);
        assertThat(ifs.get(1).speedBps()).isEqualTo(100_000_000L);
        assertThat(ifs.get(2).mac()).as("sem MAC").isNull();
        assertThat(ifs.get(3).loopback()).isTrue();
    }

    @Test
    void largeTableIsFullyWalked() throws IOException {
        TestMib mib = new TestMib().system("big", "1.3.6.1.4.1.9.1.1", "big", 1);
        for (int i = 1; i <= 60; i++) {
            mib.iface(i, "Gi1/" + i, String.format("aa:bb:cc:00:%02x:%02x", i / 256, i % 256), 6, 1, 1, 1000);
        }
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, mib.build());
        assertThat(inventory.readInterfaces("127.0.0.1", access("2c", 1000))).hasSize(60);
    }

    @Test
    void wrongCommunityIsATimeout() throws IOException {
        agent = new FakeSnmpAgent("outra", "127.0.0.1", 0, switchMib().build());
        assertThatThrownBy(() -> inventory.readSystem("127.0.0.1", access("2c", 300)))
                .isInstanceOf(SnmpException.class).hasMessageContaining("timeout");
        assertThatThrownBy(() -> inventory.readInterfaces("127.0.0.1", access("2c", 300))).isInstanceOf(SnmpException.class);
    }

    @Test
    void agentWithoutIfTableIsReportedClearly() throws IOException {
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, new TestMib().system("x", "1.3.6.1.4.1.1", "x", 1).build());
        assertThatThrownBy(() -> inventory.readInterfaces("127.0.0.1", access("2c", 500)))
                .isInstanceOf(SnmpException.class).hasMessageContaining("ifTable");
    }

    @Test
    void readsLldpNeighborsWithManagementAddress() throws IOException {
        TestMib mib = switchMib()
                .lldpNeighbor(1, 1, "de:ad:be:ef:00:01", "ge-0/0/1", "uplink para core", "rtr-borda", "10.9.8.7")
                .lldpNeighbor(2, 1, "de:ad:be:ef:00:02", "eth0", "eth0", "srv-db", null);
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, mib.build());
        List<Neighbor> n = lldp.read("127.0.0.1", access("2c", 1000));
        assertThat(n).hasSize(2);
        Neighbor first = n.get(0);
        assertThat(first.localPort()).isEqualTo(1);
        assertThat(first.chassisMac()).isEqualTo("de:ad:be:ef:00:01");
        assertThat(first.portId()).isEqualTo("ge-0/0/1");
        assertThat(first.portDesc()).isEqualTo("uplink para core");
        assertThat(first.sysName()).isEqualTo("rtr-borda");
        assertThat(first.mgmtIps()).containsExactly("10.9.8.7");
        assertThat(n.get(1).mgmtIps()).isEmpty();
        assertThat(n.get(1).chassisMac()).isEqualTo("de:ad:be:ef:00:02");
    }

    @Test
    void deviceWithoutLldpYieldsNoNeighbors() throws IOException {
        agent = new FakeSnmpAgent(COMMUNITY, "127.0.0.1", 0, switchMib().build());
        assertThat(lldp.read("127.0.0.1", access("2c", 1000))).isEmpty();
    }
}
