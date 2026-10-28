package com.omnidude.snmp;

import com.omnidude.snmp.SnmpProfileService.ProfileRequest;
import com.omnidude.snmp.SnmpProfileService.ProfileView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/snmp-profiles")
public class SnmpProfileController {

    private final SnmpProfileService service;

    public SnmpProfileController(SnmpProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProfileView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView create(@Valid @RequestBody ProfileRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ProfileView update(@PathVariable long id, @Valid @RequestBody ProfileRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
