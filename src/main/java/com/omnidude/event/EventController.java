package com.omnidude.event;

import com.omnidude.security.UserRepository;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService events;
    private final UserRepository users;

    public EventController(EventService events, UserRepository users) {
        this.events = events;
        this.users = users;
    }

    @GetMapping
    public List<EventView> list(@RequestParam(required = false) Severity severity,
                                @RequestParam(defaultValue = "false") boolean unacked,
                                @RequestParam(required = false) Long device,
                                @RequestParam(defaultValue = "100") int limit) {
        return events.search(severity, unacked, device, limit);
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Void> ack(@PathVariable long id, Principal principal) {
        Long userId = users.findByUsername(principal.getName()).map(u -> u.getId()).orElse(null);
        return events.acknowledge(id, userId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
