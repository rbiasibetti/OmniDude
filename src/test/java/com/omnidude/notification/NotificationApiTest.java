package com.omnidude.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSmtpServer;
import com.omnidude.admin.SettingsService;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.EventView;
import com.omnidude.event.Severity;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Canais, regras e entrega de notificações contra servidores locais (HTTP e SMTP) que registram o que recebem. */
class NotificationApiTest extends ApiTestSupport {

    private static final String BOT_TOKEN = "123456789:AAEhBP0av18KmG1MgqYV2jzZkqyJgq-WABC";

    @Autowired NotificationService notifications;
    @Autowired SettingsService settings;
    @Autowired EventService events;

    private record Hit(String path, Map<String, String> headers, String body) {
    }

    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    /** A partir desta chamada o servidor responde 200, qualquer que seja o status configurado. */
    private final AtomicInteger okFromCall = new AtomicInteger(Integer.MAX_VALUE);
    private int httpPort;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM notification_channels");           // regras e histórico saem junto
        jdbc.update("DELETE FROM notification_log");
        settings.update(new HashMap<>(Map.of(SettingsService.NOTIFY_DEDUPE_S, 0, SettingsService.MAINTENANCE_ENABLED, false)));
        notifications.retryDelaysMs = new long[] {10, 10};
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            int code = calls.incrementAndGet() >= okFromCall.get() ? 200 : status.get();
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            hits.add(new Hit(ex.getRequestURI().getPath(), h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = (code >= 400 ? "{\"ok\":false,\"description\":\"chat not found\"}" : "{\"ok\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        httpPort = http.getAddress().getPort();
        closeables.add(() -> http.stop(0));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        settings.update(new HashMap<>(Map.of(SettingsService.NOTIFY_DEDUPE_S, 60, SettingsService.MAINTENANCE_ENABLED, false)));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private long webhook(String name, String secret) throws Exception {
        Map<String, String> cfg = new HashMap<>(Map.of("url", "http://127.0.0.1:" + httpPort + "/hook"));
        if (secret != null) {
            cfg.put("secret", secret);
        }
        return channel(name, "WEBHOOK", cfg);
    }

    private long telegram(String name) throws Exception {
        return channel(name, "TELEGRAM", Map.of("botToken", BOT_TOKEN, "chatId", "-1001234567890",
                "apiBase", "http://127.0.0.1:" + httpPort));
    }

    private long channel(String name, String type, Map<String, String> cfg) throws Exception {
        return read(postJson("/api/notification-channels", Map.of("name", name, "type", type, "config", cfg))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private long rule(long channelId, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("channelId", channelId);
        return read(postJson("/api/notification-rules", body).andExpect(status().isCreated())).get("id").asLong();
    }

    private static final AtomicInteger EVENT_SEQ = new AtomicInteger(1000);

    private EventView event(Severity sev, EventType type, Long deviceId, String text) {
        return new EventView((long) EVENT_SEQ.incrementAndGet(), System.currentTimeMillis() / 1000, sev, type, deviceId, text, false);
    }

    private List<Map<String, Object>> logRows() {
        return jdbc.queryForList("SELECT * FROM notification_log ORDER BY id");
    }

    private long countLog(String status) {
        return logRows().stream().filter(r -> status.equals(r.get("status"))).count();
    }

    // ------------------------------------------------------------------------------------------------ canais

    @Test
    void channelsNeverReturnSecretsAndAreEncryptedAtRest() throws Exception {
        long id = webhook("hook", "segredo-do-webhook");
        long tg = telegram("tg");
        String list = mvc.perform(get("/api/notification-channels").session(admin)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).doesNotContain("segredo-do-webhook").doesNotContain(BOT_TOKEN).doesNotContain("AAEhBP0av18");
        JsonNode hook = getJson("/api/notification-channels").get(0);
        assertThat(hook.get("secrets").get("secret").asBoolean()).isTrue();
        assertThat(hook.get("config").get("url").asText()).startsWith("http://127.0.0.1");

        String stored = jdbc.queryForObject("SELECT config_enc FROM notification_channels WHERE id = ?", String.class, id)
                + jdbc.queryForObject("SELECT config_enc FROM notification_channels WHERE id = ?", String.class, tg);
        assertThat(stored).as("criptografado em repouso").doesNotContain("127.0.0.1").doesNotContain("segredo-do-webhook")
                .doesNotContain("AAEhBP0av18");

        // editar com o segredo em branco o mantém
        putJson("/api/notification-channels/" + id, Map.of("name", "hook2", "type", "WEBHOOK",
                "config", Map.of("url", "http://127.0.0.1:" + httpPort + "/hook", "secret", ""))).andExpect(status().isOk());
        assertThat(getJson("/api/notification-channels").toString()).contains("\"secret\":true");
        notifications.test(id);
        assertThat(hits.get(hits.size() - 1).headers()).as("a assinatura continua usando o segredo guardado")
                .containsKey("x-omnidude-signature");
    }

    @Test
    void channelValidation() throws Exception {
        postJson("/api/notification-channels", Map.of("name", "x", "type", "WEBHOOK", "config", Map.of("url", "ftp://x/y")))
                .andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "WEBHOOK", "config", Map.of("url", "javascript:alert(1)")))
                .andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "TELEGRAM", "config", Map.of("chatId", "-100123456")))
                .andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "TELEGRAM",
                "config", Map.of("botToken", "token-ruim", "chatId", "-100123456"))).andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "TELEGRAM",
                "config", Map.of("botToken", BOT_TOKEN, "chatId", "quero-ligar"))).andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "EMAIL",
                "config", Map.of("host", "smtp.exemplo.com", "from", "nao-e-email", "to", "a@b.co"))).andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "EMAIL",
                "config", Map.of("host", "smtp.exemplo.com", "from", "a@b.co", "to", "sem-arroba"))).andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "x", "type", "EMAIL",
                "config", Map.of("host", "smtp.exemplo.com", "from", "a@b.co", "to", "c@d.co", "security", "TLS13")))
                .andExpect(status().isBadRequest());
        postJson("/api/notification-channels", Map.of("name", "", "type", "WEBHOOK")).andExpect(status().isBadRequest());

        long id = webhook("dup", null);
        postJson("/api/notification-channels", Map.of("name", "dup", "type", "WEBHOOK",
                "config", Map.of("url", "http://127.0.0.1/x"))).andExpect(status().isConflict());
        putJson("/api/notification-channels/" + id, Map.of("name", "dup", "type", "TELEGRAM",
                "config", Map.of("botToken", BOT_TOKEN, "chatId", "-100123456"))).andExpect(status().isBadRequest());
        putJson("/api/notification-channels/999999", Map.of("name", "z", "type", "WEBHOOK",
                "config", Map.of("url", "http://127.0.0.1/x"))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/notification-channels/999999").session(admin).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void emailDefaultsThePortFromTheSecurityMode() throws Exception {
        long id = channel("mail", "EMAIL", Map.of("host", "smtp.exemplo.com", "from", "a@b.co", "to", "c@d.co, e@f.co"));
        JsonNode v = getJson("/api/notification-channels").get(0);
        assertThat(v.get("id").asLong()).isEqualTo(id);
        assertThat(v.get("config").get("port").asText()).isEqualTo("587");
        assertThat(v.get("config").get("security").asText()).isEqualTo("STARTTLS");
        assertThat(v.get("config").get("to").asText()).isEqualTo("c@d.co,e@f.co");
        assertThat(v.get("secrets").get("password").asBoolean()).isFalse();
    }

    @Test
    void onlyAdminsCanTouchNotificationsSettingsOrBackups() throws Exception {
        for (String url : new String[] {"/api/notification-channels", "/api/notification-rules", "/api/notification-log"}) {
            mvc.perform(get(url).session(viewer())).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/notification-channels/1/test").session(viewer()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void deletingAChannelRemovesItsRulesButKeepsTheHistory() throws Exception {
        long id = webhook("efemero", null);
        rule(id, Map.of());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "x caiu"));
        assertThat(countLog("SENT")).isEqualTo(1);
        mvc.perform(delete("/api/notification-channels/" + id).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(getJson("/api/notification-rules")).isEmpty();
        assertThat(getJson("/api/notification-log")).hasSize(1);
        assertThat(getJson("/api/notification-log").get(0).get("channel").asText()).isEqualTo("efemero");
    }

    // ------------------------------------------------------------------------------------------------ entrega

    @Test
    void webhookReceivesSignedJson() throws Exception {
        long id = webhook("hook", "chave-de-assinatura");
        rule(id, Map.of("minSeverity", "WARNING"));
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "core-sw caiu"));

        assertThat(hits).hasSize(1);
        Hit h = hits.get(0);
        JsonNode body = json.readTree(h.body());
        assertThat(body.get("source").asText()).isEqualTo("omnidude");
        assertThat(body.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(body.get("type").asText()).isEqualTo("DEVICE_DOWN");
        assertThat(body.get("message").asText()).isEqualTo("core-sw caiu");
        assertThat(h.headers().get("x-omnidude-signature")).isEqualTo("sha256=" + WebhookNotifier.hmac("chave-de-assinatura", h.body()));
        assertThat(h.headers().get("content-type")).startsWith("application/json");
        assertThat(logRows().get(0).get("status")).isEqualTo("SENT");
    }

    @Test
    void telegramSendsToTheBotApiWithoutLeakingTheTokenOnFailure() throws Exception {
        long id = telegram("tg");
        rule(id, Map.of());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "core-sw caiu"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).path()).isEqualTo("/bot" + BOT_TOKEN + "/sendMessage");
        JsonNode body = json.readTree(hits.get(0).body());
        assertThat(body.get("chat_id").asText()).isEqualTo("-1001234567890");
        assertThat(body.get("text").asText()).contains("[CRITICAL] core-sw caiu");

        status.set(400);                                                    // chat inexistente: erro de configuração, sem retentativa
        hits.clear();
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "outro caiu"));
        assertThat(hits).as("400 não é repetido").hasSize(1);
        assertThat(logRows().get(1).get("status")).isEqualTo("FAILED");
        assertThat(logRows().get(1).get("detail").toString()).contains("HTTP 400").contains("chat not found")
                .doesNotContain(BOT_TOKEN).doesNotContain("AAEhBP0av18");
        TestResultHolder.assertNoToken(mvc.perform(get("/api/notification-log").session(admin)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static final class TestResultHolder {
        static void assertNoToken(String text) {
            assertThat(text).doesNotContain(BOT_TOKEN).doesNotContain("AAEhBP0av18");
        }
    }

    @Test
    void serverErrorsAreRetriedUpToThreeTimesThenLoggedAsFailed() throws Exception {
        long id = webhook("fraco", null);
        rule(id, Map.of());
        status.set(503);
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "x caiu"));
        assertThat(calls.get()).isEqualTo(3);
        assertThat(logRows().get(0).get("status")).isEqualTo("FAILED");
        assertThat(((Number) logRows().get(0).get("attempts")).intValue()).isEqualTo(3);

        // recupera na segunda tentativa
        calls.set(0);
        hits.clear();
        status.set(503);
        okFromCall.set(2);
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "y caiu"));
        assertThat(countLog("SENT")).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(((Number) logRows().get(1).get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void emailGoesOutBySmtpWithAuthenticationAndUtf8() throws Exception {
        FakeSmtpServer smtp = new FakeSmtpServer("alerta", "senha-smtp-1");
        closeables.add(smtp);
        long id = channel("mail", "EMAIL", Map.of("host", "127.0.0.1", "port", String.valueOf(smtp.port()), "security", "NONE",
                "from", "omnidude@exemplo.com", "to", "ops@exemplo.com, noc@exemplo.com", "username", "alerta",
                "password", "senha-smtp-1"));
        rule(id, Map.of());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "Servidor de produção caiu — ação imediata"));

        assertThat(smtp.messages()).hasSize(1);
        var mail = smtp.messages().get(0);
        assertThat(mail.from()).isEqualTo("omnidude@exemplo.com");
        assertThat(mail.to()).containsExactlyInAnyOrder("ops@exemplo.com", "noc@exemplo.com");
        assertThat(mail.data()).contains("Subject:").contains("Content-Type: text/plain; charset=UTF-8");
        assertThat(logRows().get(0).get("status")).isEqualTo("SENT");

        // senha errada: falha sem retentativa e sem expor a senha
        long bad = channel("mail-ruim", "EMAIL", Map.of("host", "127.0.0.1", "port", String.valueOf(smtp.port()), "security", "NONE",
                "from", "omnidude@exemplo.com", "to", "ops@exemplo.com", "username", "alerta", "password", "errada-123"));
        var result = notifications.test(bad);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("recusou").doesNotContain("errada-123").doesNotContain("senha-smtp-1");
    }

    @Test
    void aCriticalEventReachesAllThreeChannelTypes() throws Exception {
        FakeSmtpServer smtp = new FakeSmtpServer(null, null);
        closeables.add(smtp);
        long w = webhook("w", null);
        long t = telegram("t");
        long m = channel("m", "EMAIL", Map.of("host", "127.0.0.1", "port", String.valueOf(smtp.port()), "security", "NONE",
                "from", "o@exemplo.com", "to", "p@exemplo.com"));
        for (long c : new long[] {w, t, m}) {
            rule(c, Map.of("minSeverity", "CRITICAL"));
        }
        // pelo caminho real: o evento é gravado e publicado, e a entrega é assíncrona
        EventView view = events.record(Severity.CRITICAL, EventType.DEVICE_DOWN, null, null, "roteador-borda caiu");
        events.publish(view);
        long deadline = System.currentTimeMillis() + 5000;
        while (countLog("SENT") < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(countLog("SENT")).isEqualTo(3);
        assertThat(hits.stream().map(Hit::path)).anyMatch(p -> p.equals("/hook")).anyMatch(p -> p.endsWith("/sendMessage"));
        assertThat(smtp.messages()).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------------ regras

    @Test
    void rulesFilterBySeverityTypeAndDeviceAndRecoveryFollowsTheFilters() throws Exception {
        long dev = createDevice("alvo", "127.0.82.1", "SERVER").get("id").asLong();
        long other = createDevice("outro", "127.0.82.2", "SERVER").get("id").asLong();
        long bySeverity = webhook("severidade", null);
        rule(bySeverity, Map.of("minSeverity", "CRITICAL", "includeRecovery", false));
        long byType = webhook2("tipo");
        rule(byType, Map.of("minSeverity", "INFO", "eventTypes", List.of("LINK_DOWN"), "includeRecovery", false));
        long byDevice = webhook2("dispositivo");
        rule(byDevice, Map.of("minSeverity", "INFO", "deviceId", dev, "includeRecovery", false));

        notifications.process(event(Severity.WARNING, EventType.CHECK_FAILED, other, "aviso qualquer"));
        assertThat(logRows()).as("WARNING fora de todos os filtros").isEmpty();
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, other, "crítico"));
        assertThat(channelsSent()).containsExactly("severidade");
        notifications.process(event(Severity.WARNING, EventType.LINK_DOWN, other, "enlace caiu"));
        assertThat(channelsSent()).contains("tipo");
        notifications.process(event(Severity.INFO, EventType.DISCOVERY, dev, "do dispositivo certo"));
        assertThat(channelsSent()).contains("dispositivo");

        // recuperação: INFO, mas com includeRecovery o filtro de gravidade não a barra
        jdbc.update("DELETE FROM notification_log");
        long rec = webhook2("recuperacao");
        rule(rec, Map.of("minSeverity", "WARNING", "includeRecovery", true));
        long norec = webhook2("sem-recuperacao");
        rule(norec, Map.of("minSeverity", "WARNING", "includeRecovery", false));
        notifications.process(event(Severity.INFO, EventType.DEVICE_UP, other, "voltou"));
        assertThat(channelsSent()).containsExactly("recuperacao");
        notifications.process(event(Severity.INFO, EventType.DISCOVERY, other, "INFO comum não é recuperação"));
        assertThat(channelsSent()).hasSize(1);
    }

    private long webhook2(String name) throws Exception {
        return webhook(name, null);
    }

    private List<String> channelsSent() {
        return logRows().stream().filter(r -> "SENT".equals(r.get("status"))).map(r -> (String) r.get("channel")).toList();
    }

    @Test
    void oneChannelWithTwoMatchingRulesGetsOneMessage() throws Exception {
        long id = webhook("dobrado", null);
        rule(id, Map.of("minSeverity", "INFO"));
        rule(id, Map.of("minSeverity", "CRITICAL"));
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "uma vez só"));
        assertThat(hits).hasSize(1);
    }

    @Test
    void disabledChannelsAndRulesSendNothing() throws Exception {
        long id = webhook("desligado", null);
        long ruleId = rule(id, Map.of());
        putJson("/api/notification-channels/" + id, Map.of("name", "desligado", "type", "WEBHOOK", "enabled", false,
                "config", Map.of("url", "http://127.0.0.1:" + httpPort + "/hook"))).andExpect(status().isOk());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "x"));
        assertThat(hits).isEmpty();
        putJson("/api/notification-channels/" + id, Map.of("name", "desligado", "type", "WEBHOOK", "enabled", true,
                "config", Map.of("url", "http://127.0.0.1:" + httpPort + "/hook"))).andExpect(status().isOk());
        putJson("/api/notification-rules/" + ruleId, Map.of("channelId", id, "enabled", false)).andExpect(status().isOk());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "x"));
        assertThat(hits).isEmpty();
    }

    @Test
    void ruleValidation() throws Exception {
        postJson("/api/notification-rules", Map.of("channelId", 999999)).andExpect(status().isBadRequest());
        long id = webhook("r", null);
        postJson("/api/notification-rules", Map.of("channelId", id, "deviceId", 999999)).andExpect(status().isBadRequest());
        postJson("/api/notification-rules", Map.of("channelId", id, "minSeverity", "ENORME")).andExpect(status().isBadRequest());
        postJson("/api/notification-rules", Map.of("channelId", id, "eventTypes", List.of("INVENTADO"))).andExpect(status().isBadRequest());
        postJson("/api/notification-rules", Map.of()).andExpect(status().isBadRequest());
        JsonNode r = read(postJson("/api/notification-rules", Map.of("channelId", id, "eventTypes", List.of("DEVICE_DOWN", "LINK_DOWN")))
                .andExpect(status().isCreated()));
        assertThat(r.get("minSeverity").asText()).as("padrão").isEqualTo("WARNING");
        assertThat(r.get("includeRecovery").asBoolean()).isTrue();
        assertThat(r.get("eventTypes")).hasSize(2);
        assertThat(r.get("channelName").asText()).isEqualTo("r");
    }

    // ------------------------------------------------------------------------------------------------ anti-ruído

    @Test
    void maintenanceModeSuppressesEverythingAndEndsOnItsOwn() throws Exception {
        long id = webhook("m", null);
        rule(id, Map.of());
        putJson("/api/settings", Map.of(SettingsService.MAINTENANCE_ENABLED, true)).andExpect(status().isOk());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "durante a manutenção"));
        assertThat(hits).isEmpty();
        assertThat(logRows().get(0).get("status")).isEqualTo("SUPPRESSED");
        assertThat(logRows().get(0).get("detail").toString()).contains("manutenção");

        long past = System.currentTimeMillis() / 1000 - 10;
        putJson("/api/settings", Map.of(SettingsService.MAINTENANCE_UNTIL, past)).andExpect(status().isOk());
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "depois do prazo"));
        assertThat(hits).as("o prazo passou: o modo se desligou sozinho").hasSize(1);
        putJson("/api/settings", Map.of(SettingsService.MAINTENANCE_ENABLED, false, SettingsService.MAINTENANCE_UNTIL, 0));
    }

    @Test
    void identicalMessagesWithinTheWindowAreSuppressed() throws Exception {
        long id = webhook("d", null);
        rule(id, Map.of());
        settings.update(new HashMap<>(Map.of(SettingsService.NOTIFY_DEDUPE_S, 60)));
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "igual"));
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "igual"));
        notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "diferente"));
        assertThat(hits).hasSize(2);
        assertThat(countLog("SUPPRESSED")).isEqualTo(1);
        assertThat(logRows().get(1).get("detail").toString()).contains("repetida");
    }

    @Test
    void aStormIsCappedPerChannelPerMinute() throws Exception {
        long id = webhook("tempestade", null);
        rule(id, Map.of());
        for (int i = 0; i < NotificationService.MAX_PER_MINUTE + 5; i++) {
            notifications.process(event(Severity.CRITICAL, EventType.DEVICE_DOWN, null, "queda " + i));
        }
        assertThat(hits).hasSize(NotificationService.MAX_PER_MINUTE);
        assertThat(countLog("SUPPRESSED")).isEqualTo(5);
    }

    @Test
    void theTestEndpointReportsSuccessAndFailureWithoutRetries() throws Exception {
        long ok = webhook("ok", null);
        JsonNode r = read(mvc.perform(post("/api/notification-channels/" + ok + "/test").session(admin).with(csrf()))
                .andExpect(status().isOk()));
        assertThat(r.get("ok").asBoolean()).isTrue();
        assertThat(json.readTree(hits.get(0).body()).get("message").asText()).contains("teste");

        status.set(500);
        calls.set(0);
        JsonNode bad = read(mvc.perform(post("/api/notification-channels/" + ok + "/test").session(admin).with(csrf()))
                .andExpect(status().isOk()));
        assertThat(bad.get("ok").asBoolean()).isFalse();
        assertThat(bad.get("error").asText()).contains("HTTP 500");
        assertThat(calls.get()).as("o teste não repete").isEqualTo(1);
        assertThat(logRows().stream().filter(x -> "TEST".equals(x.get("status"))).count()).isEqualTo(2);
        mvc.perform(post("/api/notification-channels/999999/test").session(admin).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void unreachableWebhookIsReportedAsANetworkFailure() throws Exception {
        long id = channel("fora", "WEBHOOK", Map.of("url", "http://127.0.0.1:1/nada"));
        NotificationService.TestResult r = notifications.test(id);
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).contains("rede");
    }

}
