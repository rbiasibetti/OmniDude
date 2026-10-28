package com.omnidude.snmp;

import com.omnidude.snmp.DeviceInventoryService.InterfaceView;
import com.omnidude.snmp.DeviceInventoryService.RefreshResult;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DeviceInventoryController {

    public record MonitoredRequest(@NotNull Boolean monitored) {
    }

    private final DeviceInventoryService service;
    private final SseHub hub;

    public DeviceInventoryController(DeviceInventoryService service, SseHub hub) {
        this.service = service;
        this.hub = hub;
    }

    @GetMapping("/devices/{id}/interfaces")
    public List<InterfaceView> interfaces(@PathVariable long id) {
        return service.interfaces(id);
    }

    @PostMapping("/devices/{id}/refresh")
    public RefreshResult refresh(@PathVariable long id) {
        RefreshResult r = service.refresh(id);
        hub.publish("devices-changed", Map.of("id", id));
        return r;
    }

    @PutMapping("/interfaces/{id}")
    public InterfaceView setMonitored(@PathVariable long id, @Valid @RequestBody MonitoredRequest req) {
        return service.setMonitored(id, req.monitored());
    }
}
