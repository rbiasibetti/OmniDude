package com.omnidude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.monitoring.MonitoringService;
import com.omnidude.monitoring.ServiceCheck;
import com.omnidude.monitoring.ServiceCheckRepository;
import com.omnidude.metrics.MetricsWriter;
import com.omnidude.security.Role;
import com.omnidude.security.User;
import com.omnidude.security.UserRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

class ChecksApiTest extends IntegrationTestBase {

    private static final AtomicInteger NEXT_IP = new AtomicInteger(10);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceCheckRepository checks;
    @Autowired MonitoringService monitoring;
    @Autowired MetricsWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private MockHttpSession admin;

    @BeforeEach
    void login() throws Exception {
        admin = login("admin", "Test-Pass-12345");
    }

    // ------------------------------------------------------------------------------------ helpers

    private MockHttpSession login(String user, String pass) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", user).param("password", pass))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    /** IP de loopback exclusivo (127.0.N.1): o banco é compartilhado entre os testes. */
    private static String freshIp() {
        return "127.0." + NEXT_IP.getAndIncrement() + ".1";
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions a) throws Exception {
        return json.readTree(a.andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return read(mvc.perform(get(url).session(admin)).andExpect(status().isOk()));
    }

    private JsonNode createDevice(String ip) throws Exception {
        return read(postJson("/api/devices", Map.of("name", "dev " + ip, "primaryIp", ip)).andExpect(status().isCreated()));
    }

    private long icmpId(JsonNode device) {
        return device.get("checks").get(0).get("id").asLong();
    }

    private String deviceStatus(JsonNode device) throws Exception {
        return getJson("/api/devices/" + device.get("id").asLong()).get("status").asText();
    }

    private JsonNode addCheck(JsonNode device, Map<String, Object> body) throws Exception {
        return read(postJson("/api/devices/" + device.get("id").asLong() + "/checks", body).andExpect(status().isCreated()));
    }

    private void expectBadRequest(JsonNode device, Map<String, Object> body, String messagePart) throws Exception {
        postJson("/api/devices/" + device.get("id").asLong() + "/checks", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(messagePart)));
    }

    private ServerSocket listen(String ip) throws IOException {
        try {
            return new ServerSocket(0, 50, InetAddress.getByName(ip));
        } catch (IOException e) {
            assumeTrue(false, "loopback " + ip + " indisponível neste SO");
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------ ciclo completo

    @Test
    void tcpCheckMakesDeviceWarningWhenPortClosesAndRecoversAfterwards() throws Exception {
        String ip = freshIp();
        JsonNode device = createDevice(ip);
        monitoring.runCheck(icmpId(device));
        assumeTrue("UP".equals(deviceStatus(device)), "ping indisponível");

        ServerSocket server = listen(ip);
        int port = server.getLocalPort();
        JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", port, "name", "app", "failThreshold", 1,
                "recoverThreshold", 1, "intervalS", 5));
        long tcpId = tcp.get("id").asLong();
        assertThat(tcp.get("status").asText()).isEqualTo("UNKNOWN");

        JsonNode run = read(mvc.perform(post("/api/checks/" + tcpId + "/run").session(admin).with(csrf()))
                .andExpect(status().isOk()));
        assertThat(run.get("success").asBoolean()).isTrue();
        assertThat(run.get("check").get("status").asText()).isEqualTo("UP");
        assertThat(deviceStatus(device)).isEqualTo("UP");

        server.close();                                                   // a porta fecha
        run = read(mvc.perform(post("/api/checks/" + tcpId + "/run").session(admin).with(csrf())).andExpect(status().isOk()));
        assertThat(run.get("success").asBoolean()).isFalse();
        assertThat(run.get("check").get("status").asText()).isEqualTo("DOWN");
        assertThat(deviceStatus(device)).isEqualTo("WARNING");            // ICMP responde, o serviço não

        JsonNode events = getJson("/api/events?device=" + device.get("id").asLong());
        assertThat(events).extracting(e -> e.get("type").asText()).contains("CHECK_FAILED", "DEVICE_WARNING");

        try (ServerSocket again = new ServerSocket(port, 50, InetAddress.getByName(ip))) {   // a porta volta
            run = read(mvc.perform(post("/api/checks/" + tcpId + "/run").session(admin).with(csrf())).andExpect(status().isOk()));
            assertThat(run.get("success").asBoolean()).isTrue();
            assertThat(deviceStatus(device)).isEqualTo("UP");
            assertThat(getJson("/api/events?device=" + device.get("id").asLong()))
                    .extracting(e -> e.get("type").asText()).contains("CHECK_RECOVERED", "DEVICE_UP");
        }
    }

