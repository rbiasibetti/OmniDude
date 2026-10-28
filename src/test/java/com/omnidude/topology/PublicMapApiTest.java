package com.omnidude.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Mapa publicado: acesso sem login, somente leitura, com o mínimo de informação. */
class PublicMapApiTest extends ApiTestSupport {

    private static final AtomicInteger CLIENT = new AtomicInteger(1);

    private long mapId;
    private String client;

    @BeforeEach
    void freshMap() throws Exception {
        client = "198.51.100." + CLIENT.incrementAndGet();          // cada teste tem a sua origem (limite de taxa)
        mapId = read(postJson("/api/maps", Map.of("name", "Publico-" + System.nanoTime())).andExpect(status().isCreated()))
                .get("id").asLong();
    }

    private RequestPostProcessor from() {
        return r -> {
            r.setRemoteAddr(client);
            return r;
        };
    }

    private JsonNode publication(Object body) throws Exception {
        return read(putJson("/api/maps/" + mapId + "/publication", body).andExpect(status().isOk()));
    }

    private String token(JsonNode pub) {
        return pub.get("path").asText().substring("/public/".length());
    }

    private MvcResult anonymousData(String token) throws Exception {
        return mvc.perform(get("/api/public/maps/" + token).with(from())).andReturn();
    }

