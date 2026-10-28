package com.omnidude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.metrics.MetricsWriter;
import com.omnidude.monitoring.MonitoringService;
import com.omnidude.monitoring.ServiceCheck;
import com.omnidude.monitoring.ServiceCheckRepository;
import com.omnidude.security.Role;
import com.omnidude.security.User;
import com.omnidude.security.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class ApiFlowTest extends IntegrationTestBase {

    private static final String ADMIN_PASS = "Test-Pass-12345";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired DeviceRepository devices;
    @Autowired ServiceCheckRepository checks;
    @Autowired MonitoringService monitoring;
    @Autowired MetricsWriter writer;
    @Autowired JdbcTemplate jdbc;

    private MockHttpSession admin;

    @BeforeEach
    void login() throws Exception {
        admin = login("admin", ADMIN_PASS);
    }

    private MockHttpSession login(String user, String pass) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).param("username", user).param("password", pass))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private void createUser(String name, String pass, Role role, boolean mustChange) {
        if (users.findByUsername(name).isPresent()) {
            return;
        }
        User u = new User();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(pass));
        u.setRole(role);
        u.setMustChangePassword(mustChange);
        u.setCreatedAt(Instant.now().getEpochSecond());
        users.save(u);
    }

    private JsonNode createDevice(String name, String ip) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("name", name, "primaryIp", ip, "deviceType", "ROUTER"));
        MvcResult r = mvc.perform(post("/api/devices").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).session(admin)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    // ------------------------------------------------------------ autenticação e autorização

    @Test
    void anonymousApiIs401AndPagesRedirectToLogin() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login.html"));
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "admin").param("password", "errada"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/devices").session(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"primaryIp\":\"198.51.100.200\"}")).andExpect(status().isForbidden());
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        createUser("viewer", "Viewer-Pass-123", Role.VIEWER, false);
        MockHttpSession viewer = login("viewer", "Viewer-Pass-123");
        mvc.perform(get("/api/devices").session(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/devices").session(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"primaryIp\":\"198.51.100.201\"}")).andExpect(status().isForbidden());
    }

    @Test
    void accountIsLockedAfterRepeatedFailures() throws Exception {
        createUser("locky", "Locky-Pass-1234", Role.VIEWER, false);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "locky").param("password", "x"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/login").with(csrf()).param("username", "locky").param("password", "Locky-Pass-1234"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void temporaryPasswordMustBeChangedBeforeUsingTheApi() throws Exception {
        createUser("novo", "Provisoria-123", Role.ADMIN, true);
        MockHttpSession s = login("novo", "Provisoria-123");
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/me").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        mvc.perform(post("/api/me/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Provisoria-123\",\"newPassword\":\"curta\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Provisoria-123\",\"newPassword\":\"Definitiva-4567\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ dispositivos, status e eventos

    @Test
    void deviceValidation() throws Exception {
        createDevice("dup", "198.51.100.50");
        mvc.perform(post("/api/devices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup2\",\"primaryIp\":\"198.51.100.50\"}")).andExpect(status().isConflict());
        for (String bad : new String[] {"1.2.3", "-f", "host.example.com", "1.1.1.1; ls"}) {
            mvc.perform(post("/api/devices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(java.util.Map.of("name", "x", "primaryIp", bad))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/devices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"primaryIp\":\"198.51.100.51\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void newDeviceGetsIcmpCheckAndMapPlacement() throws Exception {
        JsonNode d = createDevice("Roteador", "198.51.100.60");
        assertThat(d.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(d.get("checks").get(0).get("type").asText()).isEqualTo("ICMP");
        JsonNode map = getJson("/api/maps/1");
        boolean found = false;
        for (JsonNode n : map.get("nodes")) {
            if (n.get("deviceId").asLong() == d.get("id").asLong()) {
                found = true;
                assertThat(n.get("x").asDouble()).isPositive();
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void reachableDeviceBecomesUpWithoutEventAndResultIsPersisted() throws Exception {
        JsonNode d = createDevice("loopback", "127.0.0.1");
        long checkId = d.get("checks").get(0).get("id").asLong();
        monitoring.runCheck(checkId);
        JsonNode view = getJson("/api/devices/" + d.get("id").asLong());
        org.junit.jupiter.api.Assumptions.assumeTrue("UP".equals(view.get("status").asText()), "ping indisponível");
        assertThat(view.get("checks").get(0).get("lastResponseMs").asInt()).isLessThan(1000);
        writer.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_results WHERE check_id = ?", Integer.class, checkId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE device_id = ?", Integer.class,
                d.get("id").asLong())).isZero();          // UNKNOWN → UP não gera evento
    }

    @Test
    void unreachableDeviceGoesDownRaisesCriticalEventAndCanBeAcknowledged() throws Exception {
        JsonNode d = createDevice("sumiu", "192.0.2.123");
        long checkId = d.get("checks").get(0).get("id").asLong();
        ServiceCheck c = checks.findById(checkId).orElseThrow();
        c.setTimeoutMs(1000);
        c.setFailThreshold(2);
        checks.save(c);

        monitoring.runCheck(checkId);                          // 1ª falha: histerese, ainda UNKNOWN
        assertThat(getJson("/api/devices/" + d.get("id").asLong()).get("status").asText()).isEqualTo("UNKNOWN");
        monitoring.runCheck(checkId);                          // 2ª falha: DOWN
        assertThat(getJson("/api/devices/" + d.get("id").asLong()).get("status").asText()).isEqualTo("DOWN");

        JsonNode events = getJson("/api/events?device=" + d.get("id").asLong());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(events.get(0).get("type").asText()).isEqualTo("DEVICE_DOWN");

        long eventId = events.get(0).get("id").asLong();
        mvc.perform(post("/api/events/" + eventId + "/ack").session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(getJson("/api/events?unacked=true&device=" + d.get("id").asLong())).isEmpty();
        assertThat(getJson("/api/summary").get("devices").get("DOWN").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pausedDeviceIsNotChecked() throws Exception {
        JsonNode d = createDevice("pausado", "192.0.2.124");
        long id = d.get("id").asLong();
        mvc.perform(put("/api/devices/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"pausado\",\"primaryIp\":\"192.0.2.124\",\"enabled\":false}"))
                .andExpect(status().isOk());
        monitoring.runCheck(d.get("checks").get(0).get("id").asLong());
        assertThat(getJson("/api/devices/" + id).get("status").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void deletingDeviceRemovesChecksAndMapNodesButKeepsEvents() throws Exception {
        JsonNode d = createDevice("temporario", "198.51.100.70");
        long id = d.get("id").asLong();
        long checkId = d.get("checks").get(0).get("id").asLong();
        jdbc.update("INSERT INTO events(ts, severity, type, device_id, message) VALUES (1,'INFO','SYSTEM',?,'sobra')", id);
        mvc.perform(delete("/api/devices/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices/" + id).session(admin)).andExpect(status().isNotFound());
        assertThat(checks.findById(checkId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM map_nodes WHERE device_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE message='sobra' AND device_id IS NULL",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------ mapa

    @Test
    void nodePositionIsPersisted() throws Exception {
        JsonNode d = createDevice("movel", "198.51.100.80");
        long id = d.get("id").asLong();
        mvc.perform(put("/api/maps/1/nodes/" + id).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"x\":321.5,\"y\":-42.25,\"pinned\":true}")).andExpect(status().isNoContent());
        for (JsonNode n : getJson("/api/maps/1").get("nodes")) {
            if (n.get("deviceId").asLong() == id) {
                assertThat(n.get("x").asDouble()).isEqualTo(321.5);
                assertThat(n.get("y").asDouble()).isEqualTo(-42.25);
                assertThat(n.get("pinned").asBoolean()).isTrue();
                return;
            }
        }
        throw new AssertionError("nó não encontrado");
    }

    @Test
    void secondMapCanBeCreatedAndDeviceAddedToIt() throws Exception {
        JsonNode d = createDevice("em-dois-mapas", "198.51.100.81");
        MvcResult r = mvc.perform(post("/api/maps").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Datacenter\"}")).andExpect(status().isCreated()).andReturn();
        long mapId = json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(post("/api/maps/" + mapId + "/nodes").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":" + d.get("id") + ",\"x\":10,\"y\":20}"))
                .andExpect(status().isNoContent());
        assertThat(getJson("/api/maps/" + mapId).get("nodes")).hasSize(1);
        mvc.perform(post("/api/maps").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Datacenter\"}")).andExpect(status().isConflict());
    }
}
