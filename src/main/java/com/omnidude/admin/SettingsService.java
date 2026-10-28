package com.omnidude.admin;

import com.omnidude.config.OmniDudeProperties;
import com.omnidude.web.ApiException;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ajustes editáveis em tempo de execução, guardados em {@code settings}. O valor padrão vem do {@code application.yml}
 * (e das variáveis de ambiente); um valor gravado o sobrepõe, e apagá-lo volta ao padrão. A leitura é em memória (o
 * processo é único), então consultar a cada segundo, como o coletor de tráfego faz, não toca o banco.
 */
@Service
public class SettingsService {

    public enum Type { INT, BOOL }

    /** Definição de um ajuste. {@code group} agrupa na tela; {@code restart} avisa que só vale depois de reiniciar. */
    public record Definition(String key, String group, String label, String help, Type type, long defaultValue, long min,
                             long max, boolean restart) {
    }

    public record Item(String key, String group, String label, String help, Type type, long value, long defaultValue,
                       long min, long max, boolean custom, boolean restart) {
    }

    public static final String RETENTION_RAW_DAYS = "retention.raw-days";
    public static final String RETENTION_HOURLY_DAYS = "retention.hourly-days";
    public static final String TRAFFIC_INTERVAL_S = "traffic.interval-s";
    public static final String MONITORING_INTERVAL_S = "monitoring.default-interval-s";
    public static final String MONITORING_TIMEOUT_MS = "monitoring.default-timeout-ms";
    public static final String DISCOVERY_MAX_HOSTS = "discovery.max-hosts";
    public static final String DISCOVERY_CONCURRENCY = "discovery.concurrency";
    public static final String DISCOVERY_RATE = "discovery.rate-per-second";
    public static final String NOTIFY_DEDUPE_S = "notifications.dedupe-seconds";
    public static final String MAINTENANCE_ENABLED = "maintenance.enabled";
    public static final String MAINTENANCE_UNTIL = "maintenance.until";
    public static final String IDENTITY_AUTO_UPDATE = "identity.auto-update-ip";
    public static final String IDENTITY_ROUTER_ARP_MIN = "identity.router-arp-minutes";
    public static final String BACKUP_ENABLED = "backup.enabled";
    public static final String BACKUP_HOUR = "backup.hour";
    public static final String BACKUP_KEEP = "backup.keep";

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Long> overrides = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public SettingsService(JdbcTemplate jdbc, OmniDudeProperties p) {
        this.jdbc = jdbc;
        var ret = p.retention();
        var mon = p.monitoring();
        var tr = p.traffic();
        var dis = p.discovery();
        def(RETENTION_RAW_DAYS, "Retenção", "Amostras brutas (dias)", "Depois disso só fica o agregado horário.",
                Type.INT, ret == null ? 7 : ret.rawDays(), 1, 365, false);
        def(RETENTION_HOURLY_DAYS, "Retenção", "Agregado horário (dias)", "Série longa de latência e tráfego.",
                Type.INT, ret == null ? 400 : ret.hourlyDays(), 30, 3650, false);
        def(TRAFFIC_INTERVAL_S, "Monitoramento", "Intervalo da coleta de tráfego (s)", "Por dispositivo; vale já no próximo ciclo.",
                Type.INT, tr == null ? 60 : tr.intervalS(), 15, 3600, false);
        def(MONITORING_INTERVAL_S, "Monitoramento", "Intervalo padrão de uma checagem nova (s)", "Checagens existentes não mudam.",
                Type.INT, mon == null ? 30 : mon.defaultIntervalS(), 5, 3600, false);
        def(MONITORING_TIMEOUT_MS, "Monitoramento", "Timeout padrão de uma checagem nova (ms)", null,
                Type.INT, mon == null ? 2000 : mon.defaultTimeoutMs(), 200, 60_000, false);
        def(DISCOVERY_MAX_HOSTS, "Descoberta", "Faixa que pede confirmação (hosts)", "Acima disso a varredura exige confirmação.",
                Type.INT, dis == null ? 4096 : dis.maxHosts(), 16, 65_536, false);
        def(DISCOVERY_CONCURRENCY, "Descoberta", "Hosts sondados ao mesmo tempo", null,
                Type.INT, dis == null ? 128 : dis.concurrency(), 1, 512, false);
        def(DISCOVERY_RATE, "Descoberta", "Hosts iniciados por segundo", "Limita o pico de pacotes na rede.",
                Type.INT, dis == null ? 200 : dis.ratePerSecond(), 1, 2000, false);
        def(NOTIFY_DEDUPE_S, "Notificações", "Janela anti-repetição (s)",
                "A mesma mensagem não é reenviada ao mesmo canal dentro desta janela. 0 desliga.", Type.INT, 60, 0, 3600, false);
        def(IDENTITY_AUTO_UPDATE, "Identidade (DHCP)", "Atualizar o IP sozinho quando o dispositivo muda de endereço",
                "Só com o dispositivo DOWN e o MAC dele visto em outro IP. Desligado, só avisa.", Type.BOOL, 1, 0, 1, false);
        def(IDENTITY_ROUTER_ARP_MIN, "Identidade (DHCP)", "Ler o ARP dos roteadores a cada (min)",
                "Ajuda a achar o novo IP fora da rede do servidor. 0 desliga.", Type.INT, 10, 0, 120, false);
        def(MAINTENANCE_ENABLED, "Manutenção", "Modo de manutenção", "Suspende todas as notificações; eventos e checagens seguem.",
                Type.BOOL, 0, 0, 1, false);
        def(MAINTENANCE_UNTIL, "Manutenção", "Termina em (epoch, 0 = manual)", "Depois deste instante o modo se desliga sozinho.",
                Type.INT, 0, 0, 4_102_444_800L, false);
        def(BACKUP_ENABLED, "Backup", "Backup diário automático", "VACUUM INTO em backups/, sem parar o serviço.",
                Type.BOOL, 0, 0, 1, false);
        def(BACKUP_HOUR, "Backup", "Hora do backup (0 a 23)", null, Type.INT, 3, 0, 23, false);
        def(BACKUP_KEEP, "Backup", "Backups a manter", "Os mais antigos são apagados.", Type.INT, 7, 1, 90, false);
    }

