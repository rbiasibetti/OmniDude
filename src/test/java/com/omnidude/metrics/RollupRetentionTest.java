package com.omnidude.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RollupRetentionTest extends ApiTestSupport {

    @Autowired RollupJob rollup;
    @Autowired RetentionJob retention;

    private long deviceWithInterface(String ip) throws Exception {
        long id = createDevice("rollup-" + ip, ip, "SWITCH").get("id").asLong();
        jdbc.update("INSERT INTO interfaces(device_id, if_index, name, speed_bps, monitored) VALUES (?, 1, 'Gi0/1', 1000000000, 1)", id);
        return jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id = ?", Long.class, id);
    }

    private void sample(long ifId, long ts, Long inBps, Long outBps) {
        jdbc.update("INSERT OR REPLACE INTO interface_metrics(interface_id, ts, in_octets, out_octets, in_bps, out_bps) "
                + "VALUES (?,?,?,?,?,?)", ifId, ts, 1, 1, inBps, outBps);
    }

    @Test
    void hourlyRollupAveragesClosedHoursOnlyAndIsIdempotent() throws Exception {
        long ifId = deviceWithInterface("127.0.72.2");
        long now = Instant.now().getEpochSecond();
        long hour = now / 3600 * 3600;
        long prev = hour - 3600;
        sample(ifId, prev + 10, 100L, 10L);
        sample(ifId, prev + 70, 300L, 30L);
        sample(ifId, prev + 130, 200L, null);                       // sem taxa de saída: não entra na média de saída
        sample(ifId, prev + 190, null, null);                       // primeira amostra pós-reset: conta em 'samples'
        sample(ifId, hour + 5, 999L, 999L);                         // hora aberta: fora do agregado

        rollup.rollup(now);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM interface_metrics_hourly WHERE interface_id = ? "
                + "AND hour_ts = ?", ifId, prev);
        assertThat(((Number) row.get("avg_in_bps")).longValue()).isEqualTo(200);
        assertThat(((Number) row.get("max_in_bps")).longValue()).isEqualTo(300);
        assertThat(((Number) row.get("avg_out_bps")).longValue()).isEqualTo(20);
        assertThat(((Number) row.get("max_out_bps")).longValue()).isEqualTo(30);
        assertThat(((Number) row.get("samples")).longValue()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics_hourly WHERE interface_id = ? AND hour_ts = ?",
                Integer.class, ifId, hour)).as("a hora aberta ainda não é agregada").isZero();

        rollup.rollup(now);                                         // idempotente
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics_hourly WHERE interface_id = ?",
                Integer.class, ifId)).isEqualTo(1);

        sample(ifId, prev + 250, 600L, 60L);                        // amostra atrasada numa hora já agregada
        rollup.rollup(now);
        Map<String, Object> redone = jdbc.queryForMap("SELECT * FROM interface_metrics_hourly WHERE interface_id = ? "
                + "AND hour_ts = ?", ifId, prev);
        assertThat(((Number) redone.get("max_in_bps")).longValue()).isEqualTo(600);
        assertThat(((Number) redone.get("samples")).longValue()).isEqualTo(5);
    }

    @Test
    void hourlyAggregateOfCheckResults() throws Exception {
        long deviceId = createDevice("rollup-checks", "127.0.72.3", "SERVER").get("id").asLong();
        long checkId = read(postJson("/api/devices/" + deviceId + "/checks", Map.of("type", "TCP", "port", 80))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()))
                .get("id").asLong();
        long now = Instant.now().getEpochSecond();
        long prev = now / 3600 * 3600 - 3600;
        jdbc.update("INSERT OR REPLACE INTO check_results(check_id, ts, success, response_ms) VALUES (?,?,1,10),(?,?,1,30),(?,?,0,NULL)",
                checkId, prev + 5, checkId, prev + 35, checkId, prev + 65);

        rollup.rollup(now);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM check_results_hourly WHERE check_id = ? AND hour_ts = ?",
                checkId, prev);
        assertThat(((Number) row.get("ok_count")).intValue()).isEqualTo(2);
        assertThat(((Number) row.get("fail_count")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("avg_ms")).doubleValue()).isEqualTo(20.0);
        assertThat(((Number) row.get("max_ms")).intValue()).isEqualTo(30);

        // além de 7 dias o gráfico de latência usa o agregado
        JsonNode series = getJson("/api/checks/" + checkId + "/results?range=30d");
        assertThat(series.get("stats").get("samples").asLong()).isEqualTo(3);
        assertThat(series.get("stats").get("availabilityPct").asDouble()).isEqualTo(66.7);
        assertThat(series.get("stats").get("avgMs").asDouble()).isEqualTo(20.0);
        assertThat(series.get("points")).isNotEmpty();
    }

    @Test
    void thirtyDayTrafficSeriesReadsTheHourlyTable() throws Exception {
        long ifId = deviceWithInterface("127.0.72.4");
        long now = Instant.now().getEpochSecond();
        long hour = now / 3600 * 3600;
        jdbc.update("INSERT INTO interface_metrics_hourly VALUES (?,?,?,?,?,?,?)", ifId, hour - 7200, 100, 400, 10, 40, 60);
        jdbc.update("INSERT INTO interface_metrics_hourly VALUES (?,?,?,?,?,?,?)", ifId, hour - 3600, 300, 900, 30, 90, 20);

        JsonNode series = getJson("/api/interfaces/" + ifId + "/metrics?range=30d");
        assertThat(series.get("source").asText()).isEqualTo("hourly");
        JsonNode stats = series.get("stats");
        assertThat(stats.get("samples").asLong()).isEqualTo(80);
        assertThat(stats.get("inAvg").asLong()).as("média ponderada pelas amostras: (100·60+300·20)/80").isEqualTo(150);
        assertThat(stats.get("inMax").asLong()).isEqualTo(900);
        assertThat(stats.get("outAvg").asLong()).isEqualTo(15);
        assertThat(series.get("points")).isNotEmpty();
    }

    @Test
    void retentionKeepsAggregatesAndDropsOldRawData() throws Exception {
        long ifId = deviceWithInterface("127.0.72.5");
        long now = Instant.now().getEpochSecond();
        sample(ifId, now - 8 * 86_400L, 1L, 1L);                    // além dos 7 dias
        sample(ifId, now - 86_400L, 2L, 2L);
        jdbc.update("INSERT INTO interface_metrics_hourly VALUES (?,?,1,1,1,1,1)", ifId, (now - 401 * 86_400L) / 3600 * 3600);
        jdbc.update("INSERT INTO interface_metrics_hourly VALUES (?,?,1,1,1,1,1)", ifId, (now - 30 * 86_400L) / 3600 * 3600);

        retention.run(now);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics WHERE interface_id = ?", Integer.class, ifId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT in_bps FROM interface_metrics WHERE interface_id = ?", Long.class, ifId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics_hourly WHERE interface_id = ?", Integer.class, ifId))
                .as("o agregado de 401 dias some, o de 30 dias fica").isEqualTo(1);
    }
}
