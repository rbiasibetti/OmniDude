package com.omnidude.security;

import com.omnidude.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestão de usuários pelo ADMIN. Salvaguardas: o último administrador ativo nunca some nem perde o perfil, ninguém muda
 * o próprio perfil ou estado (a senha própria tem o seu endpoint), e senha definida por outro é provisória (troca
 * obrigatória no primeiro acesso). Toda mudança que afeta o acesso derruba as sessões abertas do usuário.
 */
@Service
public class UserAdminService {

    public record CreateRequest(
            @NotBlank @Size(min = 3, max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "use letras, números, ponto, hífen e sublinhado") String username,
            @NotBlank @Size(min = 10, max = 128) String password,
            Role role) {
    }

    public record UpdateRequest(Role role, Boolean enabled, @Size(min = 10, max = 128) String password) {
    }

    public record UserView(Long id, String username, Role role, boolean enabled, boolean mustChangePassword, long createdAt) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getRole(), u.isEnabled(), u.isMustChangePassword(), u.getCreatedAt());
        }
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SessionRevocation revocation;

    public UserAdminService(UserRepository users, PasswordEncoder encoder, SessionRevocation revocation) {
        this.users = users;
        this.encoder = encoder;
        this.revocation = revocation;
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAll(org.springframework.data.domain.Sort.by("username")).stream().map(UserView::of).toList();
    }

    @Transactional
    public UserView create(CreateRequest req) {
        String name = req.username().trim();
        if (users.findByUsername(name).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um usuário com esse nome");
        }
        User u = new User();
        u.setUsername(name);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role() == null ? Role.VIEWER : req.role());
        u.setMustChangePassword(true);
        u.setCreatedAt(Instant.now().getEpochSecond());
        return UserView.of(users.save(u));
    }

    @Transactional
    public UserView update(long id, UpdateRequest req, String actor) {
        User u = find(id);
        boolean self = u.getUsername().equals(actor);
        if (self && ((req.role() != null && req.role() != u.getRole()) || (req.enabled() != null && !req.enabled()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Você não pode mudar o seu próprio perfil nem se desativar");
        }
        boolean losesAdmin = u.getRole() == Role.ADMIN && u.isEnabled()
                && ((req.role() != null && req.role() != Role.ADMIN) || Boolean.FALSE.equals(req.enabled()));
        if (losesAdmin && activeAdmins() <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Este é o último administrador ativo");
        }
        boolean affectsAccess = false;
        if (req.role() != null && req.role() != u.getRole()) {
            u.setRole(req.role());
            affectsAccess = true;
        }
        if (req.enabled() != null && req.enabled() != u.isEnabled()) {
            u.setEnabled(req.enabled());
            affectsAccess = true;
        }
        if (req.password() != null && !req.password().isBlank()) {
            if (self) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Troque a sua senha pelo menu do usuário");
            }
            u.setPasswordHash(encoder.encode(req.password()));
            u.setMustChangePassword(true);                           // senha definida por outro é provisória
            affectsAccess = true;
        }
        if (affectsAccess) {
            revocation.revoke(u.getUsername());
        }
        return UserView.of(u);
    }

    @Transactional
    public void delete(long id, String actor) {
        User u = find(id);
        if (u.getUsername().equals(actor)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Você não pode apagar o seu próprio usuário");
        }
        if (u.getRole() == Role.ADMIN && u.isEnabled() && activeAdmins() <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Este é o último administrador ativo");
        }
        users.delete(u);
        revocation.revoke(u.getUsername());
    }

    private long activeAdmins() {
        return users.findAll().stream().filter(x -> x.getRole() == Role.ADMIN && x.isEnabled()).count();
    }

    private User find(long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }
}
