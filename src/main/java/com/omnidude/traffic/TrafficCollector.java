package com.omnidude.traffic;

import com.omnidude.admin.SettingsService;
import com.omnidude.config.OmniDudeProperties;
import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.NetInterface;
import com.omnidude.device.NetInterfaceRepository;
import com.omnidude.device.Status;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.EventView;
import com.omnidude.event.Severity;
import com.omnidude.metrics.MetricsWriter;
import com.omnidude.metrics.RateCalculator;
import com.omnidude.metrics.RateCalculator.Counters;
import com.omnidude.metrics.RateCalculator.Rate;
import com.omnidude.snmp.IfTablePoller;
import com.omnidude.snmp.IfTablePoller.Reading;
import com.omnidude.snmp.SnmpAccess;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.topology.LinkRepository;
import com.omnidude.topology.LinkService;
import com.omnidude.traffic.TrafficService.Latest;
import com.omnidude.web.SseHub;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coleta periódica de contadores das interfaces monitoradas. Uma consulta SNMP por dispositivo e ciclo (não uma por
 * interface); dispositivos DOWN ou desabilitados são pulados, já que só dariam timeout. Taxas saem de duas leituras
 * consecutivas, guardadas só em memória: depois de um reinício a primeira amostra de cada interface não tem taxa.
 */
@Component
public class TrafficCollector {

    private static final Logger log = LoggerFactory.getLogger(TrafficCollector.class);

    private record Previous(Counters counters, Long inErrors, Long outErrors) {
    }

    private static final class Entry {
        volatile long nextDueMs;

        Entry(long nextDueMs) {
            this.nextDueMs = nextDueMs;
        }
    }

    /** Taxas de um enlace empurradas ao mapa por SSE ({@code link-traffic}). */
    public record LinkUpdate(Long linkId, TrafficService.LinkTraffic traffic) {
    }

    private final Map<Long, Entry> registry = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Long, Previous> previous = new ConcurrentHashMap<>();
    private final OmniDudeProperties.Traffic config;
    private final SettingsService settings;
    private final Semaphore permits;
    private final DeviceRepository devices;
    private final NetInterfaceRepository interfaces;
    private final LinkRepository linkRepo;
    private final LinkService links;
    private final SnmpProfileService profiles;
    private final IfTablePoller poller;
    private final MetricsWriter metrics;
    private final TrafficService traffic;
    private final EventService events;
    private final SseHub hub;
    private final TransactionTemplate tx;
    private final ExecutorService executor;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private volatile boolean initialLoadDone;

    public TrafficCollector(OmniDudeProperties props, DeviceRepository devices, NetInterfaceRepository interfaces,
                            LinkRepository linkRepo, LinkService links, SnmpProfileService profiles,
                            IfTablePoller poller, MetricsWriter metrics, TrafficService traffic, EventService events,
                            SseHub hub, TransactionTemplate tx, ExecutorService checkExecutor,
                            org.springframework.jdbc.core.JdbcTemplate jdbc, SettingsService settings) {
        this.settings = settings;
        this.config = props.traffic();
        this.permits = new Semaphore(config.maxConcurrent());
        this.devices = devices;
        this.interfaces = interfaces;
        this.linkRepo = linkRepo;
        this.links = links;
        this.profiles = profiles;
        this.poller = poller;
        this.metrics = metrics;
        this.traffic = traffic;
        this.events = events;
        this.hub = hub;
        this.tx = tx;
        this.executor = checkExecutor;
        this.jdbc = jdbc;
    }

