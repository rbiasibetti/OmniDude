package com.omnidude.traffic;

import com.omnidude.admin.SettingsService;
import com.omnidude.device.NetInterface;
import com.omnidude.device.NetInterfaceRepository;
import com.omnidude.topology.Link;
import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Última taxa conhecida de cada interface (em memória, alimentada pelo coletor), taxas derivadas para enlaces e séries
 * históricas para os gráficos. Só leituras recentes valem: uma taxa velha vira "sem dado", nunca "zero".
 */
@Service
public class TrafficService {

    /** Taxas em bits/s. */
    public record Latest(long ts, Long inBps, Long outBps, Long inErrors, Long outErrors) {
    }

    /**
     * @param txBps          origem → destino; @param rxBps destino → origem
     * @param utilizationPct maior sentido sobre a menor velocidade das duas pontas
     * @param operDown       alguma interface do enlace está com ifOperStatus = down
     */
    public record LinkTraffic(Long txBps, Long rxBps, Long speedBps, Double utilizationPct, boolean operDown) {
    }

    public record Point(long ts, Long inAvg, Long inMax, Long outAvg, Long outMax) {
    }

    public record Stats(long samples, Long inAvg, Long inMax, Long outAvg, Long outMax) {
    }

    /** {@code source}: "raw" (amostras brutas) ou "hourly" (agregado horário). */
    public record Series(long from, long to, long bucketS, String source, Long speedBps, List<Point> points,
                         Stats stats) {
    }

    private static final Map<String, Long> RAW_RANGES = Map.of("1h", 3600L, "6h", 6 * 3600L, "24h", 86_400L,
            "7d", 7 * 86_400L);
    private static final Map<String, Long> HOURLY_RANGES = Map.of("30d", 30 * 86_400L, "90d", 90 * 86_400L);
    private static final int TARGET_POINTS = 240;

    private final Map<Long, Latest> latest = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final NetInterfaceRepository interfaces;
    private final SettingsService settings;

