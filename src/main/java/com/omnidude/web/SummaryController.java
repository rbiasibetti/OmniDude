package com.omnidude.web;

import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.event.EventService;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SummaryController {

    /** {@code maintenance}: notificações suspensas (todos os usuários veem o aviso). */
    public record Summary(Map<Status, Long> devices, long unackedEvents, boolean maintenance) {
    }

    private final DeviceRepository devices;
    private final EventService events;
    private final com.omnidude.admin.SettingsService settings;

    public SummaryController(DeviceRepository devices, EventService events, com.omnidude.admin.SettingsService settings) {
        this.devices = devices;
        this.events = events;
        this.settings = settings;
    }

    @GetMapping("/api/summary")
    public Summary summary() {
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0L);
        }
        devices.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getTotal()));
        return new Summary(counts, events.unacknowledgedCount(), settings.maintenanceActive());
    }
}
