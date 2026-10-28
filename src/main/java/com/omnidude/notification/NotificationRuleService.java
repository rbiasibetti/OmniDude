package com.omnidude.notification;

import com.omnidude.device.DeviceRepository;
import com.omnidude.event.EventType;
import com.omnidude.event.Severity;
import com.omnidude.web.ApiException;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Regras: qual canal recebe o quê. Uma regra casa por gravidade mínima, tipos de evento e dispositivo. */
@Service
public class NotificationRuleService {

    public record RuleRequest(@NotNull Long channelId, Severity minSeverity, List<EventType> eventTypes, Long deviceId,
                              Boolean enabled, Boolean includeRecovery) {
    }

    public record RuleView(Long id, Long channelId, String channelName, Severity minSeverity, List<EventType> eventTypes,
                           Long deviceId, String deviceName, boolean enabled, boolean includeRecovery) {
    }

    private final NotificationRuleRepository rules;
    private final NotificationChannelRepository channels;
    private final DeviceRepository devices;

    public NotificationRuleService(NotificationRuleRepository rules, NotificationChannelRepository channels,
                                   DeviceRepository devices) {
        this.rules = rules;
        this.channels = channels;
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public RuleView create(RuleRequest req) {
        NotificationRule r = new NotificationRule();
        apply(r, req);
        return view(rules.save(r));
    }

    @Transactional
    public RuleView update(long id, RuleRequest req) {
        NotificationRule r = rules.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Regra não encontrada"));
        apply(r, req);
        return view(r);
    }

    @Transactional
    public void delete(long id) {
        if (!rules.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Regra não encontrada");
        }
        rules.deleteById(id);
    }

    private void apply(NotificationRule r, RuleRequest req) {
        if (!channels.existsById(req.channelId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Canal inexistente");
        }
        if (req.deviceId() != null && !devices.existsById(req.deviceId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dispositivo inexistente");
        }
        r.setChannelId(req.channelId());
        r.setMinSeverity(req.minSeverity() == null ? Severity.WARNING : req.minSeverity());
        r.setEventTypes(req.eventTypes() == null || req.eventTypes().isEmpty() ? null
                : req.eventTypes().stream().distinct().map(Enum::name).collect(Collectors.joining(",")));
        r.setDeviceId(req.deviceId());
        r.setEnabled(req.enabled() == null || req.enabled());
        r.setIncludeRecovery(req.includeRecovery() == null || req.includeRecovery());
    }

    private RuleView view(NotificationRule r) {
        String channel = channels.findById(r.getChannelId()).map(NotificationChannel::getName).orElse("?");
        String device = r.getDeviceId() == null ? null : devices.findById(r.getDeviceId()).map(d -> d.getName()).orElse(null);
        List<EventType> types = r.getEventTypes() == null || r.getEventTypes().isBlank() ? List.of()
                : Arrays.stream(r.getEventTypes().split(",")).map(EventType::valueOf).toList();
        return new RuleView(r.getId(), r.getChannelId(), channel, r.getMinSeverity(), types, r.getDeviceId(), device,
                r.isEnabled(), r.isIncludeRecovery());
    }
}