    /** Dispositivos com SNMP, ativos e com ao menos uma interface monitorada. */
    @Scheduled(fixedDelay = 10_000)
    public synchronized void refresh() {
        if (!config.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Long> ids = jdbc.queryForList(
                "SELECT DISTINCT d.id FROM devices d JOIN interfaces i ON i.device_id = d.id "
                        + "WHERE d.enabled = 1 AND d.snmp_profile_id IS NOT NULL AND i.monitored = 1", Long.class);
        for (Long id : ids) {
            registry.computeIfAbsent(id, k -> new Entry(now + (initialLoadDone
                    ? 0 : ThreadLocalRandom.current().nextLong(settings.getInt(SettingsService.TRAFFIC_INTERVAL_S) * 1000L))));
        }
        registry.keySet().retainAll(Set.copyOf(ids));
        initialLoadDone = true;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!config.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        registry.forEach((id, entry) -> {
            if (now >= entry.nextDueMs && inFlight.add(id)) {
                entry.nextDueMs = now + settings.getInt(SettingsService.TRAFFIC_INTERVAL_S) * 1000L;
                executor.submit(() -> execute(id));
            }
        });
    }

    private void execute(long deviceId) {
        try {
            permits.acquire();
            try {
                pollDevice(deviceId);
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("Falha na coleta de tráfego do dispositivo {}", deviceId, t);
        } finally {
            inFlight.remove(deviceId);
        }
    }

    /** Coleta um dispositivo agora. Devolve quantas interfaces tiveram contadores lidos (0 se foi pulado). */
    public int pollDevice(long deviceId) {
        Device device = devices.findById(deviceId).orElse(null);
        if (device == null || !device.isEnabled() || device.getStatus() == Status.DOWN) {
            return 0;
        }
        Optional<SnmpAccess> access = profiles.access(device.getSnmpProfileId());
        List<NetInterface> monitored = interfaces.findByDeviceIdOrderByIfIndexAsc(deviceId).stream()
                .filter(NetInterface::isMonitored).toList();
        if (access.isEmpty() || monitored.isEmpty()) {
            return 0;
        }
        Map<Integer, Reading> readings;
        try {
            readings = poller.read(device.getPrimaryIp(), access.get(),
                    monitored.stream().map(NetInterface::getIfIndex).toList());
        } catch (SnmpException e) {
            log.debug("Coleta de tráfego de {} falhou: {}", device.getPrimaryIp(), e.getMessage());
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        long ts = nowMs / 1000;
        int collected = 0;
        List<Object[]> operChanges = new ArrayList<>();
        for (NetInterface iface : monitored) {
            Reading r = readings.get(iface.getIfIndex());
            if (r == null) {
                continue;
            }
            if (r.hasCounters()) {
                collected++;
                record(iface, r, nowMs, ts);
            }
            if (r.operStatus() != null && !r.operStatus().equals(iface.getOperStatus())) {
                operChanges.add(new Object[] {iface.getId(), iface.getOperStatus(), r.operStatus()});
            }
        }
        if (!operChanges.isEmpty()) {
            applyOperChanges(device, operChanges);
        }
        publishLinks(deviceId);
        return collected;
    }

    private void record(NetInterface iface, Reading r, long nowMs, long ts) {
        Counters cur = new Counters(nowMs, r.inOctets(), r.outOctets(), r.bits());
        Previous prev = previous.get(iface.getId());
        Rate rate = RateCalculator.compute(prev == null ? null : prev.counters(), cur, iface.getSpeedBps());
        Long inErr = prev == null ? null : RateCalculator.delta(prev.inErrors(), r.inErrors(), 32);
        Long outErr = prev == null ? null : RateCalculator.delta(prev.outErrors(), r.outErrors(), 32);
        previous.put(iface.getId(), new Previous(cur, r.inErrors(), r.outErrors()));
        metrics.offerInterfaceMetric(iface.getId(), ts, r.inOctets(), r.outOctets(), rate.inBps(), rate.outBps(),
                inErr, outErr);
        traffic.record(iface.getId(), new Latest(ts, rate.inBps(), rate.outBps(), inErr, outErr));
    }

    /** Grava o novo ifOperStatus e, se a interface liga dois dispositivos, gera LINK_DOWN / LINK_UP. */
    private void applyOperChanges(Device device, List<Object[]> changes) {
        List<EventView> raised = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (Object[] c : changes) {
                NetInterface iface = interfaces.findById((Long) c[0]).orElse(null);
                if (iface == null) {
                    continue;
                }
                Integer before = (Integer) c[1];
                Integer after = (Integer) c[2];
                iface.setOperStatus(after);
                boolean flipped = before != null && ((before == 1 && after == 2) || (before == 2 && after == 1));
                if (flipped && linkRepo.confirmedLinkUses(iface.getId())) {
                    boolean down = after == 2;
                    raised.add(events.record(down ? Severity.WARNING : Severity.INFO,
                            down ? EventType.LINK_DOWN : EventType.LINK_UP, device.getId(), null, iface.getId(),
                            device.getName() + " — interface " + iface.getName() + (down ? " caiu" : " voltou")));
                }
            }
        });
        raised.forEach(events::publish);
    }

    private void publishLinks(long deviceId) {
        List<LinkUpdate> updates = links.ofDevice(deviceId).stream().filter(l -> l.traffic() != null)
                .map(l -> new LinkUpdate(l.id(), l.traffic())).toList();
        if (!updates.isEmpty()) {
            hub.publish("link-traffic", updates);
        }
    }

    public int registered() {
        return registry.size();
    }
}
