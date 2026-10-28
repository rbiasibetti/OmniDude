package com.omnidude.device;

import com.omnidude.device.DeviceDtos.DeviceRequest;
import com.omnidude.device.DeviceDtos.DeviceView;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService service;
    private final SseHub hub;

    public DeviceController(DeviceService service, SseHub hub) {
        this.service = service;
        this.hub = hub;
    }

    @GetMapping
    public List<DeviceView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public DeviceView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView create(@Valid @RequestBody DeviceRequest req) {
        DeviceView v = service.create(req);
        hub.publish("devices-changed", Map.of("id", v.id()));
        return v;
    }

    @PutMapping("/{id}")
    public DeviceView update(@PathVariable long id, @Valid @RequestBody DeviceRequest req) {
        DeviceView v = service.update(id, req);
        hub.publish("devices-changed", Map.of("id", id));
        return v;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
        hub.publish("devices-changed", Map.of("id", id));
    }
}
