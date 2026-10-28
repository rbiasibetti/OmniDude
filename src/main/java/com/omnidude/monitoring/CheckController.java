package com.omnidude.monitoring;

import com.omnidude.device.DeviceDtos.CheckView;
import com.omnidude.monitoring.CheckService.CheckRequest;
import com.omnidude.monitoring.CheckService.RunResult;
import com.omnidude.monitoring.ResultsService.Series;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CheckController {

    private final CheckService service;
    private final ResultsService results;
    private final SseHub hub;

    public CheckController(CheckService service, ResultsService results, SseHub hub) {
        this.service = service;
        this.results = results;
        this.hub = hub;
    }

    @GetMapping("/devices/{deviceId}/checks")
    public List<CheckView> list(@PathVariable long deviceId) {
        return service.list(deviceId);
    }

    @PostMapping("/devices/{deviceId}/checks")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckView create(@PathVariable long deviceId, @Valid @RequestBody CheckRequest req) {
        CheckView v = service.create(deviceId, req);
        hub.publish("devices-changed", Map.of("id", deviceId));
        return v;
    }

    @GetMapping("/checks/{id}")
    public CheckView get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/checks/{id}")
    public CheckView update(@PathVariable long id, @Valid @RequestBody CheckRequest req) {
        CheckView v = service.update(id, req);
        hub.publish("devices-changed", Map.of("id", v.deviceId()));
        return v;
    }

    @DeleteMapping("/checks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        long deviceId = service.get(id).deviceId();
        service.delete(id);
        hub.publish("devices-changed", Map.of("id", deviceId));
    }

    @PostMapping("/checks/{id}/run")
    public RunResult run(@PathVariable long id) {
        return service.runNow(id);
    }

    @GetMapping("/checks/{id}/results")
    public Series results(@PathVariable long id, @RequestParam(defaultValue = "1h") String range) {
        return results.series(id, range);
    }
}
