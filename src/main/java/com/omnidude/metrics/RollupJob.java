package com.omnidude.metrics;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agrega amostras brutas em linhas horárias, só de horas já fechadas. É idempotente (REPLACE por chave), então
 * reexecutar, ou refazer as últimas horas, nunca duplica nem perde nada. Depois de uma parada longa, retoma da última
 * hora gravada. A retenção só apaga o bruto depois
 * deste job rodar.
 */
@Component
public class RollupJob {

    private static final Logger log = LoggerFactory.getLogger(RollupJob.class);
    private static final long HOUR = 3600;
    /** Horas fechadas refeitas a cada execução, para pegar amostra que chegou atrasada; o atraso real é de ~1 s. */
    private static final long LOOKBACK = 2 * HOUR;

    private final JdbcTemplate jdbc;

    public RollupJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 5 * * * *")
    public void run() {
        rollup(Instant.now().getEpochSecond());
    }

    /** Agrega tudo que ficou pendente até a última hora fechada antes de {@code nowS}. Devolve linhas escritas. */
    public int rollup(long nowS) {
        long closedBefore = nowS / HOUR * HOUR;                  // início da hora corrente: ela ainda está aberta
        int rows = rollupTraffic(closedBefore) + rollupChecks(closedBefore);
        if (rows > 0) {
            log.info("Agregação horária: {} linhas gravadas", rows);
        }
        return rows;
    }

    private int rollupTraffic(long closedBefore) {
        Long last = jdbc.queryForObject("SELECT MAX(hour_ts) FROM interface_metrics_hourly", Long.class);
        long from = last != null ? Math.min(last, closedBefore - LOOKBACK) : firstHour("interface_metrics", closedBefore);
        if (from >= closedBefore) {
            return 0;
        }
        return jdbc.update(
                "INSERT OR REPLACE INTO interface_metrics_hourly(interface_id, hour_ts, avg_in_bps, max_in_bps, "
                        + "avg_out_bps, max_out_bps, samples) "
                        + "SELECT interface_id, (ts / 3600) * 3600, CAST(ROUND(AVG(in_bps)) AS INTEGER), MAX(in_bps), "
                        + "CAST(ROUND(AVG(out_bps)) AS INTEGER), MAX(out_bps), COUNT(*) "
                        + "FROM interface_metrics WHERE ts >= ? AND ts < ? GROUP BY interface_id, (ts / 3600) * 3600",
                from, closedBefore);
    }

    private int rollupChecks(long closedBefore) {
        Long last = jdbc.queryForObject("SELECT MAX(hour_ts) FROM check_results_hourly", Long.class);
        long from = last != null ? Math.min(last, closedBefore - LOOKBACK) : firstHour("check_results", closedBefore);
        if (from >= closedBefore) {
            return 0;
        }
        return jdbc.update(
                "INSERT OR REPLACE INTO check_results_hourly(check_id, hour_ts, ok_count, fail_count, avg_ms, max_ms) "
                        + "SELECT check_id, (ts / 3600) * 3600, SUM(success), COUNT(*) - SUM(success), "
                        + "AVG(CASE WHEN success = 1 THEN response_ms END), MAX(CASE WHEN success = 1 THEN response_ms END) "
                        + "FROM check_results WHERE ts >= ? AND ts < ? GROUP BY check_id, (ts / 3600) * 3600",
                from, closedBefore);
    }

    /** Primeira hora com amostra bruta; sem nenhuma, não há o que agregar. */
    private long firstHour(String table, long closedBefore) {
        Long min = jdbc.queryForObject("SELECT MIN(ts) FROM " + table, Long.class);
        return min == null ? closedBefore : min / HOUR * HOUR;
    }
}