    private JsonNode publicJson(String token) throws Exception {
        MvcResult r = anonymousData(token);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long deviceOnMap(String name, String ip, String type, String status, String mac) throws Exception {
        long id = createDevice(name, ip, type).get("id").asLong();
        jdbc.update("UPDATE devices SET status = ?, mac_address = ?, notes = ? WHERE id = ?", status, mac,
                "nota-secreta-" + name, id);
        postJson("/api/maps/" + mapId + "/nodes", Map.of("deviceId", id)).andExpect(status().isNoContent());
        return id;
    }

    // ------------------------------------------------------------------------------------------------

    @Test
    void publishingCreatesAnUnguessableLinkAndOnlyAdminsCanManageIt() throws Exception {
        assertThat(getJson("/api/maps/" + mapId + "/publication").get("published").asBoolean()).isFalse();
        JsonNode pub = publication(Map.of("published", true));
        assertThat(pub.get("published").asBoolean()).isTrue();
        assertThat(token(pub)).matches("[A-Za-z0-9_-]{43}");
        assertThat(getJson("/api/maps/" + mapId + "/publication").get("path").asText()).isEqualTo(pub.get("path").asText());
        assertThat(publication(Map.of("showNames", false)).get("path").asText()).as("mudar opção não troca o link")
                .isEqualTo(pub.get("path").asText());
        assertThat(getJson("/api/maps").toString()).contains("\"published\":true");

        mvc.perform(get("/api/maps/" + mapId + "/publication").session(viewer())).andExpect(status().isForbidden());
        mvc.perform(put("/api/maps/" + mapId + "/publication").session(viewer()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"published\":false}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/maps/" + mapId + "/publication")).andExpect(status().isUnauthorized());
        putJson("/api/maps/999999/publication", Map.of("published", true)).andExpect(status().isNotFound());
    }

    @Test
    void anonymousVisitorSeesOnlyTheReducedMap() throws Exception {
        long sw = deviceOnMap("sw-secreto", "10.77.0.1", "SWITCH", "UP", "aa:bb:cc:11:22:33");
        long srv = deviceOnMap("srv-secreto", "10.77.0.2", "SERVER", "DOWN", "aa:bb:cc:44:55:66");
        long pc = deviceOnMap("pc-secreto", "10.77.0.3", "WORKSTATION", "UP", null);
        postJson("/api/links", Map.of("sourceDeviceId", sw, "targetDeviceId", srv, "label", "rotulo-secreto", "kind", "VPN"))
                .andExpect(status().isCreated());
        jdbc.update("INSERT INTO links(source_device_id, target_device_id, origin, state, evidence, created_at) "
                + "VALUES (?, ?, 'FDB', 'SUGGESTED', 'evidencia-secreta', 1)", Math.min(sw, pc), Math.max(sw, pc));
        String tk = token(publication(Map.of("published", true)));

        MvcResult res = anonymousData(tk);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode map = json.readTree(body);

        assertThat(map.get("nodes")).hasSize(3);
        assertThat(map.get("links")).as("só o enlace confirmado; a sugestão fica de fora").hasSize(1);
        assertThat(map.get("links").get(0).get("kind").asText()).isEqualTo("VPN");
        assertThat(map.get("links").get(0).get("down").asBoolean()).as("uma ponta DOWN").isTrue();
        assertThat(map.get("nodes").findValuesAsText("name")).containsExactlyInAnyOrder("sw-secreto", "srv-secreto", "pc-secreto");
        assertThat(map.get("nodes").findValuesAsText("status")).containsExactlyInAnyOrder("UP", "DOWN", "UP");

        // nada do que não está na lista fixa pode aparecer, nem como valor nem como nome de campo
        for (String secret : new String[] {"10.77.0.", "aa:bb:cc", "nota-secreta", "rotulo-secreto", "evidencia-secreta",
                "primaryIp", "macAddress", "deviceId", "evidence", "label", "notes", "snmp", "interface"}) {
            assertThat(body).as("vazou: " + secret).doesNotContain(secret);
        }
        assertThat(map.get("nodes").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "name", "type", "status", "x", "y", "logical");
        assertThat(map.get("nodes").get(0).get("id").asText()).as("id público, não o interno").matches("n\\d+");
    }

    @Test
    void optionsHideNamesAndStatusOnTheServer() throws Exception {
        long a = deviceOnMap("nome-a", "10.77.1.1", "SWITCH", "DOWN", null);
        long b = deviceOnMap("nome-b", "10.77.1.2", "SERVER", "UP", null);
        postJson("/api/links", Map.of("sourceDeviceId", a, "targetDeviceId", b)).andExpect(status().isCreated());
        String tk = token(publication(Map.of("published", true, "showNames", false, "showStatus", false)));

        MvcResult res = anonymousData(tk);
        String body = res.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode map = json.readTree(body);
        assertThat(body).doesNotContain("nome-a").doesNotContain("nome-b");
        assertThat(map.get("showNames").asBoolean()).isFalse();
        for (JsonNode n : map.get("nodes")) {
            assertThat(n.has("name")).as("sem nome").isFalse();
            assertThat(n.has("status")).as("sem estado").isFalse();
            assertThat(n.get("type").asText()).isIn("SWITCH", "SERVER");
        }
        assertThat(map.get("links").get(0).get("down").asBoolean()).as("sem estado, o enlace não acusa falha").isFalse();
    }

    @Test
    void logicalAndDeducedNodesDoNotLeakInternalNames() throws Exception {
        long logical = read(postJson("/api/devices", Map.of("name", "Internet", "logical", true)).andExpect(status().isCreated()))
                .get("id").asLong();
        postJson("/api/maps/" + mapId + "/nodes", Map.of("deviceId", logical)).andExpect(status().isNoContent());
        jdbc.update("INSERT INTO devices(name, primary_ip, device_type, origin, virtual, created_at, updated_at) "
                + "VALUES ('core-sw-privado Gi0/2', 'unmanaged:9:2', 'SWITCH', 'DISCOVERY', 1, 1, 1)");
        long deduced = jdbc.queryForObject("SELECT id FROM devices WHERE primary_ip = 'unmanaged:9:2'", Long.class);
        postJson("/api/maps/" + mapId + "/nodes", Map.of("deviceId", deduced)).andExpect(status().isNoContent());

        JsonNode map = publicJson(token(publication(Map.of("published", true))));
        assertThat(map.toString()).doesNotContain("core-sw-privado").doesNotContain("logical:").doesNotContain("unmanaged:");
        assertThat(map.get("nodes").findValuesAsText("name")).containsExactlyInAnyOrder("Internet", "Switch/hub não gerenciável");
        for (JsonNode n : map.get("nodes")) {
            assertThat(n.get("logical").asBoolean()).isTrue();
            assertThat(n.has("status")).as("nó sem monitoramento não tem estado").isFalse();
        }
    }

    @Test
    void unpublishingOrRegeneratingKillsTheOldLink() throws Exception {
        deviceOnMap("x", "10.77.2.1", "SERVER", "UP", null);
        String first = token(publication(Map.of("published", true)));
        assertThat(anonymousData(first).getResponse().getStatus()).isEqualTo(200);

        String second = token(publication(Map.of("regenerate", true)));
        assertThat(second).isNotEqualTo(first);
        assertThat(anonymousData(first).getResponse().getStatus()).as("o link antigo morre").isEqualTo(404);
        assertThat(anonymousData(second).getResponse().getStatus()).isEqualTo(200);

        JsonNode off = publication(Map.of("published", false));
        assertThat(off.get("published").asBoolean()).isFalse();
        assertThat(off.has("path")).isFalse();
        assertThat(anonymousData(second).getResponse().getStatus()).isEqualTo(404);

        String third = token(publication(Map.of("published", true)));
        assertThat(third).isNotIn(first, second);
        assertThat(anonymousData(second).getResponse().getStatus()).as("o link despublicado não volta").isEqualTo(404);
        assertThat(anonymousData(third).getResponse().getStatus()).isEqualTo(200);

        // regenerar um mapa que não está publicado não o publica
        publication(Map.of("published", false));
        assertThat(publication(Map.of("regenerate", true)).get("published").asBoolean()).isFalse();
    }

    @Test
    void unknownOrMalformedTokensAreAllTheSame404() throws Exception {
        for (String bad : new String[] {"naoexiste-naoexiste-naoexiste-naoexiste-123", "curto", "a".repeat(100),
                "x".repeat(43)}) {
            assertThat(anonymousData(bad).getResponse().getStatus()).as(bad).isEqualTo(404);
        }
        assertThat(anonymousData("..%2F..%2Fetc").getResponse().getStatus()).as("o firewall recusa barra codificada")
                .isBetween(400, 404);
        MvcResult a = anonymousData("naoexiste-naoexiste-naoexiste-naoexiste-123");
        MvcResult b = anonymousData("curto");
        assertThat(a.getResponse().getContentAsString()).as("o 404 não diferencia token inexistente de malformado")
                .isEqualTo(b.getResponse().getContentAsString());
    }

    @Test
    void publicResponsesCarryProtectiveHeadersAndNoCookies() throws Exception {
        String tk = token(publication(Map.of("published", true)));
        for (String url : new String[] {"/public/" + tk, "/api/public/maps/" + tk}) {
            MvcResult r = mvc.perform(get(url).with(from())).andExpect(status().isOk()).andReturn();
            assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
            assertThat(r.getResponse().getHeader("X-Robots-Tag")).contains("noindex");
            assertThat(r.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(r.getResponse().getHeaders("Set-Cookie")).as("sem cookie (nem sessão, nem XSRF)").isEmpty();
        }
        MvcResult page = mvc.perform(get("/public/" + tk).with(from())).andReturn();
        assertThat(page.getResponse().getContentType()).startsWith("text/html");
        assertThat(page.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'")
                .contains("script-src 'self'").doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
        String html = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("public.js").doesNotContain("app.js").doesNotContain("alpine");
        assertThat(mvc.perform(get("/public/short").with(from())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theRestOfTheApplicationStaysProtectedForAnonymousVisitors() throws Exception {
        String tk = token(publication(Map.of("published", true)));
        // só os recursos da página pública são abertos
        for (String ok : new String[] {"/js/common.js", "/js/public.js", "/vendor/cytoscape.min.js", "/css/public.css"}) {
            mvc.perform(get(ok).with(from())).andExpect(status().isOk());
        }
        // o aplicativo, a API de administração e o SSE não
        for (String api : new String[] {"/api/devices", "/api/maps", "/api/links", "/api/events", "/api/summary", "/api/stream",
                "/api/maps/" + mapId + "/publication"}) {
            mvc.perform(get(api).with(from())).andExpect(status().isUnauthorized());
        }
        for (String page : new String[] {"/", "/index.html", "/js/app.js", "/vendor/alpine.min.js", "/vendor/uplot.min.js"}) {
            mvc.perform(get(page).with(from())).andExpect(status().is3xxRedirection());
        }
        // a visão pública é só GET: qualquer escrita é recusada
        for (var req : new org.springframework.test.web.servlet.RequestBuilder[] {
                post("/api/public/maps/" + tk).with(csrf()), put("/api/public/maps/" + tk).with(csrf()),
                post("/public/" + tk).with(csrf())}) {
            int st = mvc.perform(req).andReturn().getResponse().getStatus();
            assertThat(st).as("escrita na visão pública: nunca 2xx").isBetween(300, 499);
        }
    }

    @Test
    void anAnonymousVisitorCannotPublishOrChangeAnything() throws Exception {
        mvc.perform(put("/api/maps/" + mapId + "/publication").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"published\":true}")).andExpect(status().isUnauthorized());
        assertThat(getJson("/api/maps/" + mapId + "/publication").get("published").asBoolean()).isFalse();
    }
}
