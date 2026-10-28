package com.omnidude.security;

import com.omnidude.config.FilePermissions;
import com.omnidude.config.OmniDudeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Verificações de postura ao iniciar: permissões dos dados e exposição na rede sem TLS. Só avisa e corrige permissões. */
@Component
public class SecurityStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupCheck.class);

    private final Environment env;
    private final OmniDudeProperties props;

    public SecurityStartupCheck(Environment env, OmniDudeProperties props) {
        this.env = env;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        restrictDataFiles();
        warnIfExposed();
    }

    /** O banco guarda hashes de senha e segredos cifrados: só o dono lê (o WAL e o SHM herdam do banco). */
    void restrictDataFiles() {
        Path dir = Path.of(props.dataDir()).toAbsolutePath();
        FilePermissions.restrict(dir);
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().matches("omnidude\\.db(-wal|-shm)?|secret\\.key|fingerprints\\.yml|mac-vendor\\.txt"))
                    .forEach(FilePermissions::restrict);
        } catch (java.io.IOException e) {
            log.warn("Não foi possível conferir as permissões de {}: {}", dir, e.toString());
        }
        FilePermissions.restrict(dir.resolve("backups"));
    }

    void warnIfExposed() {
        String bind = env.getProperty("server.address", "127.0.0.1");
        boolean loopback = bind.equals("127.0.0.1") || bind.equals("::1") || bind.equals("localhost");
        boolean tls = env.getProperty("server.ssl.enabled", Boolean.class, false) || env.getProperty("server.ssl.key-store") != null;
        boolean proxy = !"none".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", "none"));
        if (!loopback && !tls && !proxy) {
            log.warn("O OmniDude escuta em {} sem TLS: senhas e a sessão trafegam em texto puro. Ponha um proxy reverso com HTTPS na "
                    + "frente (e use OMNIDUDE_FORWARD_HEADERS=native e OMNIDUDE_COOKIE_SECURE=true) ou configure server.ssl.*.", bind);
        }
        if (proxy && "framework".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy"))) {
            log.warn("OMNIDUDE_FORWARD_HEADERS=framework confia em X-Forwarded-* de qualquer cliente: quem falsificar o cabeçalho "
                    + "contorna o bloqueio de login e o limite da página pública. Prefira \"native\" (só proxies de OMNIDUDE_TRUSTED_PROXIES).");
        }
        if (!loopback && proxy && !env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            log.warn("Atrás de proxy, defina OMNIDUDE_COOKIE_SECURE=true para o cookie de sessão só trafegar por HTTPS.");
        }
    }
}
