package com.omnidude.event;

import com.omnidude.web.SseHub;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private final EventRepository repo;
    private final SseHub hub;
    private final org.springframework.beans.factory.ObjectProvider<com.omnidude.notification.NotificationService> notifications;

    /** As notificações entram por {@code ObjectProvider}: elas dependem de quase tudo, e o evento é a base de tudo. */
    public EventService(EventRepository repo, SseHub hub,
                        org.springframework.beans.factory.ObjectProvider<com.omnidude.notification.NotificationService> notifications) {
        this.repo = repo;
        this.hub = hub;
        this.notifications = notifications;
    }

    /** Persiste o evento e devolve a visão para publicação (a publicação é feita após o commit). */
    @Transactional
    public EventView record(Severity severity, EventType type, Long deviceId, Long checkId, String message) {
        return record(severity, type, deviceId, checkId, null, message);
    }

    @Transactional
    public EventView record(Severity severity, EventType type, Long deviceId, Long checkId, Long interfaceId,
                            String message) {
        Event e = new Event();
        e.setTs(Instant.now().getEpochSecond());
        e.setSeverity(severity);
        e.setType(type);
        e.setDeviceId(deviceId);
        e.setCheckId(checkId);
        e.setInterfaceId(interfaceId);
        e.setMessage(message);
        return EventView.of(repo.save(e));
    }

    public void publish(EventView view) {
        hub.publish("event", view);
        var service = notifications.getIfAvailable();
        if (service != null) {
            service.dispatch(view);                // assíncrono: nunca atrasa quem gerou o evento
        }
    }

    @Transactional(readOnly = true)
    public List<EventView> search(Severity severity, boolean unacked, Long deviceId, int limit) {
        return repo.search(severity, unacked, deviceId, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)))
                .stream().map(EventView::of).toList();
    }

    @Transactional
    public boolean acknowledge(long id, Long userId) {
        return repo.findById(id).map(e -> {
            if (e.getAcknowledgedAt() == null) {
                e.setAcknowledgedAt(Instant.now().getEpochSecond());
                e.setAcknowledgedBy(userId);
            }
            return true;
        }).orElse(false);
    }

    @Transactional(readOnly = true)
    public long unacknowledgedCount() {
        return repo.countByAcknowledgedAtIsNull();
    }
}