    private void def(String key, String group, String label, String help, Type type, long dflt, long min, long max,
                     boolean restart) {
        definitions.put(key, new Definition(key, group, label, help, type, dflt, min, max, restart));
    }

    @PostConstruct
    void load() {
        overrides.clear();
        jdbc.query("SELECT setting_key, setting_value FROM settings", rs -> {
            String key = rs.getString(1);
            if (definitions.containsKey(key)) {
                try {
                    overrides.put(key, Long.parseLong(rs.getString(2).trim()));
                } catch (NumberFormatException ignored) {
                    // linha corrompida: vale o padrão
                }
            }
        });
    }

    public long get(String key) {
        Definition d = definitions.get(key);
        if (d == null) {
            throw new IllegalArgumentException("ajuste desconhecido: " + key);
        }
        return overrides.getOrDefault(key, d.defaultValue());
    }

    public int getInt(String key) {
        return (int) get(key);
    }

    public boolean getBool(String key) {
        return get(key) != 0;
    }

    /** Modo de manutenção ligado agora (e ainda dentro do prazo, se houver). */
    public boolean maintenanceActive() {
        if (!getBool(MAINTENANCE_ENABLED)) {
            return false;
        }
        long until = get(MAINTENANCE_UNTIL);
        return until == 0 || Instant.now().getEpochSecond() < until;
    }

    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        for (Definition d : definitions.values()) {
            out.add(new Item(d.key(), d.group(), d.label(), d.help(), d.type(), get(d.key()), d.defaultValue(), d.min(),
                    d.max(), overrides.containsKey(d.key()), d.restart()));
        }
        return out;
    }

    /**
     * Aplica vários ajustes de uma vez, tudo ou nada. {@code null} (ou um valor igual ao padrão) volta ao padrão.
     * Booleanos aceitam true/false.
     */
    @Transactional
    public List<Item> update(Map<String, Object> values) {
        Map<String, Long> parsed = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            Definition d = definitions.get(e.getKey());
            if (d == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Ajuste desconhecido: " + e.getKey());
            }
            parsed.put(e.getKey(), e.getValue() == null ? null : parse(d, e.getValue()));
        }
        long now = Instant.now().getEpochSecond();
        for (var e : parsed.entrySet()) {
            Definition d = definitions.get(e.getKey());
            if (e.getValue() == null || e.getValue() == d.defaultValue()) {
                jdbc.update("DELETE FROM settings WHERE setting_key = ?", e.getKey());
            } else {
                jdbc.update("INSERT INTO settings(setting_key, setting_value, updated_at) VALUES (?,?,?) "
                        + "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                        + "updated_at = excluded.updated_at", e.getKey(), String.valueOf(e.getValue()), now);
            }
        }
        // só depois de gravar tudo, para a memória nunca ficar à frente do banco se algo falhar no meio
        for (var e : parsed.entrySet()) {
            Definition d = definitions.get(e.getKey());
            if (e.getValue() == null || e.getValue() == d.defaultValue()) {
                overrides.remove(e.getKey());
            } else {
                overrides.put(e.getKey(), e.getValue());
            }
        }
        return list();
    }

    private static long parse(Definition d, Object raw) {
        long v;
        try {
            if (d.type() == Type.BOOL) {
                if (raw instanceof Boolean b) {
                    return b ? 1 : 0;
                }
                String s = String.valueOf(raw).trim().toLowerCase();
                if (s.equals("true") || s.equals("1")) {
                    return 1;
                }
                if (s.equals("false") || s.equals("0")) {
                    return 0;
                }
                throw new NumberFormatException();
            }
            v = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Valor inválido para " + d.key());
        }
        if (v < d.min() || v > d.max()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    d.label() + ": o valor deve estar entre " + d.min() + " e " + d.max());
        }
        return v;
    }
}
