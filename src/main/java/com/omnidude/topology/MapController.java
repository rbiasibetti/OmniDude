package com.omnidude.topology;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/maps")
public class MapController {

    public record CreateMap(@NotBlank @Size(max = 80) String name) {
    }

    public record PlaceNode(@NotNull Long deviceId, Double x, Double y) {
    }

    public record MoveNode(@NotNull Double x, @NotNull Double y, Boolean pinned) {
    }

    private final MapService service;
    private final MapPublicationService publication;

    public MapController(MapService service, MapPublicationService publication) {
        this.service = service;
        this.publication = publication;
    }

    @GetMapping
    public List<MapService.MapSummary> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapService.MapSummary create(@Valid @RequestBody CreateMap body) {
        return service.create(body.name().trim());
    }

    /** Publicação para acesso público (só ADMIN: o link dá acesso). */
    @GetMapping("/{id}/publication")
    public MapPublicationService.Publication publication(@PathVariable long id) {
        return publication.get(id);
    }

    @PutMapping("/{id}/publication")
    public MapPublicationService.Publication publish(@PathVariable long id, @RequestBody MapPublicationService.Update body) {
        return publication.update(id, body);
    }

    @GetMapping("/{id}")
    public MapService.MapView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/nodes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void place(@PathVariable long id, @Valid @RequestBody PlaceNode body) {
        service.placeNode(id, body.deviceId(), body.x(), body.y());
    }

    @PutMapping("/{id}/nodes/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void move(@PathVariable long id, @PathVariable long deviceId, @Valid @RequestBody MoveNode body) {
        service.moveNode(id, deviceId, body.x(), body.y(), body.pinned());
    }

    @DeleteMapping("/{id}/nodes/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long id, @PathVariable long deviceId) {
        service.removeNode(id, deviceId);
    }
}
