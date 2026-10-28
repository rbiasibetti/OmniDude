package com.omnidude.admin;

import com.omnidude.config.OmniDudeProperties;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.Severity;
import com.omnidude.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Backup do banco com {@code VACUUM INTO}: uma cópia consistente e compacta, feita com o serviço no ar (o SQLite lê um
 * instantâneo; as escritas seguem). Cada cópia é aberta e conferida ({@code PRAGMA integrity_check}) antes de valer.
 * Os segredos (SNMP, canais) ficam criptografados no banco: a cópia só os abre com a mesma chave
 * ({@code OMNIDUDE_SECRET_KEY} ou {@code secret.key}), que NÃO vai no backup.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final Pattern NAME = Pattern.compile("omnidude-\\d{8}-\\d{6}(-\\d+)?\\.db");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record BackupInfo(String name, long sizeBytes, long createdAt) {
    }

    private final Path dir;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final EventService events;

    public BackupService(OmniDudeProperties props, JdbcTemplate jdbc, SettingsService settings, EventService events) {
        this.dir = Path.of(props.dataDir()).toAbsolutePath().resolve("backups");
        this.jdbc = jdbc;
        this.settings = settings;
        this.events = events;
    }

    /** Cria e confere um backup. Se a conferência falhar, a cópia é apagada e o erro sobe. */
    public synchronized BackupInfo create() {
        try {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path target = uniqueTarget();
            jdbc.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
            try {
                verify(target);
            } catch (SQLException | IOException e) {
                Files.deleteIfExists(target);
                throw new IOException("a cópia não passou na conferência de integridade: " + e.getMessage(), e);
            }
            restrict(target, "rw-------");
            log.info("Backup criado: {} ({} bytes)", target.getFileName(), Files.size(target));
            return info(target);
        } catch (IOException | RuntimeException e) {
            log.error("Falha no backup: {}", e.toString());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Falha ao criar o backup: " + e.getMessage());
        }
    }

    private Path uniqueTarget() {
        String base = "omnidude-" + LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(base + ".db");
        for (int i = 1; Files.exists(p); i++) {
            p = dir.resolve(base + "-" + i + ".db");
        }
        return p;
    }

    /** Abre a cópia como um banco qualquer e roda a conferência de integridade. */
    static void verify(Path file) throws SQLException, IOException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new IOException("integrity_check: " + (rs.getRow() > 0 ? rs.getString(1) : "sem resposta"));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM flyway_schema_history")) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    throw new IOException("a cópia não tem o histórico de migrações");
                }
            }
        }
    }

    public List<BackupInfo> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> NAME.matcher(f.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .map(BackupService::info).toList();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível listar os backups");
        }
    }

    /** O arquivo de um backup pelo nome; nome fora do padrão ou inexistente é 404 (nunca sai de {@code backups/}). */
    public Path file(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Backup não encontrado");
        }
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Backup não encontrado");
        }
        return p;
    }

    public void delete(String name) {
        try {
            Files.delete(file(name));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível apagar o backup");
        }
    }

    /** Mantém só os {@code keep} mais recentes. Devolve quantos apagou. */
    public int prune(int keep) {
        List<BackupInfo> all = list();
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            try {
                Files.deleteIfExists(dir.resolve(all.get(i).name()));
                removed++;
            } catch (IOException e) {
                log.warn("Não foi possível apagar o backup antigo {}", all.get(i).name());
            }
        }
        return removed;
    }

    /** Todo início de hora: se o backup automático está ligado e é a hora escolhida, faz um (no máximo um por dia). */
    @Scheduled(cron = "0 0 * * * *")
    public void scheduled() {
        runScheduled(LocalDateTime.now());
    }

    void runScheduled(LocalDateTime now) {
        if (!settings.getBool(SettingsService.BACKUP_ENABLED) || now.getHour() != settings.getInt(SettingsService.BACKUP_HOUR)) {
            return;
        }
        long cutoff = now.atZone(ZoneId.systemDefault()).toEpochSecond() - 20 * 3600L;
        if (list().stream().anyMatch(b -> b.createdAt() > cutoff)) {
            return;                                               // já há um das últimas 20 horas (manual ou de uma rodada anterior)
        }
        try {
            create();
            prune(settings.getInt(SettingsService.BACKUP_KEEP));
        } catch (RuntimeException e) {
            events.publish(events.record(Severity.WARNING, EventType.SYSTEM, null, null, "O backup automático falhou: " + e.getMessage()));
        }
    }

    private static void restrict(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // sistema sem permissões POSIX (Windows): fica como está
        }
    }

    private static BackupInfo info(Path f) {
        try {
            return new BackupInfo(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toInstant().getEpochSecond());
        } catch (IOException e) {
            return new BackupInfo(f.getFileName().toString(), 0, 0);
        }
    }
}
