package com.omnidude.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.admin.SettingsService;
import com.omnidude.identity.IdentityService;
import com.omnidude.config.OmniDudeProperties;
import com.omnidude.discovery.Fingerprinter.Fingerprint;
import com.omnidude.discovery.HostScanner.Scan;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.Severity;
import com.omnidude.snmp.SnmpAccess;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.web.ApiException;
import com.omnidude.web.SseHub;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Varredura de uma faixa CIDR. Um job por vez; cada host passa por {@link HostScanner}; os que respondem ficam em
 * {@code discovery_hosts} para o usuário revisar antes de importar. Hosts já cadastrados não são varridos.
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() { };
    private static final TypeReference<List<Integer>> INTS = new TypeReference<>() { };
    private static final long PROGRESS_EVERY_MS = 1000;

    public record StartRequest(@NotBlank String cidr, List<Long> snmpProfileIds, List<Integer> ports,
                               Boolean confirmLarge, Boolean authorized) {
    }

    public record JobView(Long id, String cidr, DiscoveryStatus status, int hostsTotal, int hostsScanned,
                          int hostsFound, int hostsKnown, Long startedAt, Long finishedAt, String error,
                          List<Long> snmpProfileIds, List<Integer> ports) {
    }

    public record HostView(Long id, String ip, String hostname, String mac, String sysDescr, String sysObjectId,
                           String suggestedType, String osInfo, List<Integer> openPorts, Long snmpProfileId,
                           HostState state, String macVendor, boolean macLocal, String hints, Long matchedDeviceId,
                           String matchedDeviceName, String matchedOldIp, String matchedBy) {
    }

    private final DiscoveryJobRepository jobs;
    private final DiscoveryHostRepository hosts;
    private final HostScanner scanner;
    private final Fingerprinter fingerprinter;
    private final ArpTable arp;
    private final SnmpProfileService profiles;
    private final EventService events;
    private final SseHub hub;
    private final JdbcTemplate jdbc;
    private final OmniDudeProperties props;
    private final ObjectMapper json;
    private final OuiRegistry oui;
    private final SettingsService settings;
    private final IdentityService identity;
    private final ExecutorService executor;
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public DiscoveryService(DiscoveryJobRepository jobs, DiscoveryHostRepository hosts, HostScanner scanner,
                            Fingerprinter fingerprinter, ArpTable arp, SnmpProfileService profiles, EventService events,
                            SseHub hub, JdbcTemplate jdbc, OmniDudeProperties props, ObjectMapper json,
                            OuiRegistry oui, SettingsService settings, IdentityService identity,
                            ExecutorService checkExecutor) {
        this.identity = identity;
        this.oui = oui;
        this.settings = settings;
        this.jobs = jobs;
        this.hosts = hosts;
        this.scanner = scanner;
        this.fingerprinter = fingerprinter;
        this.arp = arp;
        this.profiles = profiles;
        this.events = events;
        this.hub = hub;
        this.jdbc = jdbc;
        this.props = props;
        this.json = json;
        this.executor = checkExecutor;
    }

    /** Um job que estava rodando quando o processo caiu não tem mais quem o execute. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (DiscoveryJob j : jobs.findByStatusIn(List.of(DiscoveryStatus.PENDING, DiscoveryStatus.RUNNING))) {
            j.setStatus(DiscoveryStatus.FAILED);
            j.setError("Interrompida por reinício do servidor");
            j.setFinishedAt(Instant.now().getEpochSecond());
            jobs.save(j);
            log.warn("Descoberta {} ({}) marcada como FAILED: o servidor reiniciou durante a varredura", j.getId(), j.getCidr());
        }
    }

    public JobView start(StartRequest req, Long userId) {
        if (!Boolean.TRUE.equals(req.authorized())) {
            throw bad("Confirme que você tem autorização para varrer esta rede");
        }
        CidrRange range;
        try {
            range = CidrRange.parse(req.cidr());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        var cfg = props.discovery();
        if (!cfg.allowPublic() && !range.insideAllowedNetworks()) {
            throw bad("Faixa não permitida: só redes privadas, loopback, link-local e CGNAT podem ser varridas "
                    + "(omnidude.discovery.allow-public libera faixas públicas)");
        }
        long count = range.hostCount();
        if (count > cfg.hardMaxHosts()) {
            throw bad("A faixa tem " + count + " hosts; o máximo absoluto é " + cfg.hardMaxHosts());
        }
        boolean large = count > settings.getInt(SettingsService.DISCOVERY_MAX_HOSTS);
        if (large && !Boolean.TRUE.equals(req.confirmLarge())) {
            throw bad("A faixa tem " + count + " hosts (limite padrão " + settings.getInt(SettingsService.DISCOVERY_MAX_HOSTS)
                    + "). Confirme a varredura grande para continuar");
        }
        List<Integer> ports = req.ports() == null ? cfg.defaultPorts() : req.ports();
        if (ports.size() > 20 || ports.stream().anyMatch(p -> p == null || p < 1 || p > 65535)) {
            throw bad("Portas inválidas (até 20, entre 1 e 65535)");
        }
        List<Long> profileIds = req.snmpProfileIds() == null ? List.of() : req.snmpProfileIds();
        List<SnmpAccess> accesses = new ArrayList<>();
        for (Long id : profileIds) {
            accesses.add(profiles.access(id).orElseThrow(() -> bad("Perfil SNMP " + id + " inexistente ou sem community")));
        }
        if (jobs.existsByStatusIn(List.of(DiscoveryStatus.PENDING, DiscoveryStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe uma descoberta em andamento");
        }

        DiscoveryJob job = new DiscoveryJob();
        job.setCidr(range.normalized());
        job.setSnmpProfileIds(write(profileIds));
        job.setProbePorts(write(ports));
        job.setStatus(DiscoveryStatus.PENDING);
        job.setHostsTotal((int) count);
        job.setCreatedBy(userId);
        job = jobs.save(job);
        if (large) {
            events.publish(events.record(Severity.WARNING, EventType.DISCOVERY, null, null,
                    "Varredura grande autorizada: " + range.normalized() + " (" + count + " hosts)"));
        }
        cancelFlags.put(job.getId(), new AtomicBoolean());
        long jobId = job.getId();
        executor.submit(() -> run(jobId, range, ports, accesses));
        return view(job);
    }

    public JobView cancel(long id) {
        DiscoveryJob j = find(id);
        AtomicBoolean flag = cancelFlags.get(id);
        if (flag == null) {
            throw new ApiException(HttpStatus.CONFLICT, "A descoberta não está em andamento");
        }
        flag.set(true);
        return view(j);
    }

    public JobView get(long id) {
        return view(find(id));
    }

    public List<JobView> list() {
        return jobs.findTop20ByOrderByIdDesc().stream().map(this::view).toList();
    }

    public List<HostView> hosts(long jobId) {
        find(jobId);
        return hosts.findByJobIdOrderByIdAsc(jobId).stream()
                .sorted(Comparator.comparingLong(h -> CidrRange.toLong(h.getIpAddress())))
                .map(this::view).toList();
    }

    public HostView ignore(long hostId) {
        DiscoveryHost h = hosts.findById(hostId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Host não encontrado"));
        if (h.getState() == HostState.NEW) {
            h.setState(HostState.IGNORED);
            hosts.save(h);
        }
        return view(h);
    }

    // ------------------------------------------------------------------------------------------------ execução

    private void run(long jobId, CidrRange range, List<Integer> ports, List<SnmpAccess> accesses) {
        AtomicBoolean cancelled = cancelFlags.get(jobId);
        var cfg = props.discovery();
        AtomicInteger scanned = new AtomicInteger();
        AtomicInteger found = new AtomicInteger();
        AtomicInteger known = new AtomicInteger();
        long[] lastPublish = {0};
        try {
            Set<String> knownIps = new HashSet<>(jdbc.queryForList(
                    "SELECT primary_ip FROM devices UNION SELECT ip_address FROM device_addresses", String.class));
            jdbc.update("UPDATE discovery_jobs SET status = 'RUNNING', started_at = ? WHERE id = ?",
                    Instant.now().getEpochSecond(), jobId);
            publish(jobId);

            int concurrency = settings.getInt(SettingsService.DISCOVERY_CONCURRENCY);
            Semaphore permits = new Semaphore(concurrency);
            long pause = Math.max(0, 1000L / Math.max(1, settings.getInt(SettingsService.DISCOVERY_RATE)));
            for (String ip : range.hosts()) {
                if (cancelled.get()) {
                    break;
                }
                if (knownIps.contains(ip)) {                      // já monitorado: não precisa sondar de novo
                    known.incrementAndGet();
                    scanned.incrementAndGet();
                    continue;
                }
                permits.acquire();
                if (pause > 0) {
                    Thread.sleep(pause);
                }
                executor.submit(() -> {
                    try {
                        if (!cancelled.get()) {
                            Scan s = scanner.scan(ip, ports, accesses, cfg.icmpTimeoutMs(), cfg.tcpTimeoutMs());
                            if (s.alive()) {
                                save(jobId, s);
                                found.incrementAndGet();
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("Falha ao sondar {} na descoberta {}", ip, jobId, e);
                    } finally {
                        scanned.incrementAndGet();
                        permits.release();
                        progress(jobId, scanned, found, known, lastPublish, false);
                    }
                });
            }
            permits.acquire(concurrency);                   // espera os que ainda estão em voo
            enrichWithArp(jobId);
            finish(jobId, cancelled.get() ? DiscoveryStatus.CANCELLED : DiscoveryStatus.DONE, null, scanned, found, known);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(jobId, DiscoveryStatus.FAILED, "interrompida", scanned, found, known);
        } catch (RuntimeException e) {
            log.error("Descoberta {} falhou", jobId, e);
            finish(jobId, DiscoveryStatus.FAILED, e.getMessage(), scanned, found, known);
        } finally {
            cancelFlags.remove(jobId);
        }
    }

    private void save(long jobId, Scan s) {
        DiscoveryHost h = new DiscoveryHost();
        h.setJobId(jobId);
        h.setIpAddress(s.ip());
        h.setOpenPorts(write(s.openPorts()));
        if (s.snmp() != null) {
            var sys = s.snmp().system();
            h.setSnmpProfileId(s.snmp().profileId());
            h.setHostname(sys.sysName() == null || sys.sysName().isBlank() ? null : sys.sysName());
            h.setSysDescr(sys.sysDescr());
            h.setSysObjectId(sys.sysObjectId());
        }
        if (s.identity() != null && !s.identity().isEmpty()) {
            h.setHints(s.identity().hints());
            if (h.getHostname() == null) {
                h.setHostname(s.identity().hostname());          // NetBIOS, UPnP ou mDNS, quando não há SNMP
            }
        }
        fingerprinter.match(h.getSysObjectId(), h.getSysDescr(), s.openPorts(), null, h.getHints()).ifPresent((Fingerprint f) -> {
            h.setSuggestedType(f.type().name());
            h.setOsInfo(f.os());
        });
        hosts.save(h);
    }

    /** A tabela ARP só tem o host depois que ele trocou pacotes com o servidor; por isso é lida ao final. */
    private void enrichWithArp(long jobId) {
        Map<String, String> table = arp.snapshot();
        for (DiscoveryHost h : hosts.findByJobIdOrderByIdAsc(jobId)) {
            String mac = table.get(h.getIpAddress());
            if (h.getMacAddress() == null && mac != null) {
                h.setMacAddress(mac);
                // Com o MAC conhecido, o fabricante pode completar ou refinar a identificação.
                oui.vendor(mac).flatMap(vendor -> fingerprinter.match(h.getSysObjectId(), h.getSysDescr(),
                        read(h.getOpenPorts(), INTS), vendor, h.getHints())).ifPresent((Fingerprint f) -> {
                    h.setSuggestedType(f.type().name());
                    if (f.os() != null) {
                        h.setOsInfo(f.os());
                    }
                });
                hosts.save(h);
            }
            recognize(h);
        }
    }

    /** Esse host parece um dispositivo já cadastrado, com outro IP (DHCP)? Marca para a tela oferecer "atualizar o IP". */
    private void recognize(DiscoveryHost h) {
        identity.recognize(h.getIpAddress(), h.getMacAddress(), h.getHostname()).ifPresent(r -> {
            h.setMatchedDeviceId(r.deviceId());
            h.setMatchedBy(r.by());
            hosts.save(h);
        });
    }

    private void progress(long jobId, AtomicInteger scanned, AtomicInteger found, AtomicInteger known, long[] last, boolean force) {
        long now = System.currentTimeMillis();
        synchronized (last) {
            if (!force && now - last[0] < PROGRESS_EVERY_MS) {
                return;
            }
            last[0] = now;
        }
        jdbc.update("UPDATE discovery_jobs SET hosts_scanned = ?, hosts_found = ?, hosts_known = ? WHERE id = ?",
                scanned.get(), found.get(), known.get(), jobId);
        publish(jobId);
    }

    private void finish(long jobId, DiscoveryStatus status, String error, AtomicInteger scanned, AtomicInteger found,
                        AtomicInteger known) {
        jdbc.update("UPDATE discovery_jobs SET status = ?, finished_at = ?, error = ?, hosts_scanned = ?, "
                        + "hosts_found = ?, hosts_known = ? WHERE id = ?",
                status.name(), Instant.now().getEpochSecond(), error, scanned.get(), found.get(), known.get(), jobId);
        DiscoveryJob j = jobs.findById(jobId).orElseThrow();
        String msg = switch (status) {
            case DONE -> "Descoberta de " + j.getCidr() + " concluída: " + found.get() + " novo(s), " + known.get()
                    + " já cadastrado(s)";
            case CANCELLED -> "Descoberta de " + j.getCidr() + " cancelada (" + found.get() + " novo(s) até aqui)";
            default -> "Descoberta de " + j.getCidr() + " falhou: " + error;
        };
        events.publish(events.record(status == DiscoveryStatus.FAILED ? Severity.WARNING : Severity.INFO,
                EventType.DISCOVERY, null, null, msg));
        publish(jobId);
    }

    private void publish(long jobId) {
        jobs.findById(jobId).ifPresent(j -> hub.publish("discovery-progress", view(j)));
    }

    // ------------------------------------------------------------------------------------------------

    private DiscoveryJob find(long id) {
        return jobs.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Descoberta não encontrada"));
    }

    private JobView view(DiscoveryJob j) {
        return new JobView(j.getId(), j.getCidr(), j.getStatus(), j.getHostsTotal(), j.getHostsScanned(),
                j.getHostsFound(), j.getHostsKnown(), j.getStartedAt(), j.getFinishedAt(), j.getError(),
                read(j.getSnmpProfileIds(), LONGS), read(j.getProbePorts(), INTS));
    }

    private HostView view(DiscoveryHost h) {
        String matchedName = null;
        String matchedIp = null;
        if (h.getMatchedDeviceId() != null) {
            var rows = jdbc.queryForList("SELECT name, primary_ip FROM devices WHERE id = ?", h.getMatchedDeviceId());
            if (!rows.isEmpty()) {
                matchedName = (String) rows.get(0).get("name");
                matchedIp = (String) rows.get(0).get("primary_ip");
            }
        }
        return new HostView(h.getId(), h.getIpAddress(), h.getHostname(), h.getMacAddress(), h.getSysDescr(),
                h.getSysObjectId(), h.getSuggestedType(), h.getOsInfo(), read(h.getOpenPorts(), INTS),
                h.getSnmpProfileId(), h.getState(), oui.vendor(h.getMacAddress()).orElse(null),
                oui.vendor(h.getMacAddress()).isEmpty() && OuiRegistry.locallyAdministered(h.getMacAddress()), h.getHints(),
                matchedName == null ? null : h.getMatchedDeviceId(), matchedName, matchedIp,
                matchedName == null ? null : h.getMatchedBy());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> read(String text, TypeReference<List<T>> type) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }
}
