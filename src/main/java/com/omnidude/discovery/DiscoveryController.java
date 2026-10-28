package com.omnidude.discovery;

import com.omnidude.discovery.DiscoveryImportService.ImportRequest;
import com.omnidude.discovery.DiscoveryImportService.ImportResult;
import com.omnidude.discovery.DiscoveryService.HostView;
import com.omnidude.discovery.DiscoveryService.JobView;
import com.omnidude.discovery.DiscoveryService.StartRequest;
import com.omnidude.security.UserRepository;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final DiscoveryService service;
    private final DiscoveryImportService importer;
    private final UserRepository users;
    private final SseHub hub;

    public DiscoveryController(DiscoveryService service, DiscoveryImportService importer, UserRepository users, SseHub hub) {
        this.service = service;
        this.importer = importer;
        this.users = users;
        this.hub = hub;
    }

    @PostMapping("/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobView start(@Valid @RequestBody StartRequest req, Principal principal) {
        Long userId = users.findByUsername(principal.getName()).map(u -> u.getId()).orElse(null);
        return service.start(req, userId);
    }

    @GetMapping("/jobs")
    public List<JobView> list() {
        return service.list();
    }

    @GetMapping("/jobs/{id}")
    public JobView get(@PathVariable long id) {
        return service.get(id);
    }

    @GetMapping("/jobs/{id}/hosts")
    public List<HostView> hosts(@PathVariable long id) {
        return service.hosts(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    public JobView cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @PostMapping("/jobs/{id}/import")
    public ImportResult importHosts(@PathVariable long id, @Valid @RequestBody ImportRequest req) {
        ImportResult r = importer.importHosts(id, req);
        hub.publish("devices-changed", Map.of("import", true));
        return r;
    }

    @PostMapping("/hosts/{id}/ignore")
    public HostView ignore(@PathVariable long id) {
        return service.ignore(id);
    }
}
