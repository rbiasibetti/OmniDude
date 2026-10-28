package com.omnidude.notification;

import com.omnidude.event.EventType;
import com.omnidude.event.Severity;

/** O que um canal recebe. {@code deviceName} pode ser {@code null} (evento de sistema ou dispositivo apagado). */
public record Message(Long eventId, long ts, Severity severity, EventType type, Long deviceId, String deviceName,
                      String text) {

    /** Linha única, boa para título de e-mail e mensagem de chat. */
    public String headline() {
        return "[" + severity + "] " + text;
    }
}
