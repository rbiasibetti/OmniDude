package com.omnidude.monitoring;

import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Série de latência de uma checagem, agregada em no máximo ~240 pontos para o gráfico. */
@Service
public class ResultsService {

    public record Point(long ts, long ok, long total, Double avgMs, Integer maxMs) {
    }

    public record Stats(long samples, long okSamples, Double availabilityPct, Double avgMs, Integer minMs, Integer maxMs) {
    }

    public record Series(long from, long to, long bucketS, List<Point> points, Stats stats) {
    }

    private static final Map<String, Long> RANGES = Map.of("1h", 3600L, "6h", 6 * 3600L, "24h", 86400L, "7d", 7 * 86400L, "30d", 30 * 86400L);
    private static final int TARGET_POINTS = 240;
    private static final long RAW_LIMIT_S = 7 * 86400L;

    private final JdbcTemplate jdbc;
    private final ServiceCheckRepository checks;

    public ResultsService(JdbcTemplate jdbc, ServiceCheckRepository checks) {
        this.jdbc = jdbc;
        this.checks = checks;
    }

    public Series series(long checkId, String range) {
        Long seconds = RANGES.get(range);
        if (seconds == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Intervalo deve ser 1h, 6h, 24h, 7d ou 30d");
        }
        ServiceCheck check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Checagem não encontrada"));
        long to = Instant.now().getEpochSecond();
        long from = to - seconds;
        if (seconds > RAW_LIMIT_S) {
            return hourlySeries(checkId, from, to, seconds);
        }
        // Balde nunca menor que o intervalo da checagem, senão o gráfico ficaria cheio de buracos.
        long bucket = Math.max(Math.max(1, seconds / TARGET_POINTS), check.getIntervalS());

        List<Point> points = jdbc.query(
                "SELECT (ts / ?) * ? AS t, SUM(success) AS ok, COUNT(*) AS total, "
                        + "AVG(CASE WHEN success = 1 THEN response_ms END) AS avg_ms, "
                        + "MAX(CASE WHEN success = 1 THEN response_ms END) AS max_ms "
                        + "FROM check_results WHERE check_id = ? AND ts >= ? AND ts <= ? GROUP BY t ORDER BY t",
                (rs, i) -> new Point(rs.getLong("t"), rs.getLong("ok"), rs.getLong("total"),
                        rs.getObject("avg_ms") == null ? null : round1(rs.getDouble("avg_ms")),
                        rs.getObject("max_ms") == null ? null : rs.getInt("max_ms")),
                bucket, bucket, checkId, from, to);

        Stats stats = jdbc.queryForObject(
                "SELECT COUNT(*), COALESCE(SUM(success), 0), "
                        + "AVG(CASE WHEN success = 1 THEN response_ms END), "
                        + "MIN(CASE WHEN success = 1 THEN response_ms END), "
                        + "MAX(CASE WHEN success = 1 THEN response_ms END) "
                        + "FROM check_results WHERE check_id = ? AND ts >= ? AND ts <= ?",
                (rs, i) -> {
                    long total = rs.getLong(1);
                    long ok = rs.getLong(2);
                    return new Stats(total, ok, total == 0 ? null : round1(100.0 * ok / total),
                            rs.getObject(3) == null ? null : round1(rs.getDouble(3)),
                            rs.getObject(4) == null ? null : rs.getInt(4),
                            rs.getObject(5) == null ? null : rs.getInt(5));
                }, checkId, from, to);
        return new Series(from, to, bucket, points, stats);
    }

    /** Acima da retenção do bruto (7 d) só o agregado horário existe: sem mínimo, e atrasado em até uma hora. */
    private Series hourlySeries(long checkId, long from, long to, long seconds) {
        long bucket = Math.max(3600, (seconds / TARGET_POINTS) / 3600 * 3600);
        String avg = "SUM(avg_ms * ok_count) * 1.0 / NULLIF(SUM(CASE WHEN avg_ms IS NOT NULL THEN ok_count END), 0)";
        List<Point> points = jdbc.query(
                "SELECT (hour_ts / ?) * ? AS t, SUM(ok_count) AS ok, SUM(ok_count + fail_count) AS total, "
                        + avg + " AS avg_ms, MAX(max_ms) AS max_ms FROM check_results_hourly "
                        + "WHERE check_id = ? AND hour_ts >= ? AND hour_ts <= ? GROUP BY t ORDER BY t",
                (rs, i) -> new Point(rs.getLong("t"), rs.getLong("ok"), rs.getLong("total"),
                        rs.getObject("avg_ms") == null ? null : round1(rs.getDouble("avg_ms")),
                        rs.getObject("max_ms") == null ? null : rs.getInt("max_ms")),
                bucket, bucket, checkId, from, to);
        Stats stats = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ok_count + fail_count), 0), COALESCE(SUM(ok_count), 0), " + avg
                        + ", MAX(max_ms) FROM check_results_hourly WHERE check_id = ? AND hour_ts >= ? AND hour_ts <= ?",
                (rs, i) -> {
                    long total = rs.getLong(1);
                    long ok = rs.getLong(2);
                    return new Stats(total, ok, total == 0 ? null : round1(100.0 * ok / total),
                            rs.getObject(3) == null ? null : round1(rs.getDouble(3)), null,
                            rs.getObject(4) == null ? null : rs.getInt(4));
                }, checkId, from, to);
        return new Series(from, to, bucket, points, stats);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
