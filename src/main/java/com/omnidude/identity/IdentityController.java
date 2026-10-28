package com.omnidude.identity;

import com.omnidude.identity.IdentityReconciler.LocateResult;
import com.omnidude.identity.IdentityService.IpChange;
import com.omnidude.identity.IdentityService.IpHistory;
import com.omnidude.identity.IdentityService.MacEntry;
import com.omnidude.web.SseHub;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Identidade dos dispositivos: MACs conhecidos, histórico de IP, "procurar novo IP" e adoção na descoberta. */
@RestController
@RequestMapping("/api")
public class IdentityController {

    public record Identity(List<MacEntry> macs, List<IpHistory> history) {
    }

    public record MacRequest(@NotBlank String mac) {
    }

    private final IdentityService identity;
    private final IdentityReconciler reconciler;
    private final SseHub hub;

    public IdentityController(IdentityService identity, IdentityReconciler reconciler, SseHub hub) {
        this.identity = identity;
        this.reconciler = reconciler;
        this.hub = hub;
    }

    @GetMapping("/devices/{id}/identity")
    public Identity get(@PathVariable long id) {
        return new Identity(identity.macs(id), identity.history(id));
    }

    @PostMapping("/devices/{id}/macs")
    @ResponseStatus(HttpStatus.CREATED)
    public List<MacEntry> addMac(@PathVariable long id, @Valid @RequestBody MacRequest body) {
        return identity.addMac(id, body.mac());
    }

    @DeleteMapping("/devices/{id}/macs")
    public List<MacEntry> removeMac(@PathVariable long id, @RequestParam String mac) {
        return identity.removeMac(id, mac);
    }

    @PostMapping("/devices/{id}/locate")
    public LocateResult locate(@PathVariable long id) {
        LocateResult r = reconciler.locate(id);
        if (r.found()) {
            hub.publish("devices-changed", Map.of("id", id));
        }
        return r;
    }

    @PostMapping("/discovery/hosts/{id}/adopt")
    public IpChange adopt(@PathVariable long id) {
        try {
            return identity.adopt(id);
        } finally {
            hub.publish("devices-changed", Map.of("identity", true));
        }
    }
}