    public TrafficService(JdbcTemplate jdbc, NetInterfaceRepository interfaces, SettingsService settings) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.interfaces = interfaces;
    }

    public void record(long interfaceId, Latest value) {
        latest.put(interfaceId, value);
    }

    public void forget(long interfaceId) {
        latest.remove(interfaceId);
    }

    /** Leitura recente da interface, ou {@code null} se nunca houve taxa ou ela está velha. */
    public Latest fresh(Long interfaceId) {
        if (interfaceId == null) {
            return null;
        }
        Latest l = latest.get(interfaceId);
        return l != null && Instant.now().getEpochSecond() - l.ts() <= 3L * settings.getInt(SettingsService.TRAFFIC_INTERVAL_S) ? l : null;
    }

    /** Taxa e utilização de um enlace a partir das interfaces das suas pontas; {@code null} sem interface nem dado. */
    public LinkTraffic linkTraffic(Link link, Map<Long, NetInterface> byId) {
        NetInterface src = link.getSourceInterfaceId() == null ? null : byId.get(link.getSourceInterfaceId());
        NetInterface dst = link.getTargetInterfaceId() == null ? null : byId.get(link.getTargetInterfaceId());
        if (src == null && dst == null) {
            return null;
        }
        boolean operDown = isDown(src) || isDown(dst);
        Latest s = src == null ? null : fresh(src.getId());
        Latest d = dst == null ? null : fresh(dst.getId());
        Long tx = null;
        Long rx = null;
        if (s != null && (s.inBps() != null || s.outBps() != null)) {
            tx = s.outBps();
            rx = s.inBps();
        } else if (d != null) {
            tx = d.inBps();
            rx = d.outBps();
        }
        Long speed = minSpeed(src, dst);
        Double pct = null;
        if (speed != null && (tx != null || rx != null)) {
            long peak = Math.max(tx == null ? 0 : tx, rx == null ? 0 : rx);
            pct = Math.round(1000.0 * peak / speed) / 10.0;
        }
        if (tx == null && rx == null && !operDown && speed == null) {
            return null;
        }
        return new LinkTraffic(tx, rx, speed, pct, operDown);
    }

    private static boolean isDown(NetInterface i) {
        return i != null && Integer.valueOf(2).equals(i.getOperStatus());
    }

    private static Long minSpeed(NetInterface a, NetInterface b) {
        Long sa = a == null || a.getSpeedBps() == null || a.getSpeedBps() <= 0 ? null : a.getSpeedBps();
        Long sb = b == null || b.getSpeedBps() == null || b.getSpeedBps() <= 0 ? null : b.getSpeedBps();
        if (sa == null) {
            return sb;
        }
        return sb == null ? sa : Math.min(sa, sb);
    }

    // --- séries ------------------------------------------------------------------------------------------------

    public Series series(long interfaceId, String range) {
        NetInterface iface = interfaces.findById(interfaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interface não encontrada"));
        long to = Instant.now().getEpochSecond();
        Long rawSeconds = RAW_RANGES.get(range);
        if (rawSeconds != null) {
            return rawSeries(iface, to - rawSeconds, to, rawSeconds);
        }
        Long hourlySeconds = HOURLY_RANGES.get(range);
        if (hourlySeconds != null) {
            return hourlySeries(iface, to - hourlySeconds, to, hourlySeconds);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Intervalo deve ser 1h, 6h, 24h, 7d, 30d ou 90d");
    }

    private Series rawSeries(NetInterface iface, long from, long to, long seconds) {
        long bucket = Math.max(60, seconds / TARGET_POINTS);
        List<Point> points = jdbc.query(
                "SELECT (ts / ?) * ? AS t, AVG(in_bps) AS in_avg, MAX(in_bps) AS in_max, "
                        + "AVG(out_bps) AS out_avg, MAX(out_bps) AS out_max "
                        + "FROM interface_metrics WHERE interface_id = ? AND ts >= ? AND ts <= ? "
                        + "AND (in_bps IS NOT NULL OR out_bps IS NOT NULL) GROUP BY t ORDER BY t",
                (rs, i) -> new Point(rs.getLong("t"), avg(rs.getObject("in_avg")), max(rs.getObject("in_max")),
                        avg(rs.getObject("out_avg")), max(rs.getObject("out_max"))),
                bucket, bucket, iface.getId(), from, to);
        Stats stats = jdbc.queryForObject(
                "SELECT COUNT(in_bps), AVG(in_bps), MAX(in_bps), AVG(out_bps), MAX(out_bps) FROM interface_metrics "
                        + "WHERE interface_id = ? AND ts >= ? AND ts <= ?",
                (rs, i) -> new Stats(rs.getLong(1), avg(rs.getObject(2)), max(rs.getObject(3)), avg(rs.getObject(4)),
                        max(rs.getObject(5))),
                iface.getId(), from, to);
        return new Series(from, to, bucket, "raw", iface.getSpeedBps(), points, stats);
    }

    /** Média ponderada pelo número de amostras de cada hora; máximo é o maior dos máximos. */
    private Series hourlySeries(NetInterface iface, long from, long to, long seconds) {
        long bucket = Math.max(3600, (seconds / TARGET_POINTS) / 3600 * 3600);
        List<Point> points = jdbc.query(
                "SELECT (hour_ts / ?) * ? AS t, "
                        + "SUM(avg_in_bps * samples) * 1.0 / NULLIF(SUM(CASE WHEN avg_in_bps IS NOT NULL THEN samples END), 0) AS in_avg, "
                        + "MAX(max_in_bps) AS in_max, "
                        + "SUM(avg_out_bps * samples) * 1.0 / NULLIF(SUM(CASE WHEN avg_out_bps IS NOT NULL THEN samples END), 0) AS out_avg, "
                        + "MAX(max_out_bps) AS out_max "
                        + "FROM interface_metrics_hourly WHERE interface_id = ? AND hour_ts >= ? AND hour_ts <= ? "
                        + "GROUP BY t ORDER BY t",
                (rs, i) -> new Point(rs.getLong("t"), avg(rs.getObject("in_avg")), max(rs.getObject("in_max")),
                        avg(rs.getObject("out_avg")), max(rs.getObject("out_max"))),
                bucket, bucket, iface.getId(), from, to);
        Stats stats = jdbc.queryForObject(
                "SELECT COALESCE(SUM(samples), 0), "
                        + "SUM(avg_in_bps * samples) * 1.0 / NULLIF(SUM(CASE WHEN avg_in_bps IS NOT NULL THEN samples END), 0), "
                        + "MAX(max_in_bps), "
                        + "SUM(avg_out_bps * samples) * 1.0 / NULLIF(SUM(CASE WHEN avg_out_bps IS NOT NULL THEN samples END), 0), "
                        + "MAX(max_out_bps) FROM interface_metrics_hourly "
                        + "WHERE interface_id = ? AND hour_ts >= ? AND hour_ts <= ?",
                (rs, i) -> new Stats(rs.getLong(1), avg(rs.getObject(2)), max(rs.getObject(3)), avg(rs.getObject(4)),
                        max(rs.getObject(5))),
                iface.getId(), from, to);
        return new Series(from, to, bucket, "hourly", iface.getSpeedBps(), points, stats);
    }

    private static Long avg(Object v) {
        return v == null ? null : Math.round(((Number) v).doubleValue());
    }

    private static Long max(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
