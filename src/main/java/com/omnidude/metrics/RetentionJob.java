package com.omnidude.metrics;

import com.omnidude.admin.SettingsService;
import com.omnidude.config.OmniDudeProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Limpeza diária: agrega o que falta, depois apaga o bruto vencido, o agregado antigo e os eventos reconhecidos. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final long EVENT_DAYS = 180;
    private static final long NOTIFICATION_LOG_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final OmniDudeProperties props;
    private final RollupJob rollup;
    private final SettingsService settings;

    public RetentionJob(JdbcTemplate jdbc, OmniDudeProperties props, RollupJob rollup, SettingsService settings) {
        this.jdbc = jdbc;
        this.props = props;
        this.rollup = rollup;
        this.settings = settings;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        run(Instant.now().getEpochSecond());
    }

    void run(long now) {
        rollup.rollup(now);                       // nunca apagar bruto que ainda não virou agregado
        long rawLimit = now - settings.getInt(SettingsService.RETENTION_RAW_DAYS) * 86_400L;
        long hourlyLimit = now - settings.getInt(SettingsService.RETENTION_HOURLY_DAYS) * 86_400L;
        int results = jdbc.update("DELETE FROM check_results WHERE ts < ?", rawLimit);
        int traffic = jdbc.update("DELETE FROM interface_metrics WHERE ts < ?", rawLimit);
        int hourly = jdbc.update("DELETE FROM check_results_hourly WHERE hour_ts < ?", hourlyLimit)
                + jdbc.update("DELETE FROM interface_metrics_hourly WHERE hour_ts < ?", hourlyLimit);
        int events = jdbc.update("DELETE FROM events WHERE acknowledged_at IS NOT NULL AND ts < ?",
                now - EVENT_DAYS * 86_400L);
        jdbc.update("DELETE FROM notification_log WHERE ts < ?", now - NOTIFICATION_LOG_DAYS * 86_400L);
        log.info("Retenção: {} resultados, {} amostras de tráfego, {} linhas horárias e {} eventos removidos", results,
                traffic, hourly, events);
        try {
            jdbc.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (RuntimeException e) {
            log.warn("wal_checkpoint falhou: {}", e.toString());
        }
    }
}
