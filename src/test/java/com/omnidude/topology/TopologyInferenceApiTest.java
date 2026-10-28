package com.omnidude.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Topologia por FDB, ARP, CDP, MNDP e gateway, contra agentes SNMP simulados. Banco próprio: toda rodada lê todos. */
class TopologyInferenceApiTest extends ApiTestSupport {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("omnidude-topology-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("omnidude.data-dir", DATA_DIR::toString);
        registry.add("omnidude.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @SpyBean GatewayResolver gateway;

    private final List<FakeSnmpAgent> agents = new ArrayList<>();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM devices");                        // cascata: enlaces, interfaces, nós de mapa
        jdbc.update("DELETE FROM snmp_profiles");
        doReturn(Optional.empty()).when(gateway).defaultGateway();
    }

    @AfterEach
    void closeAgents() throws IOException {
        for (FakeSnmpAgent a : agents) {
            a.close();
        }
        agents.clear();
    }

    private static String mac(int n) {
        return String.format("02:00:00:00:91:%02x", n);
    }

    /** Dispositivo sem SNMP, com MAC conhecido. */
    private long host(String name, String ip, String type, int macId) throws Exception {
        long id = createDevice(name, ip, type).get("id").asLong();
        jdbc.update("UPDATE devices SET mac_address = ? WHERE id = ?", mac(macId), id);
        return id;
    }

    /** Dispositivo com agente SNMP (interfaces já lidas). */
    private long snmpDevice(String name, String ip, String type, TestMib mib, int ownMacId) throws Exception {
        FakeSnmpAgent agent = new FakeSnmpAgent("topo", ip, 0, mib.build());
        agents.add(agent);
        long profile = createProfile("topo-" + name, "topo", agent.port()).get("id").asLong();
        long id = createDevice(name, ip, type).get("id").asLong();
        putJson("/api/devices/" + id, Map.of("name", name, "primaryIp", ip, "deviceType", type, "snmpProfileId", profile))
                .andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/refresh").session(admin).with(csrf())).andExpect(status().isOk());
        return id;
    }

    private TestMib switchMib(String name, int ownMacId, int ports) {
        TestMib m = new TestMib().system("Switch", "1.3.6.1.4.1.9.1.716", name, 1000);
        for (int p = 1; p <= ports; p++) {
            m.iface(p, "Gi0/" + p, p == 1 ? mac(ownMacId) : null, 6, 1, 1, 1000).bridgePort(p, p);
        }
        return m.fdb(mac(ownMacId), 0, 4);
    }

    private JsonNode refreshAll() throws Exception {
        return read(mvc.perform(post("/api/topology/refresh").session(admin).with(csrf())).andExpect(status().isOk()));
    }

    private List<JsonNode> links() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        getJson("/api/links").forEach(out::add);
        return out;
    }

    private static boolean between(JsonNode l, long a, long b) {
        long s = l.get("sourceDeviceId").asLong();
        long t = l.get("targetDeviceId").asLong();
        return (s == a && t == b) || (s == b && t == a);
    }

    private JsonNode linkBetween(long a, long b) throws Exception {
        return links().stream().filter(l -> between(l, a, b)).findFirst().orElse(null);
    }

