package com.omnidude.web;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Limite por origem para os endpoints públicos (sem autenticação): janela fixa de um minuto. Protege contra varredura
 * de tokens e contra uso abusivo; o token tem 256 bits, então o limite é defesa em profundidade, não a barreira.
 * Usa o endereço da conexão: atrás de um proxy reverso todos os visitantes aparecem com o IP do proxy.
 */
@Component
public class PublicRateLimiter {

    static final int LIMIT_PER_MINUTE = 120;
    private static final long WINDOW_MS = 60_000;

    private static final class Window {
        final long start;
        final AtomicInteger count = new AtomicInteger();

        Window(long start) {
            this.start = start;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** {@code true} se a requisição pode seguir. */
    public boolean allow(String client) {
        return allow(client, System.currentTimeMillis());
    }

    boolean allow(String client, long nowMs) {
        if (windows.size() > 10_000) {
            purge(nowMs);
        }
        Window w = windows.compute(client, (k, cur) -> cur == null || nowMs - cur.start >= WINDOW_MS ? new Window(nowMs) : cur);
        return w.count.incrementAndGet() <= LIMIT_PER_MINUTE;
    }

    private void purge(long nowMs) {
        for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
            if (nowMs - it.next().start >= WINDOW_MS) {
                it.remove();
            }
        }
    }
}
