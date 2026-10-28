package com.omnidude.monitoring;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.EventView;
import com.omnidude.event.Severity;
import com.omnidude.metrics.MetricsWriter;
import com.omnidude.monitoring.probe.Probe;
import com.omnidude.monitoring.probe.SnmpCredentials;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.monitoring.probe.ProbeResult;
import com.omnidude.monitoring.probe.Target;
import com.omnidude.monitoring.state.StatusEngine;
import com.omnidude.web.SseHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executa uma checagem: a sonda roda fora de transação (pode levar segundos); só a aplicação do
 * resultado e a gravação de eventos acontecem na transação. A publicação via SSE vem após o commit.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final ServiceCheckRepository checks;
    private final DeviceRepository devices;
    private final EventService events;
    private final MetricsWriter metrics;
    private final SseHub hub;
    private final TransactionTemplate tx;
    private final SnmpProfileService snmpProfiles;
    private final Map<CheckType, Probe> probes = new EnumMap<>(CheckType.class);

    public MonitoringService(ServiceCheckRepository checks, DeviceRepository devices, EventService events,
                             MetricsWriter metrics, SseHub hub, TransactionTemplate tx, SnmpProfileService snmpProfiles,
                             List<Probe> probeBeans) {
        this.checks = checks;
        this.devices = devices;
        this.events = events;
        this.metrics = metrics;
        this.hub = hub;
        this.tx = tx;
        this.snmpProfiles = snmpProfiles;
        probeBeans.forEach(p -> p.types().forEach(t -> probes.put(t, p)));
    }

    private record Outcome(DeviceStatusView deviceChange, List<EventView> events) {
    }

    public ProbeResult runCheck(long checkId) {
        return runCheck(checkId, false);
    }

    /**
     * @param force ignora a regra "dispositivo DOWN só executa a âncora" (usado pelo botão "verificar agora").
     * @return o resultado da sonda, ou {@code null} se a checagem não foi executada.
     */
    public ProbeResult runCheck(long checkId, boolean force) {
        ServiceCheck check = checks.findById(checkId).orElse(null);
        if (check == null || !check.isEnabled()) {
            return null;
        }
        Device device = devices.findById(check.getDeviceId()).orElse(null);
        if (device == null || !device.isEnabled()) {
            return null;
        }
        // Com o dispositivo DOWN só a âncora continua: detecta a volta sem gastar sondas à toa. Sem âncora
        // (nem ICMP nem SNMP) nada pode ser pulado, senão um dispositivo DOWN nunca mais seria reavaliado.
        if (!force && device.getStatus() == Status.DOWN) {
            ServiceCheck anchor = StatusEngine.anchorOf(checks.findByDeviceId(device.getId()));
            if (anchor != null && !anchor.getId().equals(check.getId())) {
                return null;
            }
        }
        Probe probe = probes.get(check.getType());
        if (probe == null) {
            log.warn("Sem sonda para o tipo {} (checagem {})", check.getType(), checkId);
            return null;
        }

        SnmpCredentials snmp = check.getType() == CheckType.SNMP
                ? snmpProfiles.credentials(device.getSnmpProfileId()).orElse(null) : null;
        Integer port = check.getPort();
        if (check.getType() == CheckType.SNMP && port == null) {
            port = snmpProfiles.port(device.getSnmpProfileId()).orElse(161);
        }
        ProbeResult result = probe.execute(new Target(check.getType(), device.getPrimaryIp(), port,
                check.getTimeoutMs(), ParamsCodec.parse(check.getParams()), snmp));
        long now = Instant.now().getEpochSecond();
        metrics.offerCheckResult(checkId, now, result.success(), result.responseMs());

        Outcome outcome = tx.execute(s -> apply(checkId, result, now));
        if (outcome != null) {
            if (outcome.deviceChange() != null) {
                hub.publish("device-status", outcome.deviceChange());
            }
            outcome.events().forEach(events::publish);
        }
        return result;
    }

    private Outcome apply(long checkId, ProbeResult result, long now) {
        ServiceCheck check = checks.findById(checkId).orElse(null);
        if (check == null) {
            return null;
        }
        Device device = devices.findById(check.getDeviceId()).orElse(null);
        if (device == null) {
            return null;
        }
        Status deviceBefore = device.getStatus();
        Status checkBefore = StatusEngine.applyResult(check, result, now);
        checks.save(check);

        List<ServiceCheck> all = checks.findByDeviceId(device.getId());
        Status deviceAfter = StatusEngine.deviceStatus(all);
        ServiceCheck anchor = StatusEngine.anchorOf(all);
        boolean isAnchor = anchor != null && anchor.getId().equals(check.getId());

        List<EventView> raised = new ArrayList<>();
        // Supressão por dependência: com o dispositivo DOWN, falhas de serviço não geram evento.
        if (!isAnchor && check.getStatus() != checkBefore && deviceBefore != Status.DOWN && deviceAfter != Status.DOWN) {
            String label = device.getName() + " — " + check.getName();
            if (check.getStatus() == Status.DOWN) {
                raised.add(events.record(Severity.WARNING, EventType.CHECK_FAILED, device.getId(), check.getId(),
                        label + " falhou"));
            } else if (checkBefore == Status.DOWN) {
                raised.add(events.record(Severity.INFO, EventType.CHECK_RECOVERED, device.getId(), check.getId(),
                        label + " recuperado"));
            }
        }

        DeviceStatusView change = null;
        if (deviceAfter != deviceBefore) {
            device.setStatus(deviceAfter);
            device.setStatusSince(now);
            device.setUpdatedAt(now);
            devices.save(device);
            change = new DeviceStatusView(device.getId(), deviceAfter, now);
            String label = device.getName() + " (" + device.getPrimaryIp() + ")";
            switch (deviceAfter) {
                case DOWN -> raised.add(events.record(Severity.CRITICAL, EventType.DEVICE_DOWN, device.getId(),
                        check.getId(), label + " está inacessível"));
                case WARNING -> raised.add(events.record(Severity.WARNING, EventType.DEVICE_WARNING, device.getId(),
                        check.getId(), label + " com degradação"));
                case UP -> {
                    if (deviceBefore != Status.UNKNOWN) {
                        raised.add(events.record(Severity.INFO, EventType.DEVICE_UP, device.getId(), check.getId(),
                                label + " voltou"));
                    }
                }
                default -> { }
            }
        }
        return new Outcome(change, raised);
    }
}