    private List<JsonNode> virtualNodes() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        getJson("/api/devices").forEach(d -> {
            if (d.get("virtualNode").asBoolean()) {
                out.add(d);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------

    @Test
    void switchWithHubAndConsumerRouterBehindIt() throws Exception {
        long pcA = host("pcA", "127.0.91.10", "WORKSTATION", 10);
        long pcB = host("pcB", "127.0.91.11", "WORKSTATION", 11);
        long pcC = host("pcC", "127.0.91.12", "WORKSTATION", 12);
        long pcD = host("pcD", "127.0.91.13", "WORKSTATION", 13);
        long router = host("roteador-wifi", "127.0.91.1", "ROUTER", 14);
        long phone = host("celular", "127.0.91.14", "OTHER", 15);
        TestMib mib = switchMib("sw", 1, 4)
                .fdb(mac(10), 1, 3)
                .fdb(mac(11), 2, 3).fdb(mac(12), 2, 3).fdb(mac(13), 2, 3)         // porta 2: um hub com 3 PCs
                .fdb(mac(14), 3, 3).fdb(mac(15), 3, 3);                           // porta 3: o roteador e um celular (Wi-Fi)
        long sw = snmpDevice("sw", "127.0.91.2", "SWITCH", mib, 1);

        JsonNode r = refreshAll();
        JsonNode inf = r.get("inference");
        assertThat(inf.get("virtualNodes").asInt()).isEqualTo(1);
        assertThat(inf.get("fdbLinks").asInt()).as("sw↔pcA, sw↔nó, nó↔3 PCs, sw↔roteador").isEqualTo(6);
        assertThat(inf.get("inferredLinks").asInt()).as("celular atrás do roteador").isEqualTo(1);
        assertThat(r.get("errors")).isEmpty();

        JsonNode direct = linkBetween(sw, pcA);
        assertThat(direct.get("origin").asText()).isEqualTo("FDB");
        assertThat(direct.get("state").asText()).isEqualTo("SUGGESTED");
        assertThat(direct.get("evidence").asText()).contains("Único MAC");
        String ifNames = direct.path("sourceInterface").asText() + direct.path("targetInterface").asText();
        assertThat(ifNames).as("a porta do switch foi resolvida").contains("Gi0/1");

        assertThat(linkBetween(sw, pcB)).as("hosts do hub não vão direto ao switch").isNull();
        List<JsonNode> clouds = virtualNodes();
        assertThat(clouds).hasSize(1);
        long hub = clouds.get(0).get("id").asLong();
        assertThat(clouds.get(0).get("name").asText()).startsWith("Switch/hub não gerenciável");
        assertThat(linkBetween(sw, hub).get("sourceInterface").asText() + linkBetween(sw, hub).path("targetInterface").asText())
                .contains("Gi0/2");
        for (long pc : new long[] {pcB, pcC, pcD}) {
            assertThat(linkBetween(hub, pc)).isNotNull();
        }

        assertThat(linkBetween(sw, router).get("origin").asText()).isEqualTo("FDB");
        JsonNode behind = linkBetween(router, phone);
        assertThat(behind.get("origin").asText()).isEqualTo("INFERRED");
        assertThat(behind.get("evidence").asText()).contains("Wi-Fi");

        // a rodada seguinte não duplica nada
        JsonNode again = refreshAll().get("inference");
        assertThat(again.get("fdbLinks").asInt()).isZero();
        assertThat(again.get("inferredLinks").asInt()).isZero();
        assertThat(again.get("staleRemoved").asInt()).isZero();
        assertThat(virtualNodes()).hasSize(1);

        // o hub some e os PCs se espalham: o nó virtual e o que dependia dele saem, e o que mudou é reproposto
        mib.clearFdb();
        mib.fdb(mac(1), 0, 4).fdb(mac(10), 1, 3).fdb(mac(11), 2, 3).fdb(mac(14), 3, 3).fdb(mac(15), 3, 3);
        JsonNode moved = refreshAll().get("inference");
        assertThat(moved.get("staleRemoved").asInt()).as("sw↔nó e nó↔PCs").isGreaterThanOrEqualTo(4);
        assertThat(virtualNodes()).isEmpty();
        assertThat(linkBetween(sw, pcB)).as("agora é host direto").isNotNull();
        assertThat(linkBetween(router, phone)).as("segue atrás do roteador").isNotNull();
    }

    @Test
    void confirmedLinksAndTheirVirtualNodeSurviveStaleCleanup() throws Exception {
        long pcB = host("pcB", "127.0.91.11", "WORKSTATION", 11);
        long pcC = host("pcC", "127.0.91.12", "WORKSTATION", 12);
        TestMib mib = switchMib("sw", 1, 2).fdb(mac(11), 2, 3).fdb(mac(12), 2, 3);
        long sw = snmpDevice("sw", "127.0.91.2", "SWITCH", mib, 1);
        refreshAll();
        long hub = virtualNodes().get(0).get("id").asLong();
        mvc.perform(post("/api/links/" + linkBetween(sw, hub).get("id").asLong() + "/confirm").session(admin).with(csrf()))
                .andExpect(status().isOk());

        mib.clearFdb();
        mib.fdb(mac(1), 0, 4);                                       // o switch esqueceu tudo
        refreshAll();
        assertThat(virtualNodes()).as("o usuário confirmou um enlace do nó: ele fica").hasSize(1);
        assertThat(linkBetween(sw, hub).get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(linkBetween(hub, pcB)).as("as sugestões que ninguém confirmou saem").isNull();
        assertThat(linkBetween(hub, pcC)).isNull();
    }

    @Test
    void chainOfManagedSwitchesLinksTheUplinkPortsAndKeepsHostsOnTheEdge() throws Exception {
        long pc1 = host("pc1", "127.0.91.10", "WORKSTATION", 10);
        long pc2 = host("pc2", "127.0.91.11", "WORKSTATION", 11);
        long pc3 = host("pc3", "127.0.91.12", "WORKSTATION", 12);
        TestMib m1 = switchMib("sw1", 1, 3).fdb(mac(10), 1, 3).fdb(mac(2), 3, 3).fdb(mac(11), 3, 3).fdb(mac(12), 3, 3);
        TestMib m2 = switchMib("sw2", 2, 3).fdb(mac(1), 1, 3).fdb(mac(10), 1, 3).fdb(mac(11), 2, 3).fdb(mac(12), 3, 3);
        long sw1 = snmpDevice("sw1", "127.0.91.2", "SWITCH", m1, 1);
        long sw2 = snmpDevice("sw2", "127.0.91.3", "SWITCH", m2, 2);

        refreshAll();
        JsonNode uplink = linkBetween(sw1, sw2);
        assertThat(uplink).isNotNull();
        assertThat(uplink.get("origin").asText()).isEqualTo("FDB");
        assertThat(List.of(uplink.path("sourceInterface").asText(), uplink.path("targetInterface").asText()))
                .containsExactlyInAnyOrder("Gi0/3", "Gi0/1");
        assertThat(linkBetween(sw1, pc1)).isNotNull();
        assertThat(linkBetween(sw2, pc2)).isNotNull();
        assertThat(linkBetween(sw2, pc3)).isNotNull();
        assertThat(linkBetween(sw1, pc2)).isNull();
        assertThat(linkBetween(sw1, pc3)).isNull();
        assertThat(links().stream().filter(l -> between(l, sw1, sw2))).as("visto dos dois lados, um enlace só").hasSize(1);
    }

    @Test
    void devicesWithNoTopologyDataGoToTheGatewayWithLowConfidence() throws Exception {
        long gw = host("gateway", "127.0.92.1", "ROUTER", 1);
        long h1 = host("h1", "127.0.92.10", "WORKSTATION", 10);
        long h2 = host("h2", "127.0.92.11", "OTHER", 11);
        long outside = host("fora", "127.0.93.5", "WORKSTATION", 12);
        doReturn(Optional.of("127.0.92.1")).when(gateway).defaultGateway();

        JsonNode inf = refreshAll().get("inference");
        assertThat(inf.get("gatewayLinks").asInt()).isEqualTo(2);
        assertThat(inf.get("gateway").asText()).contains("gateway").contains("127.0.92.1");
        JsonNode l1 = linkBetween(gw, h1);
        assertThat(l1.get("origin").asText()).isEqualTo("INFERRED");
        assertThat(l1.get("state").asText()).isEqualTo("SUGGESTED");
        assertThat(l1.get("evidence").asText()).contains("gateway");
        assertThat(linkBetween(gw, h2)).isNotNull();
        assertThat(linkBetween(gw, outside)).as("outra sub-rede: não se presume nada").isNull();

        // descartar uma sugestão a rejeita; confirmar outra a mantém
        mvc.perform(delete("/api/links/" + l1.get("id").asLong()).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/links/" + linkBetween(gw, h2).get("id").asLong() + "/confirm").session(admin).with(csrf()))
                .andExpect(status().isOk());
        JsonNode again = refreshAll().get("inference");
        assertThat(again.get("gatewayLinks").asInt()).isZero();
        assertThat(linkBetween(gw, h1)).as("rejeitado não volta").isNull();
        assertThat(linkBetween(gw, h2).get("state").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void unregisteredGatewayIsReportedInsteadOfGuessed() throws Exception {
        host("h1", "127.0.94.10", "WORKSTATION", 10);
        doReturn(Optional.of("127.0.94.1")).when(gateway).defaultGateway();
        JsonNode inf = refreshAll().get("inference");
        assertThat(inf.get("gatewayLinks").asInt()).isZero();
        assertThat(inf.get("notes").toString()).contains("127.0.94.1").contains("não está cadastrado");
        assertThat(links()).isEmpty();
    }

    @Test
    void betterEvidencePromotesAGatewayGuess() throws Exception {
        long gw = host("gateway", "127.0.92.1", "ROUTER", 1);
        long pc = host("pc", "127.0.92.10", "WORKSTATION", 10);
        doReturn(Optional.of("127.0.92.1")).when(gateway).defaultGateway();
        refreshAll();
        assertThat(linkBetween(gw, pc).get("origin").asText()).isEqualTo("INFERRED");

        TestMib mib = switchMib("sw", 2, 2).fdb(mac(10), 1, 3);
        long sw = snmpDevice("sw", "127.0.92.2", "SWITCH", mib, 2);
        refreshAll();
        assertThat(linkBetween(sw, pc).get("origin").asText()).isEqualTo("FDB");
        assertThat(linkBetween(gw, pc)).as("a suposição antiga sai quando há dado melhor").isNull();
    }

    @Test
    void qBridgeOnlySwitchIsReadToo() throws Exception {
        long pc = host("pc", "127.0.91.10", "WORKSTATION", 10);
        TestMib mib = new TestMib().system("Switch", "1.3.6.1.4.1.9.1.716", "q-sw", 1000)
                .iface(1, "Gi0/1", mac(1), 6, 1, 1, 1000).iface(2, "Gi0/2", null, 6, 1, 1, 1000)
                .bridgePort(1, 1).bridgePort(2, 2)
                .qFdb(1, mac(10), 2);
        long sw = snmpDevice("q-sw", "127.0.91.2", "SWITCH", mib, 1);
        refreshAll();
        JsonNode l = linkBetween(sw, pc);
        assertThat(l).isNotNull();
        assertThat(l.path("sourceInterface").asText() + l.path("targetInterface").asText()).contains("Gi0/2");
    }

    @Test
    void routerArpFillsMissingMacsAndReportsUnknownHosts() throws Exception {
        long pc = createDevice("pc-sem-mac", "127.0.91.10", "WORKSTATION").get("id").asLong();
        TestMib mib = new TestMib().system("Router", "1.3.6.1.4.1.14988.1", "rtr", 1000)
                .iface(1, "lan", mac(1), 6, 1, 1, 1000)
                .arp(1, "127.0.91.10", "e4:8d:8c:11:22:33")               // MikroTik
                .arp(1, "127.0.91.77", "00:0c:29:44:55:66");              // host que ninguém cadastrou
        snmpDevice("rtr", "127.0.91.2", "ROUTER", mib, 1);

        JsonNode inf = refreshAll().get("inference");
        assertThat(inf.get("macsFilled").asInt()).isEqualTo(1);
        JsonNode d = getJson("/api/devices/" + pc);
        assertThat(d.get("macAddress").asText()).isEqualTo("e4:8d:8c:11:22:33");
        assertThat(d.get("macVendor").asText()).isEqualTo("Routerboard.com");
        JsonNode unknown = inf.get("newHosts").get(0);
        assertThat(unknown.get("ip").asText()).isEqualTo("127.0.91.77");
        assertThat(unknown.get("vendor").asText()).isEqualTo("VMware, Inc.");
    }

    @Test
    void cdpAndMndpNeighborsBecomeLinksWithTheirOrigin() throws Exception {
        TestMib core = switchMib("core", 1, 3)
                .cdpNeighbor(2, 1, "edge-cdp", "descr-Gi0/1", "127.0.95.3")
                .mndpNeighbor(1, "127.0.95.4", mac(4), "mikrotik-edge", 3);
        TestMib edgeCdp = switchMib("edge-cdp", 3, 2);
        TestMib edgeMndp = new TestMib().system("RouterOS", "1.3.6.1.4.1.14988.1", "mikrotik-edge", 1000)
                .iface(1, "ether1", mac(4), 6, 1, 1, 1000);
        long c = snmpDevice("core", "127.0.95.2", "SWITCH", core, 1);
        long e1 = snmpDevice("edge-cdp", "127.0.95.3", "SWITCH", edgeCdp, 3);
        long e2 = snmpDevice("mikrotik-edge", "127.0.95.4", "ROUTER", edgeMndp, 4);

        refreshAll();
        JsonNode cdp = linkBetween(c, e1);
        assertThat(cdp.get("origin").asText()).isEqualTo("CDP");
        assertThat(cdp.get("evidence").asText()).startsWith("CDP:");
        assertThat(List.of(cdp.path("sourceInterface").asText(), cdp.path("targetInterface").asText()))
                .containsExactlyInAnyOrder("Gi0/2", "Gi0/1");
        JsonNode mndp = linkBetween(c, e2);
        assertThat(mndp.get("origin").asText()).isEqualTo("MNDP");
        assertThat(mndp.get("sourceInterface").asText() + mndp.path("targetInterface").asText()).contains("Gi0/3");
    }

    @Test
    void unreachableDeviceBlocksStaleCleanupInsteadOfWipingSuggestions() throws Exception {
        long pcB = host("pcB", "127.0.91.11", "WORKSTATION", 11);
        long pcC = host("pcC", "127.0.91.12", "WORKSTATION", 12);
        TestMib mib = switchMib("sw", 1, 2).fdb(mac(11), 2, 3).fdb(mac(12), 2, 3);
        snmpDevice("sw", "127.0.91.2", "SWITCH", mib, 1);
        refreshAll();
        assertThat(virtualNodes()).hasSize(1);

        agents.get(0).close();                                       // o switch deixa de responder
        agents.clear();
        JsonNode r = refreshAll();
        assertThat(r.get("errors")).isNotEmpty();
        assertThat(r.get("inference").get("staleRemoved").asInt()).isZero();
        assertThat(virtualNodes()).as("sem leitura, nada é apagado").hasSize(1);
        assertThat(r.get("inference").get("notes").toString()).contains("falhas de leitura");
        assertThat(pcB).isNotEqualTo(pcC);
    }

    @Test
    void logicalNodesAndExternalDevicesKeepTheirManualLinksThroughTopologyRounds() throws Exception {
        long gw = host("gateway", "127.0.92.1", "ROUTER", 1);
        long local = host("local", "127.0.92.10", "WORKSTATION", 10);
        doReturn(Optional.of("127.0.92.1")).when(gateway).defaultGateway();
        long internet = read(postJson("/api/devices", Map.of("name", "Internet", "logical", true))
                .andExpect(status().isCreated())).get("id").asLong();
        long dns = createDevice("DNS externo", "8.8.4.4", "SERVER").get("id").asLong();
        postJson("/api/links", Map.of("sourceDeviceId", gw, "targetDeviceId", internet, "kind", "INTERNET"))
                .andExpect(status().isCreated());
        postJson("/api/links", Map.of("sourceDeviceId", internet, "targetDeviceId", dns, "kind", "INTERNET"))
                .andExpect(status().isCreated());

        for (int round = 0; round < 2; round++) {
            JsonNode inf = refreshAll().get("inference");
            assertThat(inf.get("gatewayLinks").asInt()).as("só o dispositivo local é suposto atrás do gateway")
                    .isEqualTo(round == 0 ? 1 : 0);
            assertThat(inf.get("virtualNodes").asInt()).isZero();
        }
        assertThat(linkBetween(gw, internet).get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(linkBetween(gw, internet).get("kind").asText()).isEqualTo("INTERNET");
        assertThat(linkBetween(internet, dns)).isNotNull();
        assertThat(linkBetween(gw, dns)).as("o DNS externo está fora da rede do gateway: nada é presumido").isNull();
        assertThat(linkBetween(gw, local)).isNotNull();
        assertThat(virtualNodes()).as("o nó lógico segue existindo").hasSize(1);
        assertThat(virtualNodes().get(0).get("name").asText()).isEqualTo("Internet");
    }
}
