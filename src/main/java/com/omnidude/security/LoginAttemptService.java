package com.omnidude.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Bloqueio temporário após falhas de login (em memória; reinicia com o processo).
 *
 * <p>Dois níveis, para não trocar força bruta por negação de serviço: o bloqueio comum vale para o par
 * <b>usuário + origem</b> (quem erra a senha de {@code admin} de um IP não tranca {@code admin} para o resto do mundo),
 * e um teto mais alto por usuário, somando todas as origens, barra quem espalha as tentativas. As contagens vencem
 * sozinhas (falhas antigas não se acumulam para sempre) e só contam usuários que existem, então inventar nomes não
 * enche a memória. Atrás de um proxy reverso, "origem" é o IP que o proxy repassa (ver OMNIDUDE_FORWARD_HEADERS).
 */
@Component
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final int MAX_FAILURES_ANY_SOURCE = 30;
    static final Duration LOCK = Duration.ofMinutes(5);
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_ENTRIES = 20_000;

    private record State(int failures, Instant lastFailure, Instant lockedUntil) {
    }

    private final Map<String, State> bySource = new ConcurrentHashMap<>();
    private final Map<String, State> byUser = new ConcurrentHashMap<>();
    private final UserRepository users;

    public LoginAttemptService(UserRepository users) {
        this.users = users;
    }

    /** Bloqueado para a origem da requisição em andamento (ou para qualquer origem, pelo teto por usuário). */
    public boolean isLocked(String username) {
        return isLocked(username, currentSource(), Instant.now());
    }

    boolean isLocked(String username, String source, Instant now) {
        return locked(byUser, username, now) || locked(bySource, username + "|" + source, now);
    }

    private static boolean locked(Map<String, State> map, String key, Instant now) {
        State s = map.get(key);
        if (s == null || s.lockedUntil() == null) {
            return false;
        }
        if (now.isAfter(s.lockedUntil())) {
            map.remove(key);
            return false;
        }
        return true;
    }

    @EventListener
    void onFailure(AuthenticationFailureBadCredentialsEvent e) {
        String source = e.getAuthentication().getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : currentSource();
        failure(e.getAuthentication().getName(), source, Instant.now());
    }

    void failure(String username, String source, Instant now) {
        if (users.findByUsername(username).isEmpty()) {
            return;                                               // nome inexistente: nada a proteger e nada a guardar
        }
        if (bySource.size() > MAX_ENTRIES || byUser.size() > MAX_ENTRIES) {
            purge(now);
        }
        count(bySource, username + "|" + source, MAX_FAILURES, now);
        count(byUser, username, MAX_FAILURES_ANY_SOURCE, now);
    }

    private static void count(Map<String, State> map, String key, int limit, Instant now) {
        map.merge(key, new State(1, now, null), (old, one) -> {
            boolean stale = old.lockedUntil() == null && now.isAfter(old.lastFailure().plus(WINDOW));
            int n = stale ? 1 : old.failures() + 1;
            Instant until = n >= limit ? now.plus(LOCK) : old.lockedUntil();
            return new State(n, now, until);
        });
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent e) {
        String name = e.getAuthentication().getName();
        String source = e.getAuthentication().getDetails() instanceof WebAuthenticationDetails d ? d.getRemoteAddress() : currentSource();
        bySource.remove(name + "|" + source);
        State all = byUser.get(name);
        if (all != null && all.lockedUntil() == null) {
            byUser.remove(name);                                  // acertou: as falhas espalhadas deixam de contar
        }
    }

    private void purge(Instant now) {
        bySource.entrySet().removeIf(en -> expired(en.getValue(), now));
        byUser.entrySet().removeIf(en -> expired(en.getValue(), now));
    }

    private static boolean expired(State s, Instant now) {
        return s.lockedUntil() != null ? now.isAfter(s.lockedUntil()) : now.isAfter(s.lastFailure().plus(WINDOW));
    }

    private static String currentSource() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest().getRemoteAddr() : "?";
    }
}
