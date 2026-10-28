package com.omnidude.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Sessões já abertas continuam válidas depois que um administrador desativa, apaga, rebaixa ou troca a senha de um
 * usuário, a menos que algo as derrube: as autoridades ficam fixas na sessão. Aqui se registra, por usuário, o instante
 * a partir do qual só valem sessões criadas depois (em memória: uma reinicialização já encerra todas as sessões).
 */
@Component
public class SessionRevocation {

    private final Map<String, Long> revokedAt = new ConcurrentHashMap<>();

    public void revoke(String username) {
        revokedAt.put(username, System.currentTimeMillis());
    }

    /** A sessão criada em {@code sessionCreatedMs} deste usuário foi revogada? */
    public boolean isRevoked(String username, long sessionCreatedMs) {
        Long at = revokedAt.get(username);
        return at != null && sessionCreatedMs <= at;
    }
}
