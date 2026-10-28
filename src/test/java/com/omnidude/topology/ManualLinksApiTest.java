package com.omnidude.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Enlaces manuais e nós lógicos: incorporar ao mapa o que está fora da rede (Internet, VPN, DNS externo). */
class ManualLinksApiTest extends ApiTestSupport {

    private JsonNode logicalNode(String name) throws Exception {
        return read(postJson("/api/devices", Map.of("name", name, "logical", true, "deviceType", "OTHER"))
                .andExpect(status().isCreated()));
    }

    private Map<String, Object> link(long a, long b, String kind, String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("sourceDeviceId", a);
        m.put("targetDeviceId", b);
        m.put("kind", kind);
        m.put("label", label);
        return m;
    }

    private JsonNode linkOf(long id) throws Exception {
        for (JsonNode l : getJson("/api/links")) {
            if (l.get("id").asLong() == id) {
                return l;
            }
        }
        return null;
    }

    @Test
    void logicalNodeHasNoIpChecksOrStatusAndStaysOutOfTheCounters() throws Exception {
        JsonNode before = getJson("/api/summary").get("devices");
        JsonNode node = logicalNode("Internet");
        assertThat(node.get("virtualNode").asBoolean()).isTrue();
        assertThat(node.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(node.get("checks")).isEmpty();
        assertThat(node.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(getJson("/api/summary").get("devices").toString()).as("não conta como dispositivo monitorado")
                .isEqualTo(before.toString());

        long id = node.get("id").asLong();
        JsonNode renamed = read(putJson("/api/devices/" + id, Map.of("name", "Internet (operadora)", "deviceType", "ROUTER",
                "primaryIp", "10.1.1.1", "snmpProfileId", 1)).andExpect(status().isOk()));
        assertThat(renamed.get("name").asText()).isEqualTo("Internet (operadora)");
        assertThat(renamed.get("deviceType").asText()).isEqualTo("ROUTER");
        assertThat(jdbc.queryForObject("SELECT primary_ip FROM devices WHERE id = ?", String.class, id))
                .as("o IP de um nó lógico não muda").startsWith("logical:");

        // dois nós lógicos com o mesmo nome convivem (a chave interna é única, o nome não precisa ser)
        logicalNode("Internet");
    }

    @Test
    void aRegularDeviceStillNeedsAnIp() throws Exception {
        postJson("/api/devices", Map.of("name", "sem-ip", "deviceType", "OTHER")).andExpect(status().isBadRequest());
        postJson("/api/devices", Map.of("name", "ip-ruim", "primaryIp", "nao-e-ip")).andExpect(status().isBadRequest());
        postJson("/api/devices", Map.of("name", "", "logical", true)).andExpect(status().isBadRequest());
    }

    @Test
    void manualLinkWithKindToALogicalNode() throws Exception {
        long gw = createDevice("gw-manual", "127.0.81.1", "ROUTER").get("id").asLong();
        long vpn = logicalNode("VPN matriz").get("id").asLong();

        JsonNode created = read(postJson("/api/links", link(gw, vpn, "VPN", "IPsec"))
                .andExpect(status().isCreated()));
        assertThat(created.get("kind").asText()).isEqualTo("VPN");
        assertThat(created.get("label").asText()).isEqualTo("IPsec");
        assertThat(created.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(created.get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(created.has("traffic")).as("sem interface, sem tráfego").isFalse();

        long id = created.get("id").asLong();
        putJson("/api/links/" + id, link(gw, vpn, "INTERNET", "WAN")).andExpect(status().isOk());
        assertThat(linkOf(id).get("kind").asText()).isEqualTo("INTERNET");
        putJson("/api/links/" + id, link(gw, vpn, null, null)).andExpect(status().isOk());
        assertThat(linkOf(id).has("kind")).as("tipo removido").isFalse();
    }

    @Test
    void linkKindIsValidated() throws Exception {
        long a = createDevice("kind-a", "127.0.81.2", "SWITCH").get("id").asLong();
        long b = createDevice("kind-b", "127.0.81.3", "SWITCH").get("id").asLong();
        postJson("/api/links", link(a, b, "TELEPATIA", null)).andExpect(status().isBadRequest());
        postJson("/api/links", link(a, b, "WIFI", null)).andExpect(status().isCreated());
        postJson("/api/links", link(b, a, "CABLE", null)).andExpect(status().isConflict());
    }

    @Test
    void externalDevicesWithPublicIpsAreRegularMonitoredDevices() throws Exception {
        long router = createDevice("roteador-borda", "127.0.81.4", "ROUTER").get("id").asLong();
        long dns = createDevice("DNS externo", "8.8.8.8", "SERVER").get("id").asLong();
        postJson("/api/devices/" + dns + "/checks", Map.of("type", "DNS", "params", Map.of("name", "example.com")))
                .andExpect(status().isCreated());
        JsonNode l = read(postJson("/api/links", link(router, dns, "INTERNET", "resolvedor")).andExpect(status().isCreated()));
        assertThat(l.get("kind").asText()).isEqualTo("INTERNET");
        assertThat(getJson("/api/devices/" + dns).get("checks")).hasSize(2);
    }

    @Test
    void deletingALogicalNodeRemovesItsLinksAndViewersCannotCreateAny() throws Exception {
        long gw = createDevice("gw-del", "127.0.81.5", "ROUTER").get("id").asLong();
        long node = logicalNode("Nuvem").get("id").asLong();
        long linkId = read(postJson("/api/links", link(gw, node, "OTHER", null)).andExpect(status().isCreated())).get("id").asLong();

        mvc.perform(post("/api/devices").session(viewer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"logical\":true}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/devices/" + node).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(linkOf(linkId)).isNull();
    }

    @Test
    void logicalNodeAppearsOnTheMapForManualLinking() throws Exception {
        long node = logicalNode("Nó no mapa").get("id").asLong();
        JsonNode maps = getJson("/api/maps");
        JsonNode map = getJson("/api/maps/" + maps.get(0).get("id").asLong());
        boolean found = false;
        for (JsonNode n : map.get("nodes")) {
            if (n.get("deviceId").asLong() == node) {
                found = true;
                assertThat(n.get("virtualNode").asBoolean()).isTrue();
                assertThat(n.get("deduced").asBoolean()).as("lógico, não deduzido").isFalse();
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void cloudAndNetworkAreValidDeviceTypes() throws Exception {
        JsonNode cloud = read(postJson("/api/devices", Map.of("name", "Nuvem AWS", "logical", true)).andExpect(status().isCreated()));
        assertThat(cloud.get("deviceType").asText()).as("nó lógico sem tipo vira nuvem").isEqualTo("CLOUD");
        JsonNode lan = read(postJson("/api/devices", Map.of("name", "VLAN 10", "logical", true, "deviceType", "NETWORK"))
                .andExpect(status().isCreated()));
        assertThat(lan.get("deviceType").asText()).isEqualTo("NETWORK");
        JsonNode withIp = read(postJson("/api/devices", Map.of("name", "VM na nuvem", "primaryIp", "127.0.81.9",
                "deviceType", "CLOUD")).andExpect(status().isCreated()));
        assertThat(withIp.get("deviceType").asText()).isEqualTo("CLOUD");
        putJson("/api/devices/" + withIp.get("id").asLong(), Map.of("name", "VM na nuvem", "primaryIp", "127.0.81.9",
                "deviceType", "NETWORK")).andExpect(status().isOk());
        // valor de enum desconhecido e JSON malformado: 400 (e não 200 vazio, como o tratador de IOException fazia)
        postJson("/api/devices", Map.of("name", "x", "primaryIp", "127.0.81.10", "deviceType", "NUVEM"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/devices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{nome:"))
                .andExpect(status().isBadRequest());
    }
}
