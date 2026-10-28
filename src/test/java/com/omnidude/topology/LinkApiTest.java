package com.omnidude.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.topology.LinkService.Outcome;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class LinkApiTest extends ApiTestSupport {

    private static final AtomicInteger NEXT = new AtomicInteger(1);

    @Autowired LinkService linkService;

    // ------------------------------------------------------------------------------------ helpers

    /** Dois dispositivos, cada um com duas interfaces. a < b por id. */
    private record Pair(long a, long a1, long a2, long b, long b1, long b2) {
    }

    private Pair pair() throws Exception {
        int n = NEXT.getAndIncrement();
        long a = createDevice("L-A" + n, "192.0.2." + (100 + n * 2), "SWITCH").get("id").asLong();
        long b = createDevice("L-B" + n, "192.0.2." + (101 + n * 2), "SWITCH").get("id").asLong();
        return new Pair(a, iface(a, 1), iface(a, 2), b, iface(b, 1), iface(b, 2));
    }

    private long iface(long deviceId, int index) {
        jdbc.update("INSERT INTO interfaces(device_id, if_index, name) VALUES (?,?,?)", deviceId, index, "eth" + index);
        return jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id = ? AND if_index = ?", Long.class, deviceId, index);
    }

    private Map<String, Object> link(long a, Long ifA, long b, Long ifB, String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("sourceDeviceId", a);
        m.put("targetDeviceId", b);
        m.put("sourceInterfaceId", ifA);
        m.put("targetInterfaceId", ifB);
        m.put("label", label);
        return m;
    }

    private int rejections(long a, long b) {
        return jdbc.queryForObject("SELECT count(*) FROM link_rejections WHERE source_device_id = ? AND target_device_id = ?",
                Integer.class, Math.min(a, b), Math.max(a, b));
    }

    // ------------------------------------------------------------------------------------ CRUD manual

    @Test
    void manualLinkIsStoredInCanonicalOrderWithInterfaceNames() throws Exception {
        Pair p = pair();
        // enviado "ao contrário" (b → a): o servidor normaliza e leva cada interface junto do seu dispositivo
        JsonNode v = read(postJson("/api/links", link(p.b, p.b1, p.a, p.a1, "uplink")).andExpect(status().isCreated()));
        assertThat(v.get("sourceDeviceId").asLong()).isEqualTo(p.a);
        assertThat(v.get("targetDeviceId").asLong()).isEqualTo(p.b);
        assertThat(v.get("sourceInterfaceId").asLong()).isEqualTo(p.a1);
        assertThat(v.get("targetInterfaceId").asLong()).isEqualTo(p.b1);
        assertThat(v.get("sourceInterface").asText()).isEqualTo("eth1");
        assertThat(v.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(v.get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(v.get("label").asText()).isEqualTo("uplink");
    }

    @Test
    void validationRules() throws Exception {
        Pair p = pair();
        postJson("/api/links", link(p.a, null, p.a, null, null)).andExpect(status().isBadRequest());            // si mesmo
        postJson("/api/links", link(p.a, null, 999999, null, null)).andExpect(status().isNotFound());           // inexistente
        postJson("/api/links", link(p.a, p.b1, p.b, null, null)).andExpect(status().isBadRequest())            // iface alheia
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("não pertence")));
        postJson("/api/links", Map.of("targetDeviceId", p.b)).andExpect(status().isBadRequest());               // campo ausente

        postJson("/api/links", link(p.a, p.a1, p.b, p.b1, null)).andExpect(status().isCreated());
        postJson("/api/links", link(p.a, p.a1, p.b, p.b1, null)).andExpect(status().isConflict());              // idêntico
        postJson("/api/links", link(p.b, p.b1, p.a, p.a1, null)).andExpect(status().isConflict());              // invertido
        postJson("/api/links", link(p.a, null, p.b, null, null)).andExpect(status().isConflict());              // "qualquer porta" já coberto
        postJson("/api/links", link(p.a, p.a2, p.b, p.b2, null)).andExpect(status().isCreated());               // paralelo, outras portas
    }

    @Test
    void updateReassociatesInterfacesButNeverChangesTheDevices() throws Exception {
        Pair p = pair();
        long id = read(postJson("/api/links", link(p.a, p.a1, p.b, p.b1, "x")).andExpect(status().isCreated())).get("id").asLong();
        postJson("/api/links", link(p.a, p.a2, p.b, p.b2, null)).andExpect(status().isCreated());

        JsonNode u = read(putJson("/api/links/" + id, link(p.a, p.a1, p.b, null, "novo rótulo"))
                .andExpect(status().isOk()));
        assertThat(u.get("label").asText()).isEqualTo("novo rótulo");
        assertThat(u.has("targetInterfaceId")).isFalse();
        putJson("/api/links/" + id, link(p.a, p.a2, p.b, p.b2, null)).andExpect(status().isConflict());         // colide com o outro
        putJson("/api/links/" + id, link(p.a, null, 999999, null, null)).andExpect(status().isNotFound());
        long c = createDevice("L-C", "192.0.2.199", "SWITCH").get("id").asLong();
        putJson("/api/links/" + id, link(p.a, null, c, null, null)).andExpect(status().isBadRequest());         // outro dispositivo
        putJson("/api/links/999999", link(p.a, null, p.b, null, null)).andExpect(status().isNotFound());
    }

    @Test
    void deletingADeviceRemovesItsLinks() throws Exception {
        Pair p = pair();
        long id = read(postJson("/api/links", link(p.a, p.a1, p.b, p.b1, null)).andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(delete("/api/devices/" + p.b).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM links WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interfaces WHERE device_id = ?", Integer.class, p.b)).isZero();
    }

    // ------------------------------------------------------------------------------------ sugestões (LLDP)

    @Test
    void suggestionsAreIdempotentFillMissingInterfacesAndSurviveBothDirections() throws Exception {
        Pair p = pair();
        assertThat(linkService.suggest(p.a, null, p.b, null)).isEqualTo(Outcome.CREATED);
        assertThat(linkService.suggest(p.a, null, p.b, null)).isEqualTo(Outcome.EXISTS);
        assertThat(linkService.suggest(p.a, p.a1, p.b, p.b1)).as("preenche as interfaces que faltavam").isEqualTo(Outcome.UPDATED);
        assertThat(linkService.suggest(p.b, p.b1, p.a, p.a1)).as("visto do outro lado").isEqualTo(Outcome.EXISTS);
        assertThat(linkService.suggest(p.a, p.a1, p.b, null)).as("menos informação não é novidade").isEqualTo(Outcome.EXISTS);
        assertThat(linkService.suggest(p.a, p.a2, p.b, p.b2)).as("outras portas: outro enlace").isEqualTo(Outcome.CREATED);

        JsonNode all = getJson("/api/links");
        long ofPair = 0;
        for (JsonNode l : all) {
            if (l.get("sourceDeviceId").asLong() == p.a && l.get("targetDeviceId").asLong() == p.b) {
                ofPair++;
                assertThat(l.get("state").asText()).isEqualTo("SUGGESTED");
                assertThat(l.get("origin").asText()).isEqualTo("LLDP");
            }
        }
        assertThat(ofPair).isEqualTo(2);
    }

    @Test
    void discardingASuggestionRejectsThePairUntilTheUserCreatesALinkThemselves() throws Exception {
        Pair p = pair();
        linkService.suggest(p.a, p.a1, p.b, p.b1);
        long id = linkId(p.a, p.b);

        mvc.perform(delete("/api/links/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(rejections(p.a, p.b)).isEqualTo(1);
        assertThat(linkService.suggest(p.a, p.a1, p.b, p.b1)).isEqualTo(Outcome.REJECTED);
        assertThat(linkService.suggest(p.b, null, p.a, null)).as("qualquer direção").isEqualTo(Outcome.REJECTED);

        postJson("/api/links", link(p.a, p.a1, p.b, p.b1, null)).andExpect(status().isCreated());   // o usuário mudou de ideia
        assertThat(rejections(p.a, p.b)).isZero();
        assertThat(linkService.suggest(p.a, p.a1, p.b, p.b1)).isEqualTo(Outcome.EXISTS);
    }

    @Test
    void deletingAConfirmedLinkIsNotARejection() throws Exception {
        Pair p = pair();
        linkService.suggest(p.a, p.a1, p.b, p.b1);
        long id = linkId(p.a, p.b);
        postJson("/api/links/" + id + "/confirm", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        mvc.perform(delete("/api/links/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(rejections(p.a, p.b)).isZero();
        assertThat(linkService.suggest(p.a, p.a1, p.b, p.b1)).isEqualTo(Outcome.CREATED);
    }

    private long linkId(long a, long b) throws Exception {
        for (JsonNode l : getJson("/api/links")) {
            if (l.get("sourceDeviceId").asLong() == Math.min(a, b) && l.get("targetDeviceId").asLong() == Math.max(a, b)) {
                return l.get("id").asLong();
            }
        }
        throw new AssertionError("enlace não encontrado");
    }

    // ------------------------------------------------------------------------------------ mapa e autorização

    @Test
    void mapOnlyShowsLinksBetweenDevicesItContains() throws Exception {
        Pair p = pair();
        postJson("/api/links", link(p.a, p.a1, p.b, p.b1, null)).andExpect(status().isCreated());
        assertThat(getJson("/api/maps/1").get("links").toString()).contains("\"sourceDeviceId\":" + p.a);

        long mapId = read(postJson("/api/maps", Map.of("name", "só-um-" + p.a)).andExpect(status().isCreated())).get("id").asLong();
        postJson("/api/maps/" + mapId + "/nodes", Map.of("deviceId", p.a, "x", 1, "y", 1)).andExpect(status().isNoContent());
        assertThat(getJson("/api/maps/" + mapId).get("links")).as("só um extremo no mapa").isEmpty();
        postJson("/api/maps/" + mapId + "/nodes", Map.of("deviceId", p.b, "x", 2, "y", 2)).andExpect(status().isNoContent());
        assertThat(getJson("/api/maps/" + mapId).get("links")).hasSize(1);
    }

    @Test
    void viewerCanSeeLinksButNotChangeThem() throws Exception {
        Pair p = pair();
        var v = viewer();
        mvc.perform(get("/api/links").session(v)).andExpect(status().isOk());
        mvc.perform(post("/api/links").session(v).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(link(p.a, null, p.b, null, null)))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/links/1").session(v).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/links/1/confirm").session(v).with(csrf())).andExpect(status().isForbidden());
    }
}
