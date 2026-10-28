package com.omnidude.security;

import com.omnidude.security.UserAdminService.CreateRequest;
import com.omnidude.security.UserAdminService.UpdateRequest;
import com.omnidude.security.UserAdminService.UserView;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Usuários do sistema. Só ADMIN (regra em SecurityConfig). */
@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable long id, @Valid @RequestBody UpdateRequest req, Principal principal) {
        return service.update(id, req, principal.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Principal principal) {
        service.delete(id, principal.getName());
    }
}
