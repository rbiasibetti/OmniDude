package com.omnidude.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import com.omnidude.FakeSnmpAgent;
import com.omnidude.TestMib;
import com.omnidude.admin.SettingsService;
import com.omnidude.discovery.ArpTable;
import com.omnidude.identity.IdentityService.Result;
import com.omnidude.topology.GatewayResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Identidade estável com DHCP: troca de IP pelo MAC, conflitos, reconhecimento na descoberta. Banco próprio. */
class IdentityApiTest extends ApiTestSupport {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("omnidude-identity-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("omnidude.data-dir", DATA_DIR::toString);
        registry.add("omnidude.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired IdentityService identity;
    @Autowired SettingsService settings;
    @Autowired SubnetSweeper sweeper;
    @SpyBean ArpTable arpTable;
    @SpyBean GatewayResolver gateway;

    // MACs estáveis (bit "administrado localmente" desligado) e aleatórios
    private static final String M1 = "00:11:22:33:44:01";
    private static final String M2 = "00:11:22:33:44:02";
    private static final String M3 = "00:11:22:33:44:03";
    private static final String RANDOM = "da:a1:19:00:00:01";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM devices");
        jdbc.update("DELETE FROM snmp_profiles");
        jdbc.update("DELETE FROM events");
        settings.update(new HashMap<>(Map.of(SettingsService.IDENTITY_AUTO_UPDATE, true)));
    }

    @AfterEach
    void restore() {
        settings.update(new HashMap<>(Map.of(SettingsService.IDENTITY_AUTO_UPDATE, true)));
    }

    private long device(String name, String ip, String status, String... macs) throws Exception {
        long id = createDevice(name, ip, "WORKSTATION").get("id").asLong();
        jdbc.update("UPDATE devices SET status = ? WHERE id = ?", status, id);
        for (String m : macs) {
            identity.learn(id, m, "DEVICE");
        }
        return id;
    }

    private static Map<String, String> arp(String... ipMac) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < ipMac.length; i += 2) {
            m.put(ipMac[i], ipMac[i + 1]);
        }
        return m;
    }

    private String ipOf(long id) {
        return jdbc.queryForObject("SELECT primary_ip FROM devices WHERE id = ?", String.class, id);
    }

    private int events(String like) {
        return jdbc.queryForObject("SELECT count(*) FROM events WHERE type = 'SYSTEM' AND message LIKE ?", Integer.class, like);
    }

    // ------------------------------------------------------------------------------------------------ troca de IP

    @Test
    void aDownDeviceWhoseMacReappearsElsewhereKeepsItsIdentityAndHistory() throws Exception {
        long id = device("notebook", "10.50.0.10", "DOWN", M1);
        jdbc.update("INSERT INTO service_checks(device_id, type, name, interval_s, timeout_ms) VALUES (?, 'TCP', 'ssh', 30, 2000)", id);

        Result r = identity.reconcile(arp("10.50.0.77", M1));

        assertThat(r.changes()).hasSize(1);
        assertThat(r.changes().get(0).oldIp()).isEqualTo("10.50.0.10");
        assertThat(r.changes().get(0).newIp()).isEqualTo("10.50.0.77");
        assertThat(ipOf(id)).isEqualTo("10.50.0.77");
        assertThat(getJson("/api/devices/" + id).get("primaryIp").asText()).isEqualTo("10.50.0.77");
        assertThat(getJson("/api/devices/" + id).get("checks")).as("as checagens seguem o dispositivo").hasSize(2);
        assertThat(getJson("/api/devices")).hasSize(1);                         // nada de dispositivo duplicado
        JsonNode history = getJson("/api/devices/" + id + "/identity").get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("reason").asText()).isEqualTo("AUTO");
        assertThat(history.get(0).get("oldIp").asText()).isEqualTo("10.50.0.10");
        assertThat(events("%notebook mudou de IP: 10.50.0.10 → 10.50.0.77%")).isEqualTo(1);
    }

    @Test
    void aDeviceThatIsStillUpIsLeftAlone() throws Exception {
        long id = device("ainda-no-ar", "10.50.0.11", "UP", M1);
        Result r = identity.reconcile(arp("10.50.0.88", M1));
        assertThat(r.changes()).isEmpty();
        assertThat(ipOf(id)).isEqualTo("10.50.0.11");
    }

    @Test
    void sameMacAnsweringOnTwoIpsBecomesAnAdditionalAddress() throws Exception {
        long id = device("duas-placas", "10.50.0.12", "UP", M1);
        Result r = identity.reconcile(arp("10.50.0.12", M1, "10.50.1.12", M1));
        assertThat(r.additional()).isEqualTo(1);
        assertThat(r.changes()).isEmpty();
        assertThat(jdbc.queryForList("SELECT ip_address FROM device_addresses WHERE device_id = ?", String.class, id))
                .containsExactly("10.50.1.12");
        assertThat(ipOf(id)).isEqualTo("10.50.0.12");
        assertThat(identity.reconcile(arp("10.50.0.12", M1, "10.50.1.12", M1)).additional()).as("idempotente").isZero();
    }

    @Test
    void randomizedAndAmbiguousMacsNeverDecideOnTheirOwn() throws Exception {
        long phone = device("celular", "10.50.0.13", "DOWN");
        identity.learn(phone, RANDOM, "ARP");
        assertThat(identity.reconcile(arp("10.50.0.99", RANDOM)).changes()).as("MAC aleatório não decide").isEmpty();

        long a = device("clone-a", "10.50.0.14", "DOWN", M2);
        long b = device("clone-b", "10.50.0.15", "DOWN", M2);
        assertThat(identity.reconcile(arp("10.50.0.98", M2)).changes()).as("MAC de dois dispositivos é ambíguo").isEmpty();
        assertThat(ipOf(a)).isEqualTo("10.50.0.14");
        assertThat(ipOf(b)).isEqualTo("10.50.0.15");
    }

    @Test
    void withAutoUpdateOffItOnlyWarnsAndWarnsOnce() throws Exception {
        settings.update(new HashMap<>(Map.of(SettingsService.IDENTITY_AUTO_UPDATE, false)));
        long id = device("manual", "10.50.0.16", "DOWN", M1);
        Result r = identity.reconcile(arp("10.50.0.66", M1));
        assertThat(r.changes()).isEmpty();
        assertThat(r.conflicts()).hasSize(1);
        assertThat(ipOf(id)).isEqualTo("10.50.0.16");
        identity.reconcile(arp("10.50.0.66", M1));
        assertThat(events("%manual%10.50.0.66%")).as("o aviso não se repete a cada minuto").isEqualTo(1);

        // pedido explícito do usuário ("procurar novo IP") ignora a configuração
        Result forced = identity.reconcile(arp("10.50.0.66", M1), id, true);
        assertThat(forced.changes()).hasSize(1);
        assertThat(ipOf(id)).isEqualTo("10.50.0.66");
    }

    @Test
    void anIpAlreadyOwnedByAnotherDeviceIsNeverTaken() throws Exception {
        long mover = device("mudou", "10.50.0.17", "DOWN", M1);
        long holder = device("dono-do-ip", "10.50.0.18", "UP", M2);
        // o MAC de "mudou" aparece justamente no IP cadastrado de "dono-do-ip"
        Result r = identity.reconcile(arp("10.50.0.18", M1));
        assertThat(r.changes()).isEmpty();
        assertThat(r.conflicts()).hasSize(1);
        assertThat(r.conflicts().get(0).deviceId()).isEqualTo(holder);
        assertThat(r.conflicts().get(0).detail()).contains("mudou");           // diz a quem o MAC pertence
        assertThat(ipOf(mover)).isEqualTo("10.50.0.17");
        assertThat(ipOf(holder)).isEqualTo("10.50.0.18");
    }

    // ------------------------------------------------------------------------------------------------ conflitos e aprendizado

    @Test
    void anotherMacAnsweringAtADevicesIpIsAWarningNotALearnedAddress() throws Exception {
        long id = device("servidor", "10.50.0.19", "UP", M1);
        Result r = identity.reconcile(arp("10.50.0.19", M3));
        assertThat(r.conflicts()).hasSize(1);
        assertThat(r.conflicts().get(0).observedMac()).isEqualTo(M3);
        assertThat(identity.macs(id)).extracting(IdentityService.MacEntry::mac).containsExactly(M1);
        assertThat(jdbc.queryForObject("SELECT severity FROM events WHERE message LIKE '%servidor%' ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("WARNING");
        identity.reconcile(arp("10.50.0.19", M3));
        assertThat(events("%servidor%")).isEqualTo(1);

        // o usuário confirma que a placa foi trocada: o MAC novo vira conhecido e o aviso some
        mvc.perform(post("/api/devices/" + id + "/macs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"" + M3.toUpperCase() + "\"}")).andExpect(status().isCreated());
        assertThat(identity.reconcile(arp("10.50.0.19", M3)).conflicts()).isEmpty();
    }

    @Test
    void aDeviceWithoutKnownMacLearnsItFromArp() throws Exception {
        long id = device("novo", "10.50.0.20", "UP");
        Result r = identity.reconcile(arp("10.50.0.20", M1));
        assertThat(r.learned()).isEqualTo(1);
        assertThat(identity.macs(id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT mac_address FROM devices WHERE id = ?", String.class, id)).isEqualTo(M1);
        assertThat(identity.reconcile(arp("10.50.0.20", M1)).learned()).isZero();
    }

    @Test
    void aDeviceWithOnlyRandomMacsDoesNotRaiseConflictsOnItsOwnIp() throws Exception {
        long id = device("celular2", "10.50.0.21", "UP");
        identity.learn(id, RANDOM, "ARP");
        assertThat(identity.reconcile(arp("10.50.0.21", "da:a1:19:00:00:02")).conflicts())
                .as("sem identidade estável, não há como acusar conflito").isEmpty();
    }

    @Test
    void manualIpEditsAreRecordedInTheHistory() throws Exception {
        long id = device("editado", "10.50.0.22", "UP", M1);
        putJson("/api/devices/" + id, Map.of("name", "editado", "primaryIp", "10.50.0.23", "deviceType", "WORKSTATION"))
                .andExpect(status().isOk());
        JsonNode h = getJson("/api/devices/" + id + "/identity").get("history");
        assertThat(h).hasSize(1);
        assertThat(h.get(0).get("reason").asText()).isEqualTo("MANUAL");
        assertThat(h.get(0).get("newIp").asText()).isEqualTo("10.50.0.23");
    }

    // ------------------------------------------------------------------------------------------------ API de MACs

    @Test
    void macEndpointsValidateAndProtect() throws Exception {
        long a = device("dono", "10.50.0.24", "UP", M1);
        long b = device("outro", "10.50.0.25", "UP");
        JsonNode list = getJson("/api/devices/" + a + "/identity").get("macs");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("mac").asText()).isEqualTo(M1);
        assertThat(list.get(0).get("source").asText()).isEqualTo("DEVICE");
        assertThat(list.get(0).get("random").asBoolean()).isFalse();

        mvc.perform(post("/api/devices/" + b + "/macs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"" + M1 + "\"}")).andExpect(status().isConflict());              // já é de outro
        mvc.perform(post("/api/devices/" + b + "/macs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"nao-e-mac\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/devices/999999/macs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"" + M2 + "\"}")).andExpect(status().isNotFound());
        // MAC aleatório informado pelo usuário vale como identidade
        mvc.perform(post("/api/devices/" + b + "/macs").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"" + RANDOM + "\"}")).andExpect(status().isCreated());
        assertThat(getJson("/api/devices/" + b + "/identity").get("macs").get(0).get("random").asBoolean()).isFalse();

        mvc.perform(delete("/api/devices/" + a + "/macs").param("mac", M1).session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/devices/" + a + "/macs").param("mac", M1).session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/devices/" + a + "/identity").session(viewer())).andExpect(status().isOk());
        mvc.perform(post("/api/devices/" + a + "/macs").session(viewer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mac\":\"" + M2 + "\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/devices/" + a + "/identity")).andExpect(status().isUnauthorized());
    }

    @Test
    void snmpInventoryLearnsEveryInterfaceMacExceptLoopback() throws Exception {
        TestMib mib = new TestMib().system("Switch", "1.3.6.1.4.1.9.1.716", "sw-id", 1000)
                .iface(1, "Gi0/1", "00:aa:bb:00:00:01", 6, 1, 1, 1000)
                .iface(2, "Gi0/2", "00:aa:bb:00:00:02", 6, 1, 1, 1000)
                .iface(3, "Lo0", "00:aa:bb:00:00:99", 24, 1, 1, 0);
        try (FakeSnmpAgent agent = new FakeSnmpAgent("idc", "127.0.86.2", 0, mib.build())) {
            long profile = createProfile("id-prof", "idc", agent.port()).get("id").asLong();
            long id = createDevice("sw-id", "127.0.86.2", "SWITCH").get("id").asLong();
            putJson("/api/devices/" + id, Map.of("name", "sw-id", "primaryIp", "127.0.86.2", "deviceType", "SWITCH", "snmpProfileId", profile))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/devices/" + id + "/refresh").session(admin).with(csrf())).andExpect(status().isOk());
            assertThat(identity.macs(id)).extracting(IdentityService.MacEntry::mac)
                    .containsExactlyInAnyOrder("00:aa:bb:00:00:01", "00:aa:bb:00:00:02");
            assertThat(identity.macs(id)).allMatch(m -> m.source().equals("IFACE"));
        }
    }

    // ------------------------------------------------------------------------------------------------ localizar

    @Test
    void locateExplainsWhyItCannotAndFindsTheNewIpWhenItCan() throws Exception {
        long noMac = device("sem-mac", "10.50.0.30", "DOWN");
        JsonNode r = read(mvc.perform(post("/api/devices/" + noMac + "/locate").session(admin).with(csrf())).andExpect(status().isOk()));
        assertThat(r.get("found").asBoolean()).isFalse();
        assertThat(r.get("message").asText()).contains("MAC");

        long onlyRandom = device("so-aleatorio", "10.50.0.31", "DOWN");
        identity.learn(onlyRandom, RANDOM, "ARP");
        assertThat(read(mvc.perform(post("/api/devices/" + onlyRandom + "/locate").session(admin).with(csrf()))
                .andExpect(status().isOk())).get("message").asText()).contains("aleatório");

        long up = device("no-ar", "10.50.0.32", "UP", M2);
        assertThat(read(mvc.perform(post("/api/devices/" + up + "/locate").session(admin).with(csrf()))
                .andExpect(status().isOk())).get("message").asText()).contains("respondendo");

        long down = device("sumiu", "10.50.0.33", "DOWN", M1);
        doReturn(arp("10.50.0.133", M1)).when(arpTable).snapshot();
        JsonNode found = read(mvc.perform(post("/api/devices/" + down + "/locate").session(admin).with(csrf())).andExpect(status().isOk()));
        assertThat(found.get("found").asBoolean()).isTrue();
        assertThat(found.get("newIp").asText()).isEqualTo("10.50.0.133");
        assertThat(ipOf(down)).isEqualTo("10.50.0.133");

        long lost = device("desligado", "10.50.0.34", "DOWN", M3);
        doReturn(Map.of()).when(arpTable).snapshot();
        JsonNode none = read(mvc.perform(post("/api/devices/" + lost + "/locate").session(admin).with(csrf())).andExpect(status().isOk()));
        assertThat(none.get("found").asBoolean()).isFalse();
        assertThat(none.get("message").asText()).contains("não apareceu");
        mvc.perform(post("/api/devices/999999/locate").session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/devices/" + lost + "/locate").session(viewer()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void subnetSweepOnlyAppliesToSmallPrivateLocalSubnets() {
        doReturn(OptionalInt.of(24)).when(gateway).localPrefix("10.99.0.5");
        assertThat(sweeper.subnetOf("10.99.0.5")).isEqualTo("10.99.0.0/24");
        doReturn(OptionalInt.of(22)).when(gateway).localPrefix("10.99.4.5");
        assertThat(sweeper.subnetOf("10.99.4.5")).isEqualTo("10.99.4.0/22");
        doReturn(OptionalInt.of(16)).when(gateway).localPrefix("10.99.9.9");
        assertThat(sweeper.subnetOf("10.99.9.9")).as("grande demais").isNull();
        doReturn(OptionalInt.of(24)).when(gateway).localPrefix("8.8.8.8");
        assertThat(sweeper.subnetOf("8.8.8.8")).as("rede pública nunca é varrida").isNull();
        doReturn(OptionalInt.empty()).when(gateway).localPrefix("172.20.1.1");
        assertThat(sweeper.subnetOf("172.20.1.1")).as("fora das sub-redes do servidor").isNull();
    }

    // ------------------------------------------------------------------------------------------------ descoberta

    private long job() {
        jdbc.update("INSERT INTO discovery_jobs(cidr, status, hosts_total) VALUES ('10.50.0.0/24', 'DONE', 254)");
        return jdbc.queryForObject("SELECT max(id) FROM discovery_jobs", Long.class);
    }

    private long host(long job, String ip, String mac, String hostname, Long matched, String by) {
        jdbc.update("INSERT INTO discovery_hosts(job_id, ip_address, mac_address, hostname, matched_device_id, matched_by, open_ports) "
                + "VALUES (?,?,?,?,?,?,'[]')", job, ip, mac, hostname, matched, by);
        return jdbc.queryForObject("SELECT max(id) FROM discovery_hosts", Long.class);
    }

    @Test
    void recognitionByMacHostnameAndAmbiguity() throws Exception {
        long id = device("Impressora-RH", "10.50.0.40", "DOWN", M1);
        jdbc.update("UPDATE devices SET hostname = 'impressora-rh' WHERE id = ?", id);

        var byMac = identity.recognize("10.50.0.140", M1, null).orElseThrow();
        assertThat(byMac.deviceId()).isEqualTo(id);
        assertThat(byMac.by()).isEqualTo("MAC");
        assertThat(byMac.oldIp()).isEqualTo("10.50.0.40");

        var byName = identity.recognize("10.50.0.141", null, "IMPRESSORA-RH").orElseThrow();
        assertThat(byName.by()).isEqualTo("HOSTNAME");
        assertThat(identity.recognize("10.50.0.40", M1, null)).as("o próprio IP não é mudança").isEmpty();
        assertThat(identity.recognize("10.50.0.142", M3, "ninguem")).isEmpty();

        identity.learn(id, RANDOM, "ARP");
        assertThat(identity.recognize("10.50.0.143", RANDOM, null).orElseThrow().by()).isEqualTo("MAC_RANDOM");

        device("outra-com-mesmo-mac", "10.50.0.41", "UP", M1);
        assertThat(identity.recognize("10.50.0.144", M1, null)).as("MAC de dois dispositivos: ambíguo").isEmpty();
    }

    @Test
    void discoveryOffersToUpdateTheIpInsteadOfCreatingADuplicate() throws Exception {
        long dev = device("camera", "10.50.0.50", "DOWN", M1);
        long job = job();
        long h = host(job, "10.50.0.150", M1, null, dev, "MAC");

        JsonNode view = getJson("/api/discovery/jobs/" + job + "/hosts").get(0);
        assertThat(view.get("matchedDeviceId").asLong()).isEqualTo(dev);
        assertThat(view.get("matchedDeviceName").asText()).isEqualTo("camera");
        assertThat(view.get("matchedOldIp").asText()).isEqualTo("10.50.0.50");
        assertThat(view.get("matchedBy").asText()).isEqualTo("MAC");

        // importar como novo é recusado com a explicação
        JsonNode imp = read(postJson("/api/discovery/jobs/" + job + "/import", Map.of("hosts", List.of(Map.of("hostId", h))))
                .andExpect(status().isOk()));
        assertThat(imp.get("deviceIds")).isEmpty();
        assertThat(imp.get("skipped").get(0).asText()).contains("Atualizar IP");
        assertThat(getJson("/api/devices")).hasSize(1);

        // adotar: o dispositivo passa a usar o IP do host, com histórico, e o host fica tratado
        JsonNode change = read(postJson("/api/discovery/hosts/" + h + "/adopt", Map.of()).andExpect(status().isOk()));
        assertThat(change.get("newIp").asText()).isEqualTo("10.50.0.150");
        assertThat(ipOf(dev)).isEqualTo("10.50.0.150");
        assertThat(getJson("/api/devices/" + dev + "/identity").get("history").get(0).get("reason").asText()).isEqualTo("DISCOVERY");
        assertThat(jdbc.queryForObject("SELECT state FROM discovery_hosts WHERE id = ?", String.class, h)).isEqualTo("IMPORTED");
        postJson("/api/discovery/hosts/" + h + "/adopt", Map.of()).andExpect(status().isConflict());           // já tratado
    }

    @Test
    void adoptionIsRefusedWhenThereIsNoMatchOrTheIpIsTaken() throws Exception {
        long dev = device("alvo", "10.50.0.51", "DOWN", M1);
        long other = device("ocupa", "10.50.0.151", "UP", M2);
        long job = job();
        long plain = host(job, "10.50.0.152", M3, null, null, null);
        long taken = host(job, "10.50.0.151", M1, null, dev, "MAC");
        postJson("/api/discovery/hosts/" + plain + "/adopt", Map.of()).andExpect(status().isBadRequest());
        postJson("/api/discovery/hosts/" + taken + "/adopt", Map.of()).andExpect(status().isConflict());
        postJson("/api/discovery/hosts/999999/adopt", Map.of()).andExpect(status().isNotFound());
        assertThat(ipOf(dev)).isEqualTo("10.50.0.51");
        assertThat(ipOf(other)).isEqualTo("10.50.0.151");
        mvc.perform(post("/api/discovery/hosts/" + plain + "/adopt").session(viewer()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void importingAsNewStillWorksAndRemembersTheMac() throws Exception {
        long dev = device("original", "10.50.0.52", "UP", M1);
        long job = job();
        long h = host(job, "10.50.0.152", M1, null, dev, "MAC");
        Map<String, Object> item = new HashMap<>();
        item.put("hostId", h);
        item.put("name", "copia-deliberada");
        item.put("forceNew", true);
        JsonNode r = read(postJson("/api/discovery/jobs/" + job + "/import", Map.of("hosts", List.of(item))).andExpect(status().isOk()));
        assertThat(r.get("deviceIds")).hasSize(1);
        assertThat(getJson("/api/devices")).hasSize(2);
    }

    @Test
    void importedDevicesRememberTheDiscoveredMac() throws Exception {
        long job = job();
        long h = host(job, "10.50.0.153", M2, null, null, null);
        JsonNode r = read(postJson("/api/discovery/jobs/" + job + "/import", Map.of("hosts", List.of(Map.of("hostId", h, "name", "novo-host"))))
                .andExpect(status().isOk()));
        long id = r.get("deviceIds").get(0).asLong();
        assertThat(identity.macs(id)).hasSize(1);
        assertThat(identity.macs(id).get(0).mac()).isEqualTo(M2);
        assertThat(identity.macs(id).get(0).source()).isEqualTo("DISCOVERY");
        // e a troca de IP desse dispositivo, depois, é reconhecida
        jdbc.update("UPDATE devices SET status = 'DOWN' WHERE id = ?", id);
        assertThat(identity.reconcile(arp("10.50.0.253", M2)).changes()).hasSize(1);
    }
}
