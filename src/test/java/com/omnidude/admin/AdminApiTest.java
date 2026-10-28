package com.omnidude.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.config.OmniDudeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/** Ajustes, usuários, backups e modo de manutenção. */
class AdminApiTest extends ApiTestSupport {

    @Autowired SettingsService settings;
    @Autowired BackupService backups;
    @Autowired OmniDudeProperties props;

    @AfterEach
    void restore() throws Exception {
        Map<String, Object> reset = new HashMap<>();
        for (var item : settings.list()) {
            if (item.custom()) {
                reset.put(item.key(), null);
            }
        }
        settings.update(reset);
    }

    // ------------------------------------------------------------------------------------------------ ajustes

    @Test
    void settingsListDefaultsAndAcceptOverridesAndResets() throws Exception {
        JsonNode all = getJson("/api/settings");
        JsonNode raw = find(all, "retention.raw-days");
        assertThat(raw.get("value").asInt()).isEqualTo(7);
        assertThat(raw.get("custom").asBoolean()).isFalse();
        assertThat(raw.get("min").asInt()).isEqualTo(1);

        JsonNode after = read(putJson("/api/settings", Map.of("retention.raw-days", 14, "backup.enabled", true))
                .andExpect(status().isOk()));
        assertThat(find(after, "retention.raw-days").get("value").asInt()).isEqualTo(14);
        assertThat(find(after, "retention.raw-days").get("custom").asBoolean()).isTrue();
        assertThat(find(after, "backup.enabled").get("value").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT setting_value FROM settings WHERE setting_key = 'retention.raw-days'", String.class))
                .isEqualTo("14");

        // JSON escrito à mão: o ObjectMapper da aplicação omite nulos, e aqui o null é justamente o pedido de "voltar ao padrão"
        JsonNode back = read(mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"retention.raw-days\":null,\"backup.enabled\":false}")).andExpect(status().isOk()));
        assertThat(find(back, "retention.raw-days").get("custom").asBoolean()).isFalse();
        assertThat(jdbc.queryForList("SELECT 1 FROM settings WHERE setting_key IN ('retention.raw-days','backup.enabled')"))
                .as("voltar ao padrão apaga a linha").isEmpty();
    }

    @Test
    void invalidSettingsAreRefusedAsAWholeAndNothingIsApplied() throws Exception {
        putJson("/api/settings", Map.of("retention.raw-days", 500)).andExpect(status().isBadRequest());
        putJson("/api/settings", Map.of("retention.raw-days", 0)).andExpect(status().isBadRequest());
        putJson("/api/settings", Map.of("retention.raw-days", "muitos")).andExpect(status().isBadRequest());
        putJson("/api/settings", Map.of("ajuste.inventado", 1)).andExpect(status().isBadRequest());
        putJson("/api/settings", Map.of("backup.enabled", "talvez")).andExpect(status().isBadRequest());
        putJson("/api/settings", Map.of("backup.hour", 24)).andExpect(status().isBadRequest());
        // um válido junto com um inválido: nada entra
        putJson("/api/settings", Map.of("retention.hourly-days", 100, "retention.raw-days", 9999)).andExpect(status().isBadRequest());
        assertThat(find(getJson("/api/settings"), "retention.hourly-days").get("custom").asBoolean()).isFalse();
    }

    @Test
    void settingsTakeEffectWithoutARestart() throws Exception {
        putJson("/api/settings", Map.of("monitoring.default-interval-s", 45, "monitoring.default-timeout-ms", 3500))
                .andExpect(status().isOk());
        JsonNode device = createDevice("padrao-novo", "127.0.83.1", "SERVER");
        JsonNode ping = device.get("checks").get(0);
        assertThat(ping.get("intervalS").asInt()).isEqualTo(45);
        assertThat(ping.get("timeoutMs").asInt()).isEqualTo(3500);
    }

    @Test
    void retentionUsesTheConfiguredDays() throws Exception {
        long dev = createDevice("ret", "127.0.83.2", "SWITCH").get("id").asLong();
        jdbc.update("INSERT INTO interfaces(device_id, if_index, name, monitored) VALUES (?, 1, 'Gi0/1', 1)", dev);
        long ifId = jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id = ?", Long.class, dev);
        long now = System.currentTimeMillis() / 1000;
        for (int days : new int[] {1, 4, 10}) {
            jdbc.update("INSERT OR REPLACE INTO interface_metrics(interface_id, ts, in_octets, out_octets) VALUES (?,?,1,1)",
                    ifId, now - days * 86_400L);
        }
        putJson("/api/settings", Map.of("retention.raw-days", 3)).andExpect(status().isOk());
        com.omnidude.metrics.RetentionJob job = org.springframework.test.util.AopTestUtils.getTargetObject(
                applicationContextBean(com.omnidude.metrics.RetentionJob.class));
        invokeRun(job, now);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics WHERE interface_id = ?", Integer.class, ifId))
                .as("só o de 1 dia sobrou com 3 dias de retenção").isEqualTo(1);
    }

    @Autowired org.springframework.context.ApplicationContext context;

    private <T> T applicationContextBean(Class<T> type) {
        return context.getBean(type);
    }

    private static void invokeRun(Object job, long now) throws Exception {
        var m = job.getClass().getDeclaredMethod("run", long.class);
        m.setAccessible(true);
        m.invoke(job, now);
    }

    @Test
    void viewersCannotReadOrChangeSettings() throws Exception {
        mvc.perform(get("/api/settings").session(viewer())).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings").session(viewer()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    }

    private static JsonNode find(JsonNode list, String key) {
        for (JsonNode n : list) {
            if (n.get("key").asText().equals(key)) {
                return n;
            }
        }
        throw new AssertionError("ajuste ausente: " + key);
    }

    // ------------------------------------------------------------------------------------------------ manutenção

    @Test
    void maintenanceModeIsVisibleToEveryoneInTheSummaryAndExpires() throws Exception {
        assertThat(getJson("/api/summary").get("maintenance").asBoolean()).isFalse();
        putJson("/api/settings", Map.of("maintenance.enabled", true)).andExpect(status().isOk());
        assertThat(getJson("/api/summary").get("maintenance").asBoolean()).isTrue();
        JsonNode asViewer = read(mvc.perform(get("/api/summary").session(viewer())).andExpect(status().isOk()));
        assertThat(asViewer.get("maintenance").asBoolean()).as("o aviso vale para todos").isTrue();

        long past = System.currentTimeMillis() / 1000 - 5;
        putJson("/api/settings", Map.of("maintenance.until", past)).andExpect(status().isOk());
        assertThat(getJson("/api/summary").get("maintenance").asBoolean()).as("o prazo passou").isFalse();
        long future = System.currentTimeMillis() / 1000 + 3600;
        putJson("/api/settings", Map.of("maintenance.until", future)).andExpect(status().isOk());
        assertThat(getJson("/api/summary").get("maintenance").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ usuários

    private JsonNode newUser(String name, String role) throws Exception {
        return read(postJson("/api/users", Map.of("username", name, "password", "Senha-Provisoria-1", "role", role))
                .andExpect(status().isCreated()));
    }

    private MockHttpSession loginAfterChange(String user, String oldPass, String newPass) throws Exception {
        MockHttpSession s = login(user, oldPass);
        mvc.perform(post("/api/me/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", oldPass, "newPassword", newPass)))).andExpect(status().isNoContent());
        return s;
    }

    @Test
    void creatingAUserForcesAPasswordChangeAndNeverReturnsTheHash() throws Exception {
        String raw = mvc.perform(post("/api/users").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"maria.ops\",\"password\":\"Senha-Provisoria-1\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("Senha-Provisoria-1").doesNotContain("$2").doesNotContain("passwordHash");
        JsonNode u = json.readTree(raw);
        assertThat(u.get("role").asText()).isEqualTo("VIEWER");
        assertThat(u.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(getJson("/api/users").toString()).doesNotContain("$2").doesNotContain("passwordHash");

        MockHttpSession s = login("maria.ops", "Senha-Provisoria-1");
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isForbidden());      // troca obrigatória
        mvc.perform(post("/api/me/password").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"Senha-Provisoria-1\",\"newPassword\":\"Minha-Senha-Nova-9\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isOk());
        mvc.perform(post("/api/devices").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"primaryIp\":\"127.0.83.9\"}")).andExpect(status().isForbidden());
    }

    @Test
    void userCreationValidation() throws Exception {
        postJson("/api/users", Map.of("username", "ab", "password", "Senha-Provisoria-1")).andExpect(status().isBadRequest());
        postJson("/api/users", Map.of("username", "com espaço", "password", "Senha-Provisoria-1")).andExpect(status().isBadRequest());
        postJson("/api/users", Map.of("username", "fraca", "password", "curta")).andExpect(status().isBadRequest());
        postJson("/api/users", Map.of("username", "papel", "password", "Senha-Provisoria-1", "role", "SUPERUSER")).andExpect(status().isBadRequest());
        newUser("duplicado", "VIEWER");
        postJson("/api/users", Map.of("username", "duplicado", "password", "Senha-Provisoria-1")).andExpect(status().isConflict());
        JsonNode defaultRole = read(postJson("/api/users", Map.of("username", "sem-papel", "password", "Senha-Provisoria-1"))
                .andExpect(status().isCreated()));
        assertThat(defaultRole.get("role").asText()).as("o padrão é o menos privilegiado").isEqualTo("VIEWER");
    }

    @Test
    void disablingDemotingOrResettingAUserKillsTheirOpenSessions() throws Exception {
        JsonNode u = newUser("sessao.a", "ADMIN");
        MockHttpSession s = loginAfterChange("sessao.a", "Senha-Provisoria-1", "Senha-Definitiva-123");
        mvc.perform(get("/api/users").session(s)).andExpect(status().isOk());

        // rebaixar para VIEWER derruba a sessão (as autoridades ficam fixas na sessão)
        putJson("/api/users/" + u.get("id").asLong(), Map.of("role", "VIEWER")).andExpect(status().isOk());
        mvc.perform(get("/api/users").session(s)).andExpect(status().isUnauthorized());
        MockHttpSession again = login("sessao.a", "Senha-Definitiva-123");
        mvc.perform(get("/api/users").session(again)).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices").session(again)).andExpect(status().isOk());

        // desativar: a sessão cai e o login deixa de funcionar
        putJson("/api/users/" + u.get("id").asLong(), Map.of("enabled", false)).andExpect(status().isOk());
        mvc.perform(get("/api/devices").session(again)).andExpect(status().isUnauthorized());
        mvc.perform(post("/login").with(csrf()).param("username", "sessao.a").param("password", "Senha-Definitiva-123"))
                .andExpect(status().isUnauthorized());

        // reativar e redefinir a senha: a nova é provisória e a sessão antiga não volta
        putJson("/api/users/" + u.get("id").asLong(), Map.of("enabled", true, "password", "Outra-Senha-Provisoria-7")).andExpect(status().isOk());
        assertThat(read(mvc.perform(get("/api/users").session(admin)).andExpect(status().isOk())).toString()).contains("sessao.a");
        MockHttpSession reset = login("sessao.a", "Outra-Senha-Provisoria-7");
        mvc.perform(get("/api/devices").session(reset)).andExpect(status().isForbidden());
    }

    @Test
    void anAdminCannotChangeDisableOrDeleteThemselves() throws Exception {
        long me = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", Long.class);
        putJson("/api/users/" + me, Map.of("role", "VIEWER")).andExpect(status().isBadRequest());
        putJson("/api/users/" + me, Map.of("enabled", false)).andExpect(status().isBadRequest());
        putJson("/api/users/" + me, Map.of("password", "Outra-Senha-Provisoria-7")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/users/" + me).session(admin).with(csrf())).andExpect(status().isBadRequest());
        putJson("/api/users/" + me, Map.of("role", "ADMIN", "enabled", true)).andExpect(status().isOk());      // sem mudança: ok
    }

    @Test
    void deletingAUserRevokesTheirSessionAndUnknownIdsAre404() throws Exception {
        JsonNode u = newUser("para.apagar", "VIEWER");
        MockHttpSession s = loginAfterChange("para.apagar", "Senha-Provisoria-1", "Senha-Definitiva-123");
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isOk());
        mvc.perform(delete("/api/users/" + u.get("id").asLong()).session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/devices").session(s)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/users/999999").session(admin).with(csrf())).andExpect(status().isNotFound());
        putJson("/api/users/999999", Map.of("enabled", false)).andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsManageUsers() throws Exception {
        MockHttpSession v = viewer();
        mvc.perform(get("/api/users").session(v)).andExpect(status().isForbidden());
        mvc.perform(post("/api/users").session(v).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"invasor\",\"password\":\"Senha-Provisoria-1\",\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------ backup

    private Path backupDir() {
        return Path.of(props.dataDir()).toAbsolutePath().resolve("backups");
    }

    @Test
    void backupIsAConsistentRestorableCopyWithoutStoppingTheService() throws Exception {
        createDevice("no-backup", "127.0.84.1", "SERVER");
        JsonNode b = read(mvc.perform(post("/api/admin/backup").session(admin).with(csrf())).andExpect(status().isCreated()));
        String name = b.get("name").asText();
        assertThat(name).matches("omnidude-\\d{8}-\\d{6}(-\\d+)?\\.db");
        assertThat(b.get("sizeBytes").asLong()).isPositive();
        Path file = backupDir().resolve(name);
        assertThat(file).exists();

        // abre como um banco qualquer (é assim que se restaura) e confere o conteúdo e a integridade
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM devices WHERE name = 'no-backup'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("ok");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM users")) {
                rs.next();
                assertThat(rs.getInt(1)).isPositive();
            }
        }
        // o serviço segue escrevendo depois do backup
        createDevice("depois-do-backup", "127.0.84.2", "SERVER");

        // download: é o próprio arquivo SQLite
        byte[] bytes = mvc.perform(get("/api/admin/backups/" + name).session(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 15, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("SQLite format 3");
        assertThat(getJson("/api/admin/backups").toString()).contains(name);

        mvc.perform(delete("/api/admin/backups/" + name).session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(file).doesNotExist();
        mvc.perform(delete("/api/admin/backups/" + name).session(admin).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void backupNamesCannotEscapeTheBackupDirectory() throws Exception {
        Files.createDirectories(backupDir());
        Files.writeString(backupDir().getParent().resolve("segredo.txt"), "x");
        for (String bad : new String[] {"..%2Fsegredo.txt", "segredo.txt", "omnidude-1.db", "omnidude-20260101-000000.db.sh",
                "omnidude-20260101-000000;rm.db", "..%2F..%2Fetc%2Fpasswd"}) {
            int st = mvc.perform(get("/api/admin/backups/" + bad).session(admin)).andReturn().getResponse().getStatus();
            assertThat(st).as(bad).isBetween(400, 404);
        }
        mvc.perform(get("/api/admin/backups/omnidude-20990101-000000.db").session(admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/backups/segredo.txt").session(admin).with(csrf())).andExpect(status().isNotFound());
        assertThat(backupDir().getParent().resolve("segredo.txt")).exists();
    }

    @Test
    void scheduledBackupRunsOncePerDayAtTheChosenHourAndKeepsOnlyTheLatest() throws Exception {
        try (Stream<Path> old = Files.exists(backupDir()) ? Files.list(backupDir()) : Stream.empty()) {
            old.forEach(f -> f.toFile().delete());
        }
        LocalDateTime now = LocalDateTime.now();
        // desligado: nada
        backups.runScheduled(now);
        assertThat(backups.list()).isEmpty();

        putJson("/api/settings", Map.of("backup.enabled", true, "backup.hour", now.getHour(), "backup.keep", 2)).andExpect(status().isOk());
        backups.runScheduled(now.plusHours(1 + (23 - now.getHour()) % 23 == 0 ? 2 : 1));     // outra hora: nada
        if (now.plusHours(1).getHour() != now.getHour()) {
            assertThat(backups.list()).isEmpty();
        }
        backups.runScheduled(now);
        assertThat(backups.list()).hasSize(1);
        backups.runScheduled(now);
        assertThat(backups.list()).as("já há um das últimas 20 horas").hasSize(1);

        // poda: com keep = 2, o terceiro manual apaga o mais antigo
        backups.create();
        backups.create();
        assertThat(backups.list()).hasSize(3);
        assertThat(backups.prune(2)).isEqualTo(1);
        assertThat(backups.list()).hasSize(2);
    }

    @Test
    void onlyAdminsCanBackUp() throws Exception {
        mvc.perform(post("/api/admin/backup").session(viewer()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/backups").session(viewer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/backups")).andExpect(status().isUnauthorized());
    }
}
