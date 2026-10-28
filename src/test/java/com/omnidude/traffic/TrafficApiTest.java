package com.omnidude.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.metrics.MetricsWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TrafficApiTest extends ApiTestSupport {

    @Autowired TrafficCollector collector;
    @Autowired MetricsWriter writer;

    /** Agente + dispositivo cadastrado, com interfaces já lidas por SNMP (Gi0/1 UP e monitorada, Gi0/2 DOWN). */
    private record Lab(FakeSnmpAgent agent, TestMib mib, long deviceId, long gi1, long gi2) implements AutoCloseable {
        @Override
        public void close() throws java.io.IOException {
            agent.close();
        }
    }

    private Lab lab(String ip) throws Exception {
        TestMib mib = new TestMib()
                .system("Switch", "1.3.6.1.4.1.9.1.716", "sw-" + ip, 1000)
                .iface(1, "Gi0/1", "aa:bb:cc:07:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "aa:bb:cc:07:00:02", 6, 2, 1, 1000);
        mib.setCounters(1, 1_000, 2_000, 0, 0);
        mib.setCounters(2, 0, 0, 0, 0);
        FakeSnmpAgent agent = new FakeSnmpAgent("traffic", ip, 0, mib.build());
        long profile = createProfile("traffic-" + ip, "traffic", agent.port()).get("id").asLong();
        long id = createDevice("traffic-" + ip, ip, "SWITCH").get("id").asLong();
        putJson("/api/devices/" + id, Map.of("name", "traffic-" + ip, "primaryIp", ip, "deviceType", "SWITCH",
                "snmpProfileId", profile)).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + id + "/refresh").session(admin).with(csrf())).andExpect(status().isOk());
        JsonNode ifs = getJson("/api/devices/" + id + "/interfaces");
        return new Lab(agent, mib, id, ifs.get(0).get("id").asLong(), ifs.get(1).get("id").asLong());
    }

    private JsonNode iface(Lab lab, long interfaceId) throws Exception {
        for (JsonNode i : getJson("/api/devices/" + lab.deviceId + "/interfaces")) {
            if (i.get("id").asLong() == interfaceId) {
                return i;
            }
        }
        throw new AssertionError("interface não encontrada");
    }

    @Test
    void ratesComeFromTwoConsecutiveSamples() throws Exception {
        try (Lab lab = lab("127.0.71.2")) {
            assertThat(collector.pollDevice(lab.deviceId)).as("só Gi0/1 é monitorada").isEqualTo(1);
            JsonNode first = iface(lab, lab.gi1);
            assertThat(first.has("rateTs")).isTrue();
            assertThat(first.has("inBps")).as("sem taxa na primeira amostra").isFalse();

            Thread.sleep(1100);
            lab.mib.setCounters(1, 1_000 + 1_000_000, 2_000 + 250_000, 5, 0);       // +8 Mb / +2 Mb em ~1,1 s
            assertThat(collector.pollDevice(lab.deviceId)).isEqualTo(1);
            JsonNode second = iface(lab, lab.gi1);
            assertThat(second.get("inBps").asLong()).isBetween(4_000_000L, 8_000_000L);
            assertThat(second.get("outBps").asLong()).isBetween(1_000_000L, 2_000_000L);
            assertThat(iface(lab, lab.gi2).has("inBps")).as("interface não monitorada não é coletada").isFalse();

            writer.flush();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics WHERE interface_id = ?",
                    Integer.class, lab.gi1)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interface_metrics WHERE interface_id = ? "
                    + "AND in_bps IS NULL", Integer.class, lab.gi1)).as("a primeira amostra grava os octetos, sem taxa")
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT in_errors FROM interface_metrics WHERE interface_id = ? "
                    + "AND in_errors IS NOT NULL", Long.class, lab.gi1)).as("erros: diferença entre amostras").isEqualTo(5L);

            JsonNode series = getJson("/api/interfaces/" + lab.gi1 + "/metrics?range=1h");
            assertThat(series.get("source").asText()).isEqualTo("raw");
            assertThat(series.get("speedBps").asLong()).isEqualTo(1_000_000_000L);
            assertThat(series.get("points")).hasSize(1);
            assertThat(series.get("stats").get("samples").asLong()).isEqualTo(1);
            assertThat(series.get("stats").get("inMax").asLong()).isBetween(4_000_000L, 8_000_000L);
        }
    }

    @Test
    void counterResetDoesNotProduceAFalseRate() throws Exception {
        try (Lab lab = lab("127.0.71.3")) {
            lab.mib.setCounters(1, 50_000_000_000L, 50_000_000_000L, 0, 0);
            collector.pollDevice(lab.deviceId);
            Thread.sleep(1100);
            lab.mib.setCounters(1, 100, 100, 0, 0);                                    // equipamento reiniciou
            collector.pollDevice(lab.deviceId);
            assertThat(iface(lab, lab.gi1).has("inBps")).as("reset ⇒ sem taxa, nunca um valor negativo").isFalse();

            Thread.sleep(1100);
            lab.mib.setCounters(1, 100 + 500_000, 100, 0, 0);
            collector.pollDevice(lab.deviceId);
            assertThat(iface(lab, lab.gi1).get("inBps").asLong()).as("a série se recompõe").isBetween(2_000_000L, 4_000_000L);
        }
    }

    @Test
    void linkCarriesTrafficUtilizationAndOperStatusEvents() throws Exception {
        try (Lab a = lab("127.0.71.4"); Lab b = lab("127.0.71.5")) {
            long link = read(postJson("/api/links", Map.of("sourceDeviceId", a.deviceId, "sourceInterfaceId", a.gi1,
                    "targetDeviceId", b.deviceId, "targetInterfaceId", b.gi1, "label", "uplink"))
                    .andExpect(status().isCreated())).get("id").asLong();

            collector.pollDevice(a.deviceId);
            Thread.sleep(1100);
            a.mib.setCounters(1, 1_000 + 12_500_000, 2_000 + 1_250_000, 0, 0);          // ~100 Mb/s in, ~10 Mb/s out
            collector.pollDevice(a.deviceId);

            JsonNode traffic = linkOf(link).get("traffic");
            boolean aIsSource = a.deviceId < b.deviceId;                                 // enlaces são canônicos (source < target)
            long towardTarget = traffic.get("txBps").asLong();
            long towardSource = traffic.get("rxBps").asLong();
            long aOut = aIsSource ? towardTarget : towardSource;
            long aIn = aIsSource ? towardSource : towardTarget;
            assertThat(aIn).isBetween(70_000_000L, 110_000_000L);
            assertThat(aOut).isBetween(7_000_000L, 11_000_000L);
            assertThat(traffic.get("speedBps").asLong()).isEqualTo(1_000_000_000L);
            assertThat(traffic.get("utilizationPct").asDouble()).as("maior sentido / menor velocidade").isBetween(7.0, 11.0);
            assertThat(traffic.get("operDown").asBoolean()).isFalse();

            // a interface cai: evento LINK_DOWN uma única vez, enlace marcado
            a.mib.setOperStatus(1, 2);
            collector.pollDevice(a.deviceId);
            collector.pollDevice(a.deviceId);
            assertThat(linkOf(link).get("traffic").get("operDown").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE type = 'LINK_DOWN' AND interface_id = ?",
                    Integer.class, a.gi1)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT oper_status FROM interfaces WHERE id = ?", Integer.class, a.gi1))
                    .isEqualTo(2);

            a.mib.setOperStatus(1, 1);
            collector.pollDevice(a.deviceId);
            assertThat(linkOf(link).get("traffic").get("operDown").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE type = 'LINK_UP' AND interface_id = ?",
                    Integer.class, a.gi1)).isEqualTo(1);
        }
    }

    @Test
    void interfaceNotUsedByAnyLinkRaisesNoLinkEvent() throws Exception {
        try (Lab lab = lab("127.0.71.6")) {
            collector.pollDevice(lab.deviceId);
            lab.mib.setOperStatus(1, 2);
            collector.pollDevice(lab.deviceId);
            assertThat(jdbc.queryForObject("SELECT oper_status FROM interfaces WHERE id = ?", Integer.class, lab.gi1))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE interface_id = ?", Integer.class, lab.gi1))
                    .as("porta de acesso comum não gera evento").isZero();
        }
    }

    @Test
    void downOrUnreachableDevicesAreSkipped() throws Exception {
        try (Lab lab = lab("127.0.71.7")) {
            jdbc.update("UPDATE devices SET status = 'DOWN' WHERE id = ?", lab.deviceId);
            assertThat(collector.pollDevice(lab.deviceId)).isZero();
            jdbc.update("UPDATE devices SET status = 'UP', enabled = 0 WHERE id = ?", lab.deviceId);
            assertThat(collector.pollDevice(lab.deviceId)).as("em manutenção").isZero();
            jdbc.update("UPDATE devices SET enabled = 1 WHERE id = ?", lab.deviceId);
            lab.agent.close();                                                          // agente mudo: falha sem lançar
            assertThat(collector.pollDevice(lab.deviceId)).isZero();
            assertThat(collector.pollDevice(987654L)).as("dispositivo inexistente").isZero();
        }
    }

    @Test
    void metricsEndpointValidatesInput() throws Exception {
        try (Lab lab = lab("127.0.71.8")) {
            mvc.perform(get("/api/interfaces/" + lab.gi1 + "/metrics?range=2h").session(admin))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/interfaces/999999/metrics").session(admin)).andExpect(status().isNotFound());
            mvc.perform(get("/api/interfaces/" + lab.gi1 + "/metrics?range=30d").session(admin)).andExpect(status().isOk());
            mvc.perform(get("/api/interfaces/" + lab.gi1 + "/metrics").session(viewer())).andExpect(status().isOk());
            mvc.perform(get("/api/interfaces/" + lab.gi1 + "/metrics")).andExpect(status().is4xxClientError());
        }
    }

    private JsonNode linkOf(long id) throws Exception {
        for (JsonNode l : getJson("/api/links")) {
            if (l.get("id").asLong() == id) {
                return l;
            }
        }
        throw new AssertionError("enlace não encontrado");
    }
}