    // ------------------------------------------------------------------------------------ validação

    @Test
    void validationRules() throws Exception {
        JsonNode device = createDevice(freshIp());

        expectBadRequest(device, Map.of("type", "ICMP", "port", 7), "ICMP não usa porta");
        postJson("/api/devices/" + device.get("id") + "/checks", Map.of("type", "ICMP")).andExpect(status().isConflict());
        expectBadRequest(device, Map.of("type", "TCP"), "porta TCP");
        expectBadRequest(device, Map.of("type", "TCP", "port", 22, "params", Map.of("path", "/")), "não se aplicam");
        expectBadRequest(device, Map.of("type", "HTTP", "params", Map.of("path", "sem-barra")), "Caminho HTTP");
        expectBadRequest(device, Map.of("type", "HTTP", "params", Map.of("path", "/a b")), "Caminho HTTP");
        expectBadRequest(device, Map.of("type", "HTTP", "params", Map.of("foo", "bar")), "desconhecido");
        expectBadRequest(device, Map.of("type", "HTTP", "params", Map.of("expectedStatus", "20x")), "Status esperado");
        expectBadRequest(device, Map.of("type", "HTTP", "params", Map.of("method", "HEAD", "contains", "x")), "HEAD");
        expectBadRequest(device, Map.of("type", "DNS", "params", Map.of("name", "bad name")), "Nome DNS");
        expectBadRequest(device, Map.of("type", "DNS", "params", Map.of("recordType", "ANY")), "Tipo de registro");
        expectBadRequest(device, Map.of("type", "SNMP"), "perfil SNMP");
        expectBadRequest(device, Map.of("type", "TCP", "port", 80, "timeoutMs", 1000, "warnLatencyMs", 1000), "menor que o timeout");
        postJson("/api/devices/" + device.get("id") + "/checks", Map.of("type", "TCP", "port", 80, "intervalS", 1))
                .andExpect(status().isBadRequest());
        postJson("/api/devices/" + device.get("id") + "/checks", Map.of("type", "TCP", "port", 70000))
                .andExpect(status().isBadRequest());

        addCheck(device, Map.of("type", "TCP", "port", 8080));
        postJson("/api/devices/" + device.get("id") + "/checks", Map.of("type", "TCP", "port", 8080)).andExpect(status().isConflict());
    }

    @Test
    void defaultsAndHttpNormalisation() throws Exception {
        JsonNode device = createDevice(freshIp());
        JsonNode http = addCheck(device, Map.of("type", "HTTP", "params", Map.of("path", "/health", "verifyTls", "false")));
        assertThat(http.get("port").asInt()).isEqualTo(80);
        assertThat(http.get("name").asText()).isEqualTo("HTTP");
        assertThat(http.get("timeoutMs").asInt()).isEqualTo(5000);
        assertThat(http.get("params").has("verifyTls")).isFalse();        // só faz sentido em HTTPS
        JsonNode https = addCheck(device, Map.of("type", "HTTPS", "params", Map.of("verifyTls", "false")));
        assertThat(https.get("port").asInt()).isEqualTo(443);
        assertThat(https.get("params").get("verifyTls").asText()).isEqualTo("false");
        JsonNode dns = addCheck(device, Map.of("type", "DNS", "params", Map.of("recordType", "aaaa")));
        assertThat(dns.get("port").asInt()).isEqualTo(53);
        assertThat(dns.get("params").get("recordType").asText()).isEqualTo("AAAA");
    }

