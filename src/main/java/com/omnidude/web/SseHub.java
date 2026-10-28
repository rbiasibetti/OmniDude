package com.omnidude.web;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Distribui eventos servidor→navegador (ADR-5). O cliente reconcilia o estado ao reconectar. */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** Teto de conexões abertas: cada uma segura uma thread e um socket; sem limite, uma sessão pode esgotar o servidor. */
    static final int MAX_STREAMS = 500;

    public SseEmitter subscribe() {
        if (emitters.size() >= MAX_STREAMS) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Conexões em tempo real demais");
        }
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    public void publish(String name, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (Exception e) {
                emitters.remove(emitter);
                log.debug("Cliente SSE removido: {}", e.toString());
            }
        }
    }

    /** Publica só depois do commit da transação corrente (ou já, se não houver): o cliente nunca vê dado não gravado. */
    public void publishAfterCommit(String name, Object data) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(name, data);
                }
            });
        } else {
            publish(name, data);
        }
    }

    public int clients() {
        return emitters.size();
    }

    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }
}
