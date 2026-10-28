package com.omnidude.monitoring.scheduler;

import com.omnidude.config.OmniDudeProperties;
import com.omnidude.monitoring.ChecksChangedEvent;
import com.omnidude.monitoring.MonitoringService;
import com.omnidude.monitoring.ServiceCheckRepository;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Um "tick" curto percorre o registro em memória e despacha as checagens vencidas para o executor
 * de virtual threads. Concorrência global limitada por semáforo; nunca duas execuções da mesma
 * checagem ao mesmo tempo. O registro é ressincronizado com o banco periodicamente e a cada mudança.
 */
@Component
@ConditionalOnProperty(name = "omnidude.monitoring.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class PollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollingScheduler.class);

    private static final class Entry {
        volatile int intervalS;
        volatile long nextDueMs;

        Entry(int intervalS, long nextDueMs) {
            this.intervalS = intervalS;
            this.nextDueMs = nextDueMs;
        }
    }

    private final Map<Long, Entry> registry = new ConcurrentHashMap<>();
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Semaphore permits;
    private final MonitoringService monitoring;
    private final ServiceCheckRepository checks;
    private final ExecutorService executor;
    private volatile boolean initialLoadDone;

    public PollingScheduler(OmniDudeProperties props, MonitoringService monitoring, ServiceCheckRepository checks,
                            ExecutorService checkExecutor) {
        this.permits = new Semaphore(props.monitoring().maxConcurrentChecks());
        this.monitoring = monitoring;
        this.checks = checks;
        this.executor = checkExecutor;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onChecksChanged(ChecksChangedEvent event) {
        refresh();
    }

    @Scheduled(fixedDelayString = "#{${omnidude.monitoring.registry-refresh-s} * 1000}")
    public synchronized void refresh() {
        long now = System.currentTimeMillis();
        Set<Long> present = checks.findSchedulable().stream().map(s -> {
            registry.compute(s.getId(), (id, e) -> {
                if (e == null) {
                    // Carga inicial espalha as primeiras execuções (jitter); checagem nova roda já.
                    long delay = initialLoadDone ? 0 : ThreadLocalRandom.current().nextLong(s.getIntervalS() * 1000L);
                    return new Entry(s.getIntervalS(), now + delay);
                }
                e.intervalS = s.getIntervalS();
                return e;
            });
            return s.getId();
        }).collect(Collectors.toSet());
        registry.keySet().retainAll(present);
        initialLoadDone = true;
    }

    @Scheduled(fixedDelayString = "${omnidude.monitoring.scheduler-tick-ms}")
    public void tick() {
        long now = System.currentTimeMillis();
        registry.forEach((id, entry) -> {
            if (now >= entry.nextDueMs && inFlight.add(id)) {
                entry.nextDueMs = now + entry.intervalS * 1000L;
                executor.submit(() -> execute(id));
            }
        });
    }

    private void execute(long id) {
        try {
            permits.acquire();
            try {
                monitoring.runCheck(id);
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("Falha na checagem {}", id, t);
        } finally {
            inFlight.remove(id);
        }
    }

    public int registered() {
        return registry.size();
    }
}
