package com.omnidude.snmp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.monitoring.probe.SnmpCredentials;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnmpProfileV3ApiTest extends ApiTestSupport {

    private static Map<String, Object> v3(String name, String level) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("version", "3");
        m.put("username", "monitor");
        m.put("securityLevel", level);
        m.put("authProtocol", "SHA256");
        m.put("authPassword", "auth-pass-123");
        m.put("privProtocol", "AES128");
        m.put("privPassword", "priv-pass-123");
        m.put("retries", 0);
        m.put("timeoutMs", 3000);
        return m;
    }

    @Test
    void createsAV3ProfileWithoutEverReturningTheSecrets() throws Exception {
        String raw = postJson("/api/snmp-profiles", v3("v3-priv", "AUTH_PRIV")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("auth-pass-123").doesNotContain("priv-pass-123");
        JsonNode p = json.readTree(raw);
        assertThat(p.get("version").asText()).isEqualTo("3");
        assertThat(p.get("username").asText()).isEqualTo("monitor");
        assertThat(p.get("securityLevel").asText()).isEqualTo("AUTH_PRIV");
        assertThat(p.get("authProtocol").asText()).isEqualTo("SHA256");
        assertThat(p.get("privProtocol").asText()).isEqualTo("AES128");
        assertThat(p.get("hasAuthPassword").asBoolean()).isTrue();
        assertThat(p.get("hasPrivPassword").asBoolean()).isTrue();
        assertThat(p.get("hasCommunity").asBoolean()).isFalse();

        String stored = jdbc.queryForObject("SELECT v3_auth_pass_enc || v3_priv_pass_enc FROM snmp_profiles WHERE id = ?",
                String.class, p.get("id").asLong());
        assertThat(stored).as("senhas cifradas no banco").doesNotContain("auth-pass-123").doesNotContain("priv-pass-123");
        assertThat(getJson("/api/snmp-profiles").toString()).doesNotContain("auth-pass-123");
    }

    @Test
    void validatesTheV3Fields() throws Exception {
        Map<String, Object> noUser = v3("v3-sem-usuario", "AUTH_PRIV");
        noUser.remove("username");
        postJson("/api/snmp-profiles", noUser).andExpect(status().isBadRequest());

        Map<String, Object> noLevel = v3("v3-sem-nivel", "AUTH_PRIV");
        noLevel.remove("securityLevel");
        postJson("/api/snmp-profiles", noLevel).andExpect(status().isBadRequest());

        Map<String, Object> shortAuth = v3("v3-curta", "AUTH_NO_PRIV");
        shortAuth.put("authPassword", "curta");
        postJson("/api/snmp-profiles", shortAuth).andExpect(status().isBadRequest());

        Map<String, Object> shortPriv = v3("v3-priv-curta", "AUTH_PRIV");
        shortPriv.put("privPassword", "1234567");
        postJson("/api/snmp-profiles", shortPriv).andExpect(status().isBadRequest());

        Map<String, Object> noAuthProto = v3("v3-sem-proto", "AUTH_NO_PRIV");
        noAuthProto.remove("authProtocol");
        postJson("/api/snmp-profiles", noAuthProto).andExpect(status().isBadRequest());

        Map<String, Object> badProto = v3("v3-proto-ruim", "AUTH_PRIV");
        badProto.put("authProtocol", "MD5");
        postJson("/api/snmp-profiles", badProto).andExpect(status().isBadRequest());

        postJson("/api/snmp-profiles", v3("v3-nivel-ruim", "TUDO")).andExpect(status().isBadRequest());
    }

    @Test
    void noAuthProfileNeedsNoPassphrasesAndDropsAnyThatWereSent() throws Exception {
        JsonNode p = read(postJson("/api/snmp-profiles", v3("v3-aberto", "NO_AUTH_NO_PRIV")).andExpect(status().isCreated()));
        assertThat(p.get("hasAuthPassword").asBoolean()).isFalse();
        assertThat(p.get("hasPrivPassword").asBoolean()).isFalse();
        assertThat(p.has("authProtocol")).isFalse();
    }

    @Test
    void editingKeepsStoredSecretsWhenBlankAndSwitchingVersionClearsThem() throws Exception {
        long id = read(postJson("/api/snmp-profiles", v3("v3-edit", "AUTH_PRIV")).andExpect(status().isCreated()))
                .get("id").asLong();
        String before = jdbc.queryForObject("SELECT v3_auth_pass_enc FROM snmp_profiles WHERE id = ?", String.class, id);

        Map<String, Object> edit = v3("v3-edit", "AUTH_PRIV");
        edit.put("authPassword", "");
        edit.put("privPassword", null);
        edit.put("privProtocol", "AES256");
        putJson("/api/snmp-profiles/" + id, edit).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT v3_auth_pass_enc FROM snmp_profiles WHERE id = ?", String.class, id))
                .as("senha em branco mantém a atual").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT v3_priv_protocol FROM snmp_profiles WHERE id = ?", String.class, id))
                .isEqualTo("AES256");

        putJson("/api/snmp-profiles/" + id, Map.of("name", "v3-edit", "version", "2c", "community", "public-x"))
                .andExpect(status().isOk());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM snmp_profiles WHERE id = ?", id);
        assertThat(row.get("v3_username")).isNull();
        assertThat(row.get("v3_auth_pass_enc")).isNull();
        assertThat(row.get("v3_priv_pass_enc")).isNull();
        assertThat(row.get("community_enc")).isNotNull();

        putJson("/api/snmp-profiles/" + id, v3("v3-edit", "AUTH_PRIV")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT community_enc FROM snmp_profiles WHERE id = ?", String.class, id))
                .as("voltar para v3 limpa a community").isNull();
    }

    @Test
    void v3ProfileWorksEndToEndThroughTheInventoryApi() throws Exception {
        TestMib mib = new TestMib().system("Switch v3", "1.3.6.1.4.1.9.1.716", "sw-v3", 1000)
                .iface(1, "Gi0/1", "aa:bb:cc:09:00:01", 6, 1, 1, 1000);
        SnmpCredentials.V3 creds = new SnmpCredentials.V3("monitor", "AUTH_PRIV", "SHA256", "auth-pass-123", "AES128",
                "priv-pass-123");
        try (FakeSnmpAgent agent = FakeSnmpAgent.v3(SnmpClient.usmUser(creds), mib.build(), "127.0.73.2")) {
            Map<String, Object> body = v3("v3-e2e", "AUTH_PRIV");
            body.put("port", agent.port());
            long profile = read(postJson("/api/snmp-profiles", body).andExpect(status().isCreated())).get("id").asLong();
            long dev = createDevice("v3-dev", "127.0.73.2", "OTHER").get("id").asLong();
            putJson("/api/devices/" + dev, Map.of("name", "v3-dev", "primaryIp", "127.0.73.2", "snmpProfileId", profile))
                    .andExpect(status().isOk());

            JsonNode r = read(mvc.perform(post("/api/devices/" + dev + "/refresh").session(admin).with(csrf()))
                    .andExpect(status().isOk()));
            assertThat(r.get("sysName").asText()).isEqualTo("sw-v3");
            assertThat(r.get("interfaces").asInt()).isEqualTo(1);

            // a sonda de alcançabilidade SNMP também usa v3
            JsonNode check = read(postJson("/api/devices/" + dev + "/checks", Map.of("type", "SNMP"))
                    .andExpect(status().isCreated()));
            assertThat(check.get("id").asLong()).isPositive();
        }
    }
}
