package com.omnidude.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class InventoryApiTest extends ApiTestSupport {

    private TestMib mib() {
        return new TestMib()
                .system("Cisco IOS Software, C2960 Switch", "1.3.6.1.4.1.9.1.716", "sw-inv", 360000)    // 3600 s de uptime
                .iface(1, "Gi0/1", "aa:bb:cc:00:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "aa:bb:cc:00:00:02", 6, 2, 1, 100)
                .iface(24, "Lo0", null, 24, 1, 1, 0);
    }

    private void withProfile(String ip, String type, String community, String name, ThrowingConsumer body) throws Exception {
        try (FakeSnmpAgent agent = new FakeSnmpAgent("inv-comm", ip, 0, mib().build())) {
            long profileId = createProfile(name, community, agent.port()).get("id").asLong();
            JsonNode device = createDevice("inv-" + ip, ip, type);
            long id = device.get("id").asLong();
            putJson("/api/devices/" + id, Map.of("name", "inv-" + ip, "primaryIp", ip, "deviceType", type,
                    "snmpProfileId", profileId)).andExpect(status().isOk());
            body.accept(id);
        }
    }

    private interface ThrowingConsumer {
        void accept(long deviceId) throws Exception;
    }

    private JsonNode refresh(long id) throws Exception {
        return read(mvc.perform(post("/api/devices/" + id + "/refresh").session(admin).with(csrf())).andExpect(status().isOk()));
    }

    @Test
    void refreshFillsSystemDataAndCreatesInterfaces() throws Exception {
        long before = Instant.now().getEpochSecond();
        withProfile("127.0.70.2", "OTHER", "inv-comm", "inv-p1", id -> {
            JsonNode r = refresh(id);
            assertThat(r.get("sysName").asText()).isEqualTo("sw-inv");
            assertThat(r.get("interfaces").asInt()).isEqualTo(3);
            assertThat(r.get("newInterfaces").asInt()).isEqualTo(3);
            assertThat(r.get("os").asText()).isEqualTo("Cisco IOS");
            assertThat(r.get("type").asText()).as("OTHER é substituído pelo fingerprint").isEqualTo("SWITCH");

            JsonNode d = getJson("/api/devices/" + id);
            assertThat(d.get("hostname").asText()).isEqualTo("sw-inv");
            assertThat(d.get("sysDescr").asText()).isEqualTo("Cisco IOS Software, C2960 Switch");
            assertThat(d.get("macAddress").asText()).as("1ª interface UP não-loopback").isEqualTo("aa:bb:cc:00:00:01");
            Long boot = jdbc.queryForObject("SELECT last_boot_at FROM devices WHERE id = ?", Long.class, id);
            assertThat(boot).isBetween(before - 3600 - 5, Instant.now().getEpochSecond() - 3600 + 5);

            JsonNode ifs = getJson("/api/devices/" + id + "/interfaces");
            assertThat(ifs).extracting(i -> i.get("name").asText()).containsExactly("Gi0/1", "Gi0/2", "Lo0");
            assertThat(ifs.get(0).get("speedBps").asLong()).isEqualTo(1_000_000_000L);
            assertThat(ifs.get(0).get("monitored").asBoolean()).isTrue();
            assertThat(ifs.get(1).get("monitored").asBoolean()).isFalse();
            assertThat(ifs.get(2).get("monitored").asBoolean()).isFalse();
            assertThat(ifs.get(2).has("speedBps")).as("loopback sem velocidade").isFalse();
        });
    }

    @Test
    void secondRefreshKeepsUserChoicesAndDoesNotDuplicate() throws Exception {
        withProfile("127.0.70.3", "ROUTER", "inv-comm", "inv-p2", id -> {
            refresh(id);
            JsonNode ifs = getJson("/api/devices/" + id + "/interfaces");
            long gi1 = ifs.get(0).get("id").asLong();
            long gi2 = ifs.get(1).get("id").asLong();
            putJson("/api/interfaces/" + gi1, Map.of("monitored", false)).andExpect(status().isOk());
            putJson("/api/interfaces/" + gi2, Map.of("monitored", true)).andExpect(status().isOk());

            JsonNode r = refresh(id);
            assertThat(r.get("newInterfaces").asInt()).isZero();
            assertThat(r.get("type").asText()).as("a escolha do usuário nunca é sobrescrita").isEqualTo("ROUTER");
            JsonNode again = getJson("/api/devices/" + id + "/interfaces");
            assertThat(again).hasSize(3);
            assertThat(again.get(0).get("monitored").asBoolean()).isFalse();
            assertThat(again.get(1).get("monitored").asBoolean()).isTrue();
            assertThat(getJson("/api/devices/" + id).get("deviceType").asText()).isEqualTo("ROUTER");
        });
    }

    @Test
    void refreshErrors() throws Exception {
        long noProfile = createDevice("sem-perfil", "127.0.70.9", "SWITCH").get("id").asLong();
        mvc.perform(post("/api/devices/" + noProfile + "/refresh").session(admin).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/devices/999999/refresh").session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/devices/999999/interfaces").session(admin)).andExpect(status().isNotFound());
        mvc.perform(put("/api/interfaces/999999").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"monitored\":true}")).andExpect(status().isNotFound());
        putJson("/api/interfaces/1", Map.of()).andExpect(status().isBadRequest());                  // 'monitored' é obrigatório

        // community errada no perfil: o agente não responde → 502, sem derrubar nada
        try (FakeSnmpAgent agent = new FakeSnmpAgent("a-certa", "127.0.70.8", 0, mib().build())) {
            long profileId = createProfile("inv-errada", "outra", agent.port()).get("id").asLong();
            long id = createDevice("agente-mudo", "127.0.70.8", "SWITCH").get("id").asLong();
            putJson("/api/devices/" + id, Map.of("name", "agente-mudo", "primaryIp", "127.0.70.8", "snmpProfileId", profileId))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/devices/" + id + "/refresh").session(admin).with(csrf())).andExpect(status().isBadGateway());
            mvc.perform(post("/api/devices/" + id + "/lldp").session(admin).with(csrf())).andExpect(status().isBadGateway());
            assertThat(getJson("/api/devices/" + id + "/interfaces")).isEmpty();
        }
    }

    @Test
    void viewerCanListInterfacesButNotRefreshOrToggle() throws Exception {
        long id = createDevice("viewer-if", "127.0.70.7", "SWITCH").get("id").asLong();
        var v = viewer();
        mvc.perform(get("/api/devices/" + id + "/interfaces").session(v)).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/refresh").session(v).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/devices/" + id + "/lldp").session(v).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/interfaces/1").session(v).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"monitored\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void refreshUsesTheMacVendorWhenSnmpAloneDoesNotIdentifyTheDevice() throws Exception {
        TestMib mib = new TestMib().system("Appliance X", "1.3.6.1.4.1.99999.1", "gw-oui", 1000)
                .iface(1, "ether1", "e4:8d:8c:00:00:01", 6, 1, 1, 1000);
        try (FakeSnmpAgent agent = new FakeSnmpAgent("inv-comm", "127.0.70.20", 0, mib.build())) {
            long profileId = createProfile("inv-oui", "inv-comm", agent.port()).get("id").asLong();
            long id = createDevice("inv-oui", "127.0.70.20", "OTHER").get("id").asLong();
            putJson("/api/devices/" + id, Map.of("name", "inv-oui", "primaryIp", "127.0.70.20", "deviceType", "OTHER",
                    "snmpProfileId", profileId)).andExpect(status().isOk());

            JsonNode r = refresh(id);
            assertThat(r.get("type").asText()).as("sysObjectID desconhecido: o fabricante do MAC decide").isEqualTo("ROUTER");
            assertThat(r.get("os").asText()).isEqualTo("RouterOS");
            JsonNode d = getJson("/api/devices/" + id);
            assertThat(d.get("macAddress").asText()).isEqualTo("e4:8d:8c:00:00:01");
            assertThat(d.get("macVendor").asText()).isEqualTo("Routerboard.com");
        }
    }
}
