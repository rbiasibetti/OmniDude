package com.omnidude.topology;

import com.omnidude.topology.LinkService.LinkRequest;
import com.omnidude.topology.LinkService.LinkView;
import com.omnidude.topology.TopologyService.BatchResult;
import com.omnidude.topology.TopologyService.LldpResult;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LinkController {

    private final LinkService links;
    private final TopologyService topology;
    private final SseHub hub;

    public LinkController(LinkService links, TopologyService topology, SseHub hub) {
        this.links = links;
        this.topology = topology;
        this.hub = hub;
    }

    @GetMapping("/links")
    public List<LinkView> list() {
        return links.all();
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkView create(@Valid @RequestBody LinkRequest req) {
        LinkView v = links.create(req);
        changed();
        return v;
    }

    @PutMapping("/links/{id}")
    public LinkView update(@PathVariable long id, @Valid @RequestBody LinkRequest req) {
        LinkView v = links.update(id, req);
        changed();
        return v;
    }

    @PostMapping("/links/{id}/confirm")
    public LinkView confirm(@PathVariable long id) {
        LinkView v = links.confirm(id);
        changed();
        return v;
    }

    @DeleteMapping("/links/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        links.delete(id);
        changed();
    }

    /** Lê o LLDP de todos os dispositivos SNMP e propõe enlaces. */
    @PostMapping("/topology/refresh")
    public BatchResult refreshAll() {
        BatchResult r = topology.refreshAll();
        changed();
        return r;
    }

    @PostMapping("/devices/{id}/lldp")
    public LldpResult refreshDevice(@PathVariable long id) {
        LldpResult r = topology.refresh(id);
        changed();
        return r;
    }

    private void changed() {
        hub.publish("devices-changed", Map.of("links", true));
    }
}
