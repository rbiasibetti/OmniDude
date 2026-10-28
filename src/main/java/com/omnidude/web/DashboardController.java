package com.omnidude.web;

import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.event.EventService;
import com.omnidude.event.EventView;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    /** Só considera latências medidas recentemente; um valor de uma hora atrás não é "maior latência agora". */
    private static final long FRESH_SECONDS = 15 * 60;

    public record Problem(Long deviceId, String name, String ip, Status status, Long since) {
    }

    public record Slow(Long deviceId, String device, String check, String type, int ms, String status) {
    }

    public record Dashboard(Map<Status, Long> devices, Map<String, Long> checks, long totalDevices, long pausedDevices,
                            long unackedEvents, List<Problem> problems, List<Slow> slowest, List<EventView> events) {
    }

    private final DeviceRepository devices;
    private final EventService events;
    private final JdbcTemplate jdbc;

    public DashboardController(DeviceRepository devices, EventService events, JdbcTemplate jdbc) {
        this.devices = devices;
        this.events = events;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/dashboard")
    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0L);
        }
        devices.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getTotal()));

        Map<String, Long> checkCounts = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            checkCounts.put(s.name(), 0L);
        }
        jdbc.query("SELECT c.status, count(*) FROM service_checks c JOIN devices d ON d.id = c.device_id "
                + "WHERE c.enabled = 1 AND d.enabled = 1 GROUP BY c.status",
                rs -> { checkCounts.put(rs.getString(1), rs.getLong(2)); });

        long total = devices.countByVirtualNodeFalse();
        long enabled = counts.values().stream().mapToLong(Long::longValue).sum();

        List<Problem> problems = jdbc.query(
                "SELECT id, name, primary_ip, status, status_since FROM devices "
                        + "WHERE enabled = 1 AND status IN ('DOWN', 'WARNING') "
                        + "ORDER BY CASE status WHEN 'DOWN' THEN 0 ELSE 1 END, status_since ASC LIMIT 10",
                (rs, i) -> new Problem(rs.getLong(1), rs.getString(2), rs.getString(3), Status.valueOf(rs.getString(4)),
                        rs.getObject(5) == null ? null : rs.getLong(5)));

        List<Slow> slowest = jdbc.query(
                "SELECT d.id, d.name, c.name, c.type, c.last_response_ms, c.status FROM service_checks c "
                        + "JOIN devices d ON d.id = c.device_id "
                        + "WHERE c.enabled = 1 AND d.enabled = 1 AND c.status IN ('UP', 'WARNING') "
                        + "AND c.last_response_ms IS NOT NULL AND c.last_check_at >= ? "
                        + "ORDER BY c.last_response_ms DESC LIMIT 5",
                (rs, i) -> new Slow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6)),
                Instant.now().getEpochSecond() - FRESH_SECONDS);

        return new Dashboard(counts, checkCounts, total, total - enabled, events.unacknowledgedCount(), problems,
                slowest, events.search(null, false, null, 8));
    }
}
