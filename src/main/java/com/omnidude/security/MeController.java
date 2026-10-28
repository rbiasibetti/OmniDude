package com.omnidude.security;

import com.omnidude.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class MeController {

    public record Me(String username, Role role, boolean mustChangePassword) {
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 10, max = 128) String newPassword) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public MeController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping
    public Me me(Principal principal) {
        User u = load(principal);
        return new Me(u.getUsername(), u.getRole(), u.isMustChangePassword());
    }

    @PostMapping("/password")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Principal principal, @Valid @RequestBody ChangePassword body) {
        User u = load(principal);
        if (!encoder.matches(body.currentPassword(), u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Senha atual incorreta");
        }
        if (body.currentPassword().equals(body.newPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A nova senha deve ser diferente da atual");
        }
        u.setPasswordHash(encoder.encode(body.newPassword()));
        u.setMustChangePassword(false);
    }

    private User load(Principal principal) {
        return users.findByUsername(principal.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));
    }
}
