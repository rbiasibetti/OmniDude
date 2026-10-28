package com.omnidude.monitoring;

import com.omnidude.admin.SettingsService;
import com.omnidude.device.Device;
import com.omnidude.device.DeviceDtos.CheckView;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.monitoring.CheckValidator.Normalized;
import com.omnidude.monitoring.probe.ProbeResult;
import com.omnidude.monitoring.state.StatusEngine;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.web.ApiException;
import com.omnidude.web.SseHub;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckService {

    public record CheckRequest(
            CheckType type,
            @Size(max = 100) String name,
            @Min(1) @Max(65535) Integer port,
            Map<String, String> params,
            @Min(5) @Max(86400) Integer intervalS,
            @Min(200) @Max(30000) Integer timeoutMs,
            @Min(1) @Max(30000) Integer warnLatencyMs,
            @Min(1) @Max(20) Integer failThreshold,
            @Min(1) @Max(20) Integer recoverThreshold,
            Boolean enabled) {
    }

    public record RunResult(boolean executed, boolean success, Integer responseMs, String detail, CheckView check) {
    }

    private final ServiceCheckRepository checks;
    private final DeviceRepository devices;
    private final SnmpProfileService snmpProfiles;
    private final MonitoringService monitoring;
    private final SettingsService settings;
    private final ApplicationEventPublisher events;
    private final SseHub hub;

    public CheckService(ServiceCheckRepository checks, DeviceRepository devices, SnmpProfileService snmpProfiles,
                        MonitoringService monitoring, SettingsService settings, ApplicationEventPublisher events,
                        SseHub hub) {
        this.checks = checks;
        this.devices = devices;
        this.snmpProfiles = snmpProfiles;
        this.monitoring = monitoring;
        this.settings = settings;
        this.events = events;
        this.hub = hub;
    }

    @Transactional(readOnly = true)
    public List<CheckView> list(long deviceId) {
        device(deviceId);
        return checks.findByDeviceId(deviceId).stream().map(CheckView::of).toList();
    }

    @Transactional(readOnly = true)
    public CheckView get(long id) {
        return CheckView.of(find(id));
    }

    @Transactional
    public CheckView create(long deviceId, CheckRequest req) {
        Device device = device(deviceId);
        if (req.type() == null) {
            throw bad("Informe o tipo da checagem");
        }
        if (req.type() == CheckType.SNMP && device.getSnmpProfileId() == null) {
            throw bad("Selecione um perfil SNMP no dispositivo antes de criar a checagem SNMP");
        }
        Normalized n = CheckValidator.validate(req.type(), req.port(), req.params(), profilePort(device));
        if (req.type() == CheckType.ICMP ? checks.existsByDeviceIdAndType(deviceId, CheckType.ICMP)
                : checks.existsByDeviceIdAndTypeAndPort(deviceId, req.type(), n.port())) {
            throw new ApiException(HttpStatus.CONFLICT, "Este dispositivo já tem uma checagem " + req.type()
                    + (n.port() == null ? "" : " na porta " + n.port()));
        }
        ServiceCheck c = new ServiceCheck();
        c.setDeviceId(deviceId);
        c.setType(req.type());
        c.setPort(n.port());
        c.setParams(ParamsCodec.write(n.params()));
        c.setName(req.name() == null || req.name().isBlank() ? defaultName(req.type(), n.port()) : req.name().trim());
        c.setIntervalS(req.intervalS() == null ? settings.getInt(SettingsService.MONITORING_INTERVAL_S) : req.intervalS());
        c.setTimeoutMs(req.timeoutMs() == null ? defaultTimeout(req.type()) : req.timeoutMs());
        c.setWarnLatencyMs(req.warnLatencyMs());
        if (req.failThreshold() != null) {
            c.setFailThreshold(req.failThreshold());
        }
        if (req.recoverThreshold() != null) {
            c.setRecoverThreshold(req.recoverThreshold());
        }
        c.setEnabled(req.enabled() == null || req.enabled());
        checkLatencyLimit(c);
        c = checks.save(c);
        afterChange(device);
        return CheckView.of(c);
    }

    @Transactional
    public CheckView update(long id, CheckRequest req) {
        ServiceCheck c = find(id);
        Device device = device(c.getDeviceId());
        if (req.type() != null && req.type() != c.getType()) {
            throw bad("O tipo da checagem não pode ser alterado; crie outra");
        }
        Normalized n = CheckValidator.validate(c.getType(), req.port() != null ? req.port() : c.getPort(),
                req.params(), profilePort(device));
        if (c.getType() != CheckType.ICMP
                && checks.existsByDeviceIdAndTypeAndPortAndIdNot(c.getDeviceId(), c.getType(), n.port(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe uma checagem " + c.getType() + " na porta " + n.port());
        }
        String newParams = ParamsCodec.write(n.params());
        boolean targetChanged = !Objects.equals(n.port(), c.getPort()) || !Objects.equals(newParams, c.getParams());
        c.setPort(n.port());
        c.setParams(newParams);
        if (req.name() != null && !req.name().isBlank()) {
            c.setName(req.name().trim());
        }
        if (req.intervalS() != null) {
            c.setIntervalS(req.intervalS());
        }
        if (req.timeoutMs() != null) {
            c.setTimeoutMs(req.timeoutMs());
        }
        c.setWarnLatencyMs(req.warnLatencyMs());   // null = sem limite de latência
        if (req.failThreshold() != null) {
            c.setFailThreshold(req.failThreshold());
        }
        if (req.recoverThreshold() != null) {
            c.setRecoverThreshold(req.recoverThreshold());
        }
        if (req.enabled() != null) {
            c.setEnabled(req.enabled());
        }
        checkLatencyLimit(c);
        if (targetChanged) {                       // o histórico do alvo antigo não vale para o novo
            c.setStatus(Status.UNKNOWN);
            c.setStatusSince(null);
            c.setConsecutiveFailures(0);
            c.setConsecutiveSuccesses(0);
            c.setLastResponseMs(null);
            c.setLastCheckAt(null);
        }
        afterChange(device);
        return CheckView.of(c);
    }

    @Transactional
    public void delete(long id) {
        ServiceCheck c = find(id);
        Device device = device(c.getDeviceId());
        checks.delete(c);
        checks.flush();
        afterChange(device);
    }

    /** "Verificar agora": executa já, fora da agenda e mesmo com o dispositivo DOWN. */
    public RunResult runNow(long id) {
        find(id);
        ProbeResult r = monitoring.runCheck(id, true);
        CheckView view = checks.findById(id).map(CheckView::of).orElse(null);
        if (r == null) {
            return new RunResult(false, false, null, "checagem desabilitada ou dispositivo pausado", view);
        }
        return new RunResult(true, r.success(), r.responseMs(), r.detail(), view);
    }

    // ------------------------------------------------------------------------------------------------

    /** Recalcula o estado do dispositivo (a âncora pode ter mudado) e avisa o agendador. */
    private void afterChange(Device device) {
        Status after = StatusEngine.deviceStatus(checks.findByDeviceId(device.getId()));
        if (after != device.getStatus()) {
            long now = Instant.now().getEpochSecond();
            device.setStatus(after);
            device.setStatusSince(now);
            device.setUpdatedAt(now);
            hub.publishAfterCommit("device-status", new DeviceStatusView(device.getId(), after, now));
        }
        events.publishEvent(new ChecksChangedEvent());
    }

    private void checkLatencyLimit(ServiceCheck c) {
        if (c.getWarnLatencyMs() != null && c.getWarnLatencyMs() >= c.getTimeoutMs()) {
            throw bad("O limite de latência deve ser menor que o timeout");
        }
    }

    private Integer profilePort(Device d) {
        return snmpProfiles.port(d.getSnmpProfileId()).orElse(null);
    }

    private static int defaultTimeout(CheckType type) {
        return type == CheckType.HTTP || type == CheckType.HTTPS ? 5000 : 2000;
    }

    private static String defaultName(CheckType type, Integer port) {
        return switch (type) {
            case ICMP -> "Ping";
            case TCP -> "TCP " + port;
            case HTTP -> "HTTP";
            case HTTPS -> "HTTPS";
            case DNS -> "DNS";
            case SNMP -> "SNMP";
        };
    }

    private ServiceCheck find(long id) {
        return checks.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Checagem não encontrada"));
    }

    private Device device(long id) {
        return devices.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado"));
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }
}