    @Test
    void updateResetsStateOnlyWhenTheTargetChanges() throws Exception {
        String ip = freshIp();
        JsonNode device = createDevice(ip);
        try (ServerSocket server = listen(ip)) {
            JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", server.getLocalPort()));
            long id = tcp.get("id").asLong();
            monitoring.runCheck(id, true);
            assertThat(getJson("/api/checks/" + id).get("status").asText()).isEqualTo("UP");

            putJson("/api/checks/" + id, Map.of("port", server.getLocalPort(), "name", "renomeada", "intervalS", 60))
                    .andExpect(status().isOk());
            JsonNode same = getJson("/api/checks/" + id);
            assertThat(same.get("name").asText()).isEqualTo("renomeada");
            assertThat(same.get("status").asText()).isEqualTo("UP");      // alvo igual: estado preservado

            putJson("/api/checks/" + id, Map.of("port", server.getLocalPort() == 9 ? 10 : 9)).andExpect(status().isOk());
            JsonNode moved = getJson("/api/checks/" + id);
            assertThat(moved.get("status").asText()).isEqualTo("UNKNOWN");   // alvo novo: recomeça
            assertThat(moved.has("lastResponseMs")).isFalse();

            putJson("/api/checks/" + id, Map.of("type", "HTTP", "port", 80)).andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------------------------ âncora e agenda

    @Test
    void deletingTheAnchorRecalculatesDeviceStatus() throws Exception {
        String ip = freshIp();
        JsonNode device = createDevice(ip);
        try (ServerSocket server = listen(ip)) {
            JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", server.getLocalPort(), "failThreshold", 1));
            monitoring.runCheck(tcp.get("id").asLong(), true);
            assertThat(deviceStatus(device)).isEqualTo("UNKNOWN");        // ICMP (âncora) ainda sem resultado

            mvc.perform(delete("/api/checks/" + icmpId(device)).session(admin).with(csrf())).andExpect(status().isNoContent());
            assertThat(deviceStatus(device)).isEqualTo("UP");             // sem âncora, vale o serviço restante
        }
    }

    @Test
    void deviceDownByIcmpOnlyRunsTheAnchorUnlessForced() throws Exception {
        JsonNode device = createDevice("192.0.2.150");
        long icmp = icmpId(device);
        ServiceCheck c = checks.findById(icmp).orElseThrow();
        c.setTimeoutMs(500);
        c.setFailThreshold(1);
        checks.save(c);
        JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", 9, "timeoutMs", 300));

        monitoring.runCheck(icmp);
        assertThat(deviceStatus(device)).isEqualTo("DOWN");
        assertThat(monitoring.runCheck(tcp.get("id").asLong())).as("não-âncora é pulada com o dispositivo DOWN").isNull();
        assertThat(monitoring.runCheck(tcp.get("id").asLong(), true)).as("manual ignora a regra").isNotNull();
        assertThat(monitoring.runCheck(icmp)).as("a âncora continua rodando").isNotNull();
    }

    @Test
    void serviceFailuresAreSuppressedWhileTheDeviceIsDown() throws Exception {
        JsonNode device = createDevice("192.0.2.152");
        long id = device.get("id").asLong();
        ServiceCheck icmp = checks.findById(icmpId(device)).orElseThrow();
        icmp.setTimeoutMs(500);
        icmp.setFailThreshold(1);
        checks.save(icmp);
        JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", 9, "timeoutMs", 300, "failThreshold", 1));

        monitoring.runCheck(icmp.getId());                                   // dispositivo DOWN
        monitoring.runCheck(tcp.get("id").asLong(), true);                   // o serviço também falha, mas...
        assertThat(getJson("/api/checks/" + tcp.get("id").asLong()).get("status").asText()).isEqualTo("DOWN");

        JsonNode events = getJson("/api/events?device=" + id);
        assertThat(events).extracting(e -> e.get("type").asText())
                .as("só o DEVICE_DOWN; nenhum CHECK_FAILED enquanto o dispositivo está fora do ar")
                .containsExactly("DEVICE_DOWN");
    }

    @Test
    void deviceWithoutAnchorIsStillReevaluatedWhenDown() throws Exception {
        JsonNode device = createDevice("192.0.2.151");
        mvc.perform(delete("/api/checks/" + icmpId(device)).session(admin).with(csrf())).andExpect(status().isNoContent());
        JsonNode tcp = addCheck(device, Map.of("type", "TCP", "port", 9, "timeoutMs", 300, "failThreshold", 1));
        long id = tcp.get("id").asLong();
        monitoring.runCheck(id);
        assertThat(deviceStatus(device)).isEqualTo("DOWN");
        assertThat(monitoring.runCheck(id)).as("sem âncora nada é pulado, senão ficaria DOWN para sempre").isNotNull();
    }

    // ------------------------------------------------------------------------------------ SNMP

    @Test
    void snmpProfileSecretsAreEncryptedAndNeverReturned() throws Exception {
        JsonNode created = read(postJson("/api/snmp-profiles", Map.of("name", "lab-segredo", "version", "2c",
                "community", "comunidade-super-secreta")).andExpect(status().isCreated()));
        assertThat(created.get("hasCommunity").asBoolean()).isTrue();
        assertThat(created.toString()).doesNotContain("comunidade-super-secreta");
        assertThat(getJson("/api/snmp-profiles").toString()).doesNotContain("comunidade-super-secreta");

        String stored = jdbc.queryForObject("SELECT community_enc FROM snmp_profiles WHERE id = ?", String.class,
                created.get("id").asLong());
        assertThat(stored).startsWith("v1:").doesNotContain("comunidade-super-secreta");

        postJson("/api/snmp-profiles", Map.of("name", "lab-segredo", "version", "2c", "community", "x")).andExpect(status().isConflict());
        postJson("/api/snmp-profiles", Map.of("name", "v3", "version", "3", "community", "x")).andExpect(status().isBadRequest());
        postJson("/api/snmp-profiles", Map.of("name", "sem-community", "version", "2c")).andExpect(status().isBadRequest());

        putJson("/api/snmp-profiles/" + created.get("id"), Map.of("name", "lab-segredo", "version", "2c", "retries", 2))
                .andExpect(status().isOk());                                   // community vazia mantém a atual
        assertThat(jdbc.queryForObject("SELECT community_enc FROM snmp_profiles WHERE id = ?", String.class,
                created.get("id").asLong())).isEqualTo(stored);
    }

    @Test
    void snmpCheckEndToEndAgainstAgentAndProfileDeletion() throws Exception {
        String ip = freshIp();
        FakeSnmpAgent agentOrNull = null;
        try {
            agentOrNull = new FakeSnmpAgent("e2e-community", ip);
        } catch (IOException e) {
            assumeTrue(false, "loopback " + ip + " indisponível neste SO");
        }
        try (FakeSnmpAgent agent = agentOrNull) {
            JsonNode profile = read(postJson("/api/snmp-profiles", Map.of("name", "e2e", "version", "2c",
                    "community", "e2e-community", "port", agent.port(), "retries", 0)).andExpect(status().isCreated()));
            JsonNode device = createDevice(ip);
            long id = device.get("id").asLong();
            putJson("/api/devices/" + id, Map.of("name", "snmp-dev", "primaryIp", device.get("primaryIp").asText(),
                    "snmpProfileId", profile.get("id").asLong())).andExpect(status().isOk());

            JsonNode snmp = addCheck(device, Map.of("type", "SNMP", "timeoutMs", 1000));
            assertThat(snmp.get("port").asInt()).as("porta padrão vem do perfil").isEqualTo(agent.port());

            JsonNode run = read(mvc.perform(post("/api/checks/" + snmp.get("id") + "/run").session(admin).with(csrf()))
                    .andExpect(status().isOk()));
            assertThat(run.get("success").asBoolean()).as(run.toString()).isTrue();

            mvc.perform(delete("/api/snmp-profiles/" + profile.get("id")).session(admin).with(csrf()))
                    .andExpect(status().isNoContent());
            assertThat(getJson("/api/devices/" + id).has("snmpProfileId")).isFalse();
            run = read(mvc.perform(post("/api/checks/" + snmp.get("id") + "/run").session(admin).with(csrf()))
                    .andExpect(status().isOk()));
            assertThat(run.get("success").asBoolean()).isFalse();
            assertThat(run.get("detail").asText()).contains("perfil");
        }
    }

    @Test
    void deviceWithUnknownSnmpProfileIsRejected() throws Exception {
        postJson("/api/devices", Map.of("name", "x", "primaryIp", freshIp(), "snmpProfileId", 999999))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------ resultados e painel

    @Test
    void resultsAreBucketedWithAvailabilityStats() throws Exception {
        JsonNode device = createDevice(freshIp());
        long id = icmpId(device);
        long now = Instant.now().getEpochSecond();
        for (int i = 0; i < 10; i++) {                    // 10 amostras nos últimos 5 minutos: 8 ok (10 ms e 30 ms), 2 falhas
            boolean ok = i < 8;
            jdbc.update("INSERT INTO check_results(check_id, ts, success, response_ms) VALUES (?,?,?,?)",
                    id, now - 300 + i * 30, ok ? 1 : 0, ok ? (i % 2 == 0 ? 10 : 30) : null);
        }
        JsonNode series = getJson("/api/checks/" + id + "/results?range=1h");
        JsonNode stats = series.get("stats");
        assertThat(stats.get("samples").asInt()).isEqualTo(10);
        assertThat(stats.get("okSamples").asInt()).isEqualTo(8);
        assertThat(stats.get("availabilityPct").asDouble()).isEqualTo(80.0);
        assertThat(stats.get("avgMs").asDouble()).isEqualTo(20.0);
        assertThat(stats.get("minMs").asInt()).isEqualTo(10);
        assertThat(stats.get("maxMs").asInt()).isEqualTo(30);

        int total = 0;
        for (JsonNode p : series.get("points")) {
            total += p.get("total").asInt();
        }
        assertThat(total).isEqualTo(10);
        assertThat(series.get("bucketS").asLong()).isGreaterThanOrEqualTo(30);   // nunca abaixo do intervalo da checagem

        mvc.perform(get("/api/checks/" + id + "/results?range=3y").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/checks/999999/results").session(admin)).andExpect(status().isNotFound());
        assertThat(getJson("/api/checks/" + id + "/results?range=7d").get("stats").get("samples").asInt()).isEqualTo(10);
    }

    @Test
    void emptyHistoryHasNullStatsInsteadOfFailing() throws Exception {
        JsonNode device = createDevice(freshIp());
        JsonNode series = getJson("/api/checks/" + icmpId(device) + "/results");
        assertThat(series.get("points")).isEmpty();
        assertThat(series.get("stats").get("samples").asInt()).isZero();
        assertThat(series.get("stats").has("availabilityPct")).isFalse();
    }

    @Test
    void dashboardSummarisesProblemsAndSlowChecks() throws Exception {
        JsonNode down = createDevice("192.0.2.160");
        ServiceCheck c = checks.findById(icmpId(down)).orElseThrow();
        c.setTimeoutMs(500);
        c.setFailThreshold(1);
        checks.save(c);
        monitoring.runCheck(icmpId(down));

        JsonNode slow = createDevice(freshIp());
        ServiceCheck sc = checks.findById(icmpId(slow)).orElseThrow();
        sc.setStatus(com.omnidude.device.Status.UP);
        sc.setLastResponseMs(987654);
        sc.setLastCheckAt(Instant.now().getEpochSecond());
        checks.save(sc);

        JsonNode dash = getJson("/api/dashboard");
        assertThat(dash.get("devices").get("DOWN").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dash.get("totalDevices").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(dash.get("problems")).extracting(p -> p.get("name").asText()).contains("dev 192.0.2.160");
        assertThat(dash.get("slowest").get(0).get("ms").asInt()).isEqualTo(987654);
        assertThat(dash.get("slowest").get(0).get("device").asText()).isEqualTo("dev " + slow.get("primaryIp").asText());
        assertThat(dash.get("events").size()).isLessThanOrEqualTo(8);
        assertThat(dash.get("checks").has("UP")).isTrue();

        sc.setLastCheckAt(Instant.now().getEpochSecond() - 3600);          // medição velha não conta como "agora"
        checks.save(sc);
        assertThat(getJson("/api/dashboard").get("slowest").toString()).doesNotContain("987654");
    }

    // ------------------------------------------------------------------------------------ autorização

    @Test
    void viewerCanReadButNotChangeChecksOrRunThem() throws Exception {
        if (users.findByUsername("viewer2").isEmpty()) {
            User u = new User();
            u.setUsername("viewer2");
            u.setPasswordHash(encoder.encode("Viewer2-Pass-123"));
            u.setRole(Role.VIEWER);
            u.setCreatedAt(Instant.now().getEpochSecond());
            users.save(u);
        }
        JsonNode device = createDevice(freshIp());
        MockHttpSession viewer = login("viewer2", "Viewer2-Pass-123");
        mvc.perform(get("/api/devices/" + device.get("id") + "/checks").session(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/checks/" + icmpId(device) + "/results").session(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").session(viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/snmp-profiles").session(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + device.get("id") + "/checks").session(viewer).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"TCP\",\"port\":22}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/checks/" + icmpId(device) + "/run").session(viewer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/checks/" + icmpId(device)).session(viewer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/snmp-profiles").session(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"version\":\"2c\",\"community\":\"y\"}")).andExpect(status().isForbidden());
    }
}
