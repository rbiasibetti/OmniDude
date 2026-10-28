package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.monitoring.probe.ExternalPingProbe;
import com.omnidude.monitoring.probe.Target;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class DiscoveryApiTest extends ApiTestSupport {

    @Autowired DiscoveryService discovery;

    // ------------------------------------------------------------------------------------ helpers

    private Map<String, Object> request(String cidr, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("cidr", cidr);
        m.put("authorized", true);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode awaitFinished(long jobId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode job = getJson("/api/discovery/jobs/" + jobId);
            String st = job.get("status").asText();
            if (!st.equals("PENDING") && !st.equals("RUNNING")) {
                return job;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("a descoberta " + jobId + " não terminou em 60 s");
    }

    private JsonNode hostAt(JsonNode hosts, String ip) {
        for (JsonNode h : hosts) {
            if (h.get("ip").asText().equals(ip)) {
                return h;
            }
        }
        throw new AssertionError("host " + ip + " não está no resultado: " + hosts);
    }

    private ServerSocket listen(String ip) throws IOException {
        try {
            return new ServerSocket(0, 50, InetAddress.getByName(ip));
        } catch (IOException e) {
            assumeTrue(false, "loopback " + ip + " indisponível neste SO");
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------ política e validação

    @Test
    void requestValidationAndSafetyPolicy() throws Exception {
        postJson("/api/discovery/jobs", Map.of("cidr", "127.0.61.0/29")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("autorização")));
        postJson("/api/discovery/jobs", Map.of("cidr", "127.0.61.0/29", "authorized", false)).andExpect(status().isBadRequest());
        for (String bad : new String[] {"não é cidr", "10.0.0.0/33", "2001:db8::/64", "10.0.0.0/24; ls"}) {
            postJson("/api/discovery/jobs", request(bad)).andExpect(status().isBadRequest());
        }
        postJson("/api/discovery/jobs", request("8.8.8.0/29")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Faixa não permitida")));
        postJson("/api/discovery/jobs", request("0.0.0.0/0")).andExpect(status().isBadRequest());

        postJson("/api/discovery/jobs", request("10.0.0.0/16")).andExpect(status().isBadRequest())      // 65534 > 4096
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("varredura grande")));
        postJson("/api/discovery/jobs", request("10.0.0.0/15", "confirmLarge", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("máximo absoluto")));

        postJson("/api/discovery/jobs", request("127.0.61.0/29", "ports", List.of(0))).andExpect(status().isBadRequest());
        postJson("/api/discovery/jobs", request("127.0.61.0/29", "ports", List.of(70000))).andExpect(status().isBadRequest());
        postJson("/api/discovery/jobs", request("127.0.61.0/29", "snmpProfileIds", List.of(999999)))
                .andExpect(status().isBadRequest());
        assertThat(getJson("/api/discovery/jobs").toString()).doesNotContain("127.0.61.0");   // nada foi criado
    }

    @Test
    void viewerCanReadButNotStartCancelOrImport() throws Exception {
        var viewer = viewer();
        mvc.perform(get("/api/discovery/jobs").session(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/discovery/jobs").session(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cidr\":\"127.0.62.0/29\",\"authorized\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/discovery/jobs/1/cancel").session(viewer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/discovery/jobs/1/import").session(viewer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"hosts\":[{\"hostId\":1}]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/topology/refresh").session(viewer).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void importValidationAndUnknownJobs() throws Exception {
        mvc.perform(get("/api/discovery/jobs/999999").session(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/discovery/jobs/999999/hosts").session(admin)).andExpect(status().isNotFound());
        postJson("/api/discovery/jobs/999999/import", Map.of("hosts", List.of(Map.of("hostId", 1))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/discovery/jobs/999999/cancel").session(admin).with(csrf())).andExpect(status().isNotFound());
        postJson("/api/discovery/jobs/1/import", Map.of("hosts", List.of())).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------ o fluxo completo

    @Test
    void scanReviewImportAndLldpTopologyEndToEnd() throws Exception {
        assumeTrue(new ExternalPingProbe().execute(new Target("127.0.60.1", null, 1000)).success(), "ping indisponível");
        int snmpPort = freeUdpPort();
        String ipA = "127.0.60.2";
        String ipTcp = "127.0.60.3";
        String ipB = "127.0.60.4";
        TestMib mibA = new TestMib()
                .system("Cisco IOS Software, C2960 Switch", "1.3.6.1.4.1.9.1.716", "sw-a", 5000)
                .iface(1, "Gi0/1", "aa:bb:cc:aa:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "aa:bb:cc:aa:00:02", 6, 2, 1, 100)
                .iface(24, "Lo0", null, 24, 1, 1, 0)
                .lldpNeighbor(1, 1, "aa:bb:cc:bb:00:01", "Gi0/1", "Gi0/1", "sw-b", ipB);
        TestMib mibB = new TestMib()
                .system("Linux sw-b 5.10.0 #1 SMP x86_64", "1.3.6.1.4.1.8072.3.2.10", "sw-b", 7000)
                .iface(1, "Gi0/1", "aa:bb:cc:bb:00:01", 6, 1, 1, 1000)
                .lldpNeighbor(1, 1, "aa:bb:cc:aa:00:01", "Gi0/1", "Gi0/1", "sw-a", ipA);

        try (FakeSnmpAgent agentA = new FakeSnmpAgent("disc-comm", ipA, snmpPort, mibA.build());
             FakeSnmpAgent agentB = new FakeSnmpAgent("disc-comm", ipB, snmpPort, mibB.build());
             ServerSocket tcp = listen(ipTcp)) {
            long profileId = createProfile("disc-profile", "disc-comm", snmpPort).get("id").asLong();

            // ---- 1) varredura
            JsonNode started = read(postJson("/api/discovery/jobs", request("127.0.60.0/29",
                    "snmpProfileIds", List.of(profileId), "ports", List.of(tcp.getLocalPort()))).andExpect(status().isCreated()));
            long jobId = started.get("id").asLong();
            assertThat(started.get("hostsTotal").asInt()).isEqualTo(6);
            postJson("/api/discovery/jobs", request("127.0.60.0/29")).andExpect(status().isConflict());   // um job por vez

            JsonNode job = awaitFinished(jobId);
            assertThat(job.get("status").asText()).as(job.toString()).isEqualTo("DONE");
            assertThat(job.get("hostsScanned").asInt()).isEqualTo(6);
            assertThat(job.get("hostsFound").asInt()).isEqualTo(6);
            assertThat(job.get("hostsKnown").asInt()).isZero();

            // ---- 2) o que foi encontrado
            JsonNode hosts = getJson("/api/discovery/jobs/" + jobId + "/hosts");
            assertThat(hosts).hasSize(6);
            assertThat(hosts).extracting(h -> h.get("ip").asText())
                    .containsExactly("127.0.60.1", ipA, ipTcp, ipB, "127.0.60.5", "127.0.60.6");   // ordem numérica
            JsonNode a = hostAt(hosts, ipA);
            assertThat(a.get("hostname").asText()).isEqualTo("sw-a");
            assertThat(a.get("snmpProfileId").asLong()).isEqualTo(profileId);
            assertThat(a.get("suggestedType").asText()).isEqualTo("SWITCH");
            assertThat(a.get("osInfo").asText()).isEqualTo("Cisco IOS");
            assertThat(hostAt(hosts, ipB).get("suggestedType").asText()).isEqualTo("SERVER");
            JsonNode t = hostAt(hosts, ipTcp);
            assertThat(t.get("openPorts")).extracting(JsonNode::asInt).containsExactly(tcp.getLocalPort());
            assertThat(t.has("snmpProfileId")).isFalse();
            assertThat(hostAt(hosts, "127.0.60.1").has("hostname")).isFalse();

            // ---- 3) importação (com ajuste do usuário em um dos hosts)
            Map<String, Object> importBody = Map.of("openPortChecks", true, "hosts", List.of(
                    Map.of("hostId", a.get("id").asLong()),
                    Map.of("hostId", t.get("id").asLong(), "name", "Servidor de aplicação", "deviceType", "STORAGE"),
                    Map.of("hostId", hostAt(hosts, ipB).get("id").asLong())));
            JsonNode result = read(postJson("/api/discovery/jobs/" + jobId + "/import", importBody).andExpect(status().isOk()));
            assertThat(result.get("deviceIds")).hasSize(3);
            assertThat(result.get("skipped")).isEmpty();
            assertThat(result.get("warnings")).as(result.toString()).isEmpty();
            assertThat(result.get("suggestedLinks").asInt()).as("A↔B visto dos dois lados vira um só").isEqualTo(1);

            JsonNode devices = getJson("/api/devices");
            JsonNode devA = deviceAt(devices, ipA);
            JsonNode detailA = getJson("/api/devices/" + devA.get("id").asLong());
            assertThat(detailA.get("origin").asText()).isEqualTo("DISCOVERY");
            assertThat(detailA.get("name").asText()).isEqualTo("sw-a");
            assertThat(detailA.get("deviceType").asText()).isEqualTo("SWITCH");
            assertThat(detailA.get("osInfo").asText()).isEqualTo("Cisco IOS");
            assertThat(detailA.get("snmpProfileId").asLong()).isEqualTo(profileId);
            assertThat(detailA.get("checks")).extracting(c -> c.get("type").asText()).containsExactlyInAnyOrder("ICMP", "SNMP");
            JsonNode detailT = getJson("/api/devices/" + deviceAt(devices, ipTcp).get("id").asLong());
            assertThat(detailT.get("name").asText()).isEqualTo("Servidor de aplicação");
            assertThat(detailT.get("deviceType").asText()).as("a escolha do usuário vence o fingerprint").isEqualTo("STORAGE");
            assertThat(detailT.get("checks")).extracting(c -> c.get("name").asText())
                    .containsExactlyInAnyOrder("Ping", "TCP " + tcp.getLocalPort());

            JsonNode ifsA = getJson("/api/devices/" + devA.get("id").asLong() + "/interfaces");
            assertThat(ifsA).hasSize(3);
            assertThat(ifsA.get(0).get("monitored").asBoolean()).as("Gi0/1 está UP").isTrue();
            assertThat(ifsA.get(1).get("monitored").asBoolean()).as("Gi0/2 está DOWN").isFalse();
            assertThat(ifsA.get(2).get("monitored").asBoolean()).as("loopback nunca").isFalse();

            // ---- 4) o enlace sugerido aparece no mapa, com os nomes das interfaces
            JsonNode links = getJson("/api/maps/1").get("links");
            JsonNode sw = null;
            for (JsonNode l : links) {
                if (l.get("origin").asText().equals("LLDP")) {
                    sw = l;
                }
            }
            assertThat(sw).as("enlace LLDP no mapa: " + links).isNotNull();
            assertThat(sw.get("state").asText()).isEqualTo("SUGGESTED");
            assertThat(sw.get("sourceInterface").asText()).isEqualTo("Gi0/1");
            assertThat(sw.get("targetInterface").asText()).isEqualTo("Gi0/1");
            assertThat(sw.get("sourceDeviceId").asLong()).isLessThan(sw.get("targetDeviceId").asLong());

            // ---- 5) reimportar é inofensivo
            JsonNode again = read(postJson("/api/discovery/jobs/" + jobId + "/import", importBody).andExpect(status().isOk()));
            assertThat(again.get("deviceIds")).isEmpty();
            assertThat(again.get("skipped")).hasSize(3);
            assertThat(again.get("skipped").get(0).asText()).contains("importado");

            // ---- 6) confirmar o enlace; o LLDP em lote não duplica
            postJson("/api/links/" + sw.get("id").asLong() + "/confirm", Map.of()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("CONFIRMED"));
            JsonNode batch = read(postJson("/api/topology/refresh", Map.of()).andExpect(status().isOk()));
            assertThat(batch.get("created").asInt()).isZero();
            // O banco é compartilhado: dispositivos de outros testes (agentes já encerrados) dão erro, como devem.
            // Aqui só interessa que os desta varredura (127.0.60.x) responderam.
            assertThat(batch.get("errors").toString()).as(batch.toString()).doesNotContain("127.0.60.");
            assertThat(batch.get("devices").asInt()).isGreaterThanOrEqualTo(2);

            // ---- 6b) descartar uma sugestão a rejeita; no lote o par conta UMA vez (visto dos dois lados)
            assertThat(batch.get("rejected").asInt()).isZero();
            postJson("/api/links", Map.of("sourceDeviceId", deviceAt(devices, ipA).get("id").asLong(),
                    "targetDeviceId", deviceAt(devices, ipB).get("id").asLong())).andExpect(status().isConflict());   // já existe (confirmado)
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/links/" + sw.get("id").asLong())
                    .session(admin).with(csrf())).andExpect(status().isNoContent());     // exclui o confirmado: NÃO é rejeição
            JsonNode afterDelete = read(postJson("/api/topology/refresh", Map.of()).andExpect(status().isOk()));
            assertThat(afterDelete.get("created").asInt()).as("um enlace confirmado apagado volta a ser sugerido").isEqualTo(1);
            long suggestedId = 0;
            for (JsonNode l : getJson("/api/maps/1").get("links")) {
                if (l.get("state").asText().equals("SUGGESTED")) {
                    suggestedId = l.get("id").asLong();
                }
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/links/" + suggestedId)
                    .session(admin).with(csrf())).andExpect(status().isNoContent());     // descarta a sugestão: rejeição
            JsonNode afterReject = read(postJson("/api/topology/refresh", Map.of()).andExpect(status().isOk()));
            assertThat(afterReject.get("created").asInt()).isZero();
            assertThat(afterReject.get("rejected").asInt()).as("um par, apesar de visto por A e por B").isEqualTo(1);

            // ---- 7) nova varredura: os 3 importados são "já cadastrados" e nem são sondados
            JsonNode second = awaitFinished(read(postJson("/api/discovery/jobs", request("127.0.60.0/29",
                    "snmpProfileIds", List.of(profileId))).andExpect(status().isCreated())).get("id").asLong());
            assertThat(second.get("hostsKnown").asInt()).isEqualTo(3);
            assertThat(second.get("hostsFound").asInt()).isEqualTo(3);
            JsonNode newHosts = getJson("/api/discovery/jobs/" + second.get("id").asLong() + "/hosts");
            assertThat(newHosts).extracting(h -> h.get("ip").asText()).containsExactly("127.0.60.1", "127.0.60.5", "127.0.60.6");

            // ---- 8) ignorar um host o tira da fila
            JsonNode ignored = read(postJson("/api/discovery/hosts/" + newHosts.get(0).get("id").asLong() + "/ignore", Map.of())
                    .andExpect(status().isOk()));
            assertThat(ignored.get("state").asText()).isEqualTo("IGNORED");
            JsonNode skip = read(postJson("/api/discovery/jobs/" + second.get("id").asLong() + "/import", Map.of("hosts",
                    List.of(Map.of("hostId", ignored.get("id").asLong())))).andExpect(status().isOk()));
            assertThat(skip.get("skipped").get(0).asText()).contains("ignorado");
        }
    }

    private JsonNode deviceAt(JsonNode devices, String ip) {
        for (JsonNode d : devices) {
            if (d.get("primaryIp").asText().equals(ip)) {
                return d;
            }
        }
        throw new AssertionError("dispositivo " + ip + " não encontrado");
    }

    // ------------------------------------------------------------------------------------ cancelamento e recuperação

    @Test
    void cancellingStopsTheScanAndKeepsPartialCounters() throws Exception {
        assumeTrue(new ExternalPingProbe().execute(new Target("127.0.0.1", null, 1000)).success(), "ping indisponível");
        JsonNode started = read(postJson("/api/discovery/jobs", request("198.51.100.0/24")).andExpect(status().isCreated()));
        long id = started.get("id").asLong();
        postJson("/api/discovery/jobs", request("198.51.100.0/24")).andExpect(status().isConflict());
        Thread.sleep(300);
        postJson("/api/discovery/jobs/" + id + "/cancel", Map.of()).andExpect(status().isOk());
        JsonNode job = awaitFinished(id);
        assertThat(job.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(job.get("hostsScanned").asInt()).isLessThan(job.get("hostsTotal").asInt());
        postJson("/api/discovery/jobs/" + id + "/cancel", Map.of()).andExpect(status().isConflict());   // já terminou
        assertThat(getJson("/api/events?limit=20").toString()).contains("cancelada");
    }

    @Test
    void jobsLeftRunningByACrashAreMarkedFailedOnStartup() throws Exception {
        jdbc.update("INSERT INTO discovery_jobs(cidr, status, hosts_total) VALUES ('10.9.9.0/24', 'RUNNING', 254)");
        Long id = jdbc.queryForObject("SELECT max(id) FROM discovery_jobs", Long.class);
        discovery.recoverInterrupted();
        JsonNode job = getJson("/api/discovery/jobs/" + id);
        assertThat(job.get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("error").asText()).contains("reinício");
        postJson("/api/discovery/jobs", request("127.0.63.0/30")).andExpect(status().isCreated());   // não bloqueia novos
        awaitFinished(read(mvc.perform(get("/api/discovery/jobs").session(admin)).andExpect(status().isOk())).get(0).get("id").asLong());
    }
}
