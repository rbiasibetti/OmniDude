package com.omnidude.security;

import com.omnidude.config.OmniDudeProperties;
import java.security.SecureRandom;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Primeiro acesso: sem usuários, cria "admin". Senha aleatória é mostrada uma única vez no log. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final OmniDudeProperties props;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, OmniDudeProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String configured = props.bootstrapAdminPassword();
        boolean explicit = configured != null && !configured.isBlank();
        String password = explicit ? configured : randomPassword(16);

        User admin = new User();
        admin.setUsername("admin");
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole(Role.ADMIN);
        admin.setMustChangePassword(!explicit);
        admin.setCreatedAt(Instant.now().getEpochSecond());
        users.save(admin);

        if (explicit) {
            log.info("Usuário 'admin' criado com a senha definida em OMNIDUDE_ADMIN_PASSWORD.");
        } else {
            log.warn("""

                    ==========================================================
                     Primeiro acesso — usuário: admin   senha: {}
                     Esta senha NÃO será exibida novamente; troque-a no login.
                    ==========================================================""", password);
        }
    }

    private static String randomPassword(int length) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
