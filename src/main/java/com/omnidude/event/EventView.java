package com.omnidude.event;

public record EventView(Long id, long ts, Severity severity, EventType type, Long deviceId,
                        String message, boolean acknowledged) {

    static EventView of(Event e) {
        return new EventView(e.getId(), e.getTs(), e.getSeverity(), e.getType(), e.getDeviceId(),
                e.getMessage(), e.getAcknowledgedAt() != null);
    }
}
