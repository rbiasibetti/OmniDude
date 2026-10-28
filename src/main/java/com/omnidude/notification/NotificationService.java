package com.omnidude.notification;

import com.omnidude.admin.SettingsService;
import com.omnidude.event.EventType;
import com.omnidude.event.EventView;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Entrega de notificações. Para cada evento publicado, as regras escolhem os canais; cada canal recebe no máximo uma
 * mensagem por evento, e antes de enviar passa por: modo de manutenção, limite por minuto, anti-repetição. O envio é
 * assíncrono (nunca atrasa a checagem que gerou o evento), com até 3 tentativas para falha de rede ou 5xx. Cada
 * resultado final (enviado, falhou, suprimido) fica em {@code notification_log}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    static final Set<EventType> RECOVERY = EnumSet.of(EventType.DEVICE_UP, EventType.CHECK_RECOVERED, EventType.LINK_UP);
    static final int MAX_PER_MINUTE = 30;
    private static final int MAX_ATTEMPTS = 3;

    public record TestResult(boolean ok, String error) {
    }

    /** Pausas antes da 2ª e da 3ª tentativa; os testes as encurtam. */
    volatile long[] retryDelaysMs = {2_000, 8_000};

    private final NotificationRuleRepository rules;
    private final NotificationChannelRepository channels;
    private final NotificationChannelService channelService;
    private final SettingsService settings;
    private final JdbcTemplate jdbc;
    private final ExecutorService executor;
    private final Map<ChannelType, Notifier> notifiers = new EnumMap<>(ChannelType.class);
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Long>> recent = new ConcurrentHashMap<>();

    public NotificationService(NotificationRuleRepository rules, NotificationChannelRepository channels,
                               NotificationChannelService channelService, SettingsService settings, JdbcTemplate jdbc,
                               ExecutorService checkExecutor, List<Notifier> all) {
        this.rules = rules;
        this.channels = channels;
        this.channelService = channelService;
        this.settings = settings;
        this.jdbc = jdbc;
        this.executor = checkExecutor;
        all.forEach(n -> notifiers.put(n.type(), n));
    }

    /** Chamado quando um evento é publicado (já gravado). Não bloqueia. */
    public void dispatch(EventView event) {
        executor.submit(() -> {
            try {
                process(event);
            } catch (RuntimeException e) {
                log.warn("Falha ao processar notificações do evento {}: {}", event.id(), e.toString());
            }
        });
    }

    /** Síncrono, para os testes e para {@link #dispatch}. */
    void process(EventView event) {
        List<NotificationRule> all = rules.findByEnabledTrue();
        Set<Long> targets = new LinkedHashSet<>();
        for (NotificationRule r : all) {
            if (matches(r, event)) {
                targets.add(r.getChannelId());
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Message message = message(event);
        for (long channelId : targets) {
            NotificationChannel channel = channels.findById(channelId).orElse(null);
            if (channel == null || !channel.isEnabled()) {
                continue;
            }
            String suppressed = suppressionReason(channelId, event.message());
            if (suppressed != null) {
                record(channel, message.eventId(), "SUPPRESSED", suppressed, 0);
                continue;
            }
            deliver(channel, message, true);
        }
    }

    static boolean matches(NotificationRule r, EventView e) {
        boolean recovery = r.isIncludeRecovery() && RECOVERY.contains(e.type());
        boolean severity = e.severity().ordinal() >= r.getMinSeverity().ordinal() || recovery;
        boolean type = r.getEventTypes() == null || r.getEventTypes().isBlank()
                || List.of(r.getEventTypes().split(",")).contains(e.type().name());
        boolean device = r.getDeviceId() == null || r.getDeviceId().equals(e.deviceId());
        return severity && type && device;
    }

    /** Motivo para não enviar, ou {@code null}. Registrar o envio acontece aqui para o limite e a repetição valerem já. */
    private String suppressionReason(long channelId, String text) {
        if (settings.maintenanceActive()) {
            return "modo de manutenção";
        }
        long now = System.currentTimeMillis();
        Deque<Long> window = recent.computeIfAbsent(channelId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= MAX_PER_MINUTE) {
                return "limite de " + MAX_PER_MINUTE + " mensagens por minuto neste canal";
            }
            int dedupe = settings.getInt(SettingsService.NOTIFY_DEDUPE_S);
            String key = channelId + "|" + text;
            Long previous = lastSent.get(key);
            if (dedupe > 0 && previous != null && now - previous < dedupe * 1000L) {
                return "mensagem repetida dentro de " + dedupe + " s";
            }
            window.addLast(now);
            lastSent.put(key, now);
            if (lastSent.size() > 5000) {
                lastSent.values().removeIf(t -> now - t > 3_600_000);
            }
        }
        return null;
    }

    private Message message(EventView e) {
        String name = null;
        if (e.deviceId() != null) {
            List<String> n = jdbc.queryForList("SELECT name FROM devices WHERE id = ?", String.class, e.deviceId());
            name = n.isEmpty() ? null : n.get(0);
        }
        return new Message(e.id(), e.ts(), e.severity(), e.type(), e.deviceId(), name, e.message());
    }

    /** Envia com tentativas (se {@code retry}) e registra o resultado. Devolve o erro, ou {@code null} se enviou. */
    private String deliver(NotificationChannel channel, Message message, boolean retry) {
        Notifier notifier = notifiers.get(channel.getType());
        Map<String, String> cfg = channelService.config(channel);
        String error = null;
        int attempts = 0;
        for (int i = 0; i < (retry ? MAX_ATTEMPTS : 1); i++) {
            attempts++;
            try {
                notifier.send(cfg, message);
                error = null;
                break;
            } catch (NotifyException e) {
                error = e.getMessage();
                if (!e.retryable() || i == MAX_ATTEMPTS - 1 || !retry) {
                    break;
                }
                try {
                    Thread.sleep(retryDelaysMs[Math.min(i, retryDelaysMs.length - 1)]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    error = "envio interrompido";
                    break;
                }
            } catch (RuntimeException e) {
                error = "erro inesperado: " + e.getClass().getSimpleName();
                break;
            }
        }
        record(channel, message.eventId(), retry ? (error == null ? "SENT" : "FAILED") : "TEST", error, attempts);
        if (error != null && retry) {
            log.warn("Notificação para o canal '{}' falhou após {} tentativa(s): {}", channel.getName(), attempts, error);
        }
        return error;
    }

    /** Envia uma mensagem de teste ao canal, sem tentativas extras, e devolve o resultado para a tela. */
    public TestResult test(long channelId) {
        NotificationChannel channel = channelService.find(channelId);
        Message m = new Message(null, Instant.now().getEpochSecond(), com.omnidude.event.Severity.INFO, EventType.SYSTEM,
                null, null, "Mensagem de teste do OmniDude: se você a recebeu, o canal '" + channel.getName() + "' funciona.");
        String error = deliver(channel, m, false);
        return new TestResult(error == null, error);
    }

    private void record(NotificationChannel channel, Long eventId, String status, String detail, int attempts) {
        // event_id só entra se o evento ainda existe (a retenção pode ter apagado): a chave estrangeira nunca derruba o registro
        jdbc.update("INSERT INTO notification_log(ts, channel_id, channel, event_id, status, detail, attempts) "
                        + "VALUES (?,?,?,(SELECT id FROM events WHERE id = ?),?,?,?)", Instant.now().getEpochSecond(),
                channel.getId(), channel.getName(), eventId, status, detail == null ? null : (detail.length() > 300 ? detail.substring(0, 300) : detail),
                Math.max(attempts, 1));
    }

    public record LogEntry(Long id, long ts, String channel, Long eventId, String status, String detail, int attempts) {
    }

    public List<LogEntry> log(int limit) {
        return jdbc.query("SELECT id, ts, channel, event_id, status, detail, attempts FROM notification_log "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, i) -> new LogEntry(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5), rs.getString(6), rs.getInt(7)),
                Math.min(Math.max(limit, 1), 500));
    }
}
