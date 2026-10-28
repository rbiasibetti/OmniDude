package com.omnidude;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.Status;
import com.omnidude.metrics.MetricsWriter;
import com.omnidude.monitoring.CheckType;
import com.omnidude.monitoring.ServiceCheck;
import com.omnidude.monitoring.ServiceCheckRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spike da Fase 0 (ADR-1): ~200 amostras/s em lote + escritas JPA + leituras concorrentes, sem
 * SQLITE_BUSY. A duração padrão é curta para rodar no CI; use -Dspike.seconds=600 para o teste longo.
 */
class SqliteConcurrencySpikeTest extends IntegrationTestBase {

    @Autowired DeviceRepository devices;
    @Autowired ServiceCheckRepository checks;
    @Autowired MetricsWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void sustainsWritesAndReadsWithoutLockErrors() throws Exception {
        int seconds = Integer.getInteger("spike.seconds", 15);
        Device d = new Device();
        d.setName("spike");
        d.setPrimaryIp("203.0.113.77");
        d.setCreatedAt(1);
        d.setUpdatedAt(1);
        d = devices.save(d);
        ServiceCheck c = new ServiceCheck();
        c.setDeviceId(d.getId());
        c.setType(CheckType.ICMP);
        c.setName("Ping");
        c = checks.save(c);
        long checkId = c.getId();
        long deviceId = d.getId();

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong offered = new AtomicLong();
        AtomicLong reads = new AtomicLong();
        AtomicLong jpaWrites = new AtomicLong();
        List<Throwable> errors = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        long t0 = Instant.now().getEpochSecond() - 1_000_000;

        List<Future<?>> tasks = new ArrayList<>();
        tasks.add(pool.submit(() -> {                       // 200 amostras/s
            try {
                long n = 0;
                while (running.get()) {
                    for (int i = 0; i < 20; i++) {
                        writer.offerCheckResult(checkId, t0 + n++, true, 5);
                        offered.incrementAndGet();
                    }
                    Thread.sleep(100);
                }
            } catch (Throwable t) {
                synchronized (errors) { errors.add(t); }
            }
        }));
        tasks.add(pool.submit(() -> {                       // escritas transacionais "de negócio"
            try {
                while (running.get()) {
                    tx.executeWithoutResult(s -> {
                        Device dev = devices.findById(deviceId).orElseThrow();
                        dev.setStatus(dev.getStatus() == Status.UP ? Status.DOWN : Status.UP);
                        devices.save(dev);
                    });
                    jpaWrites.incrementAndGet();
                    Thread.sleep(200);
                }
            } catch (Throwable t) {
                synchronized (errors) { errors.add(t); }
            }
        }));
        for (int r = 0; r < 3; r++) {                       // leitores
            tasks.add(pool.submit(() -> {
                try {
                    while (running.get()) {
                        devices.findAll();
                        jdbc.queryForObject("SELECT count(*) FROM check_results WHERE check_id = ?", Long.class, checkId);
                        reads.incrementAndGet();
                        Thread.sleep(20);
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                }
            }));
        }

        Thread.sleep(seconds * 1000L);
        running.set(false);
        for (Future<?> f : tasks) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        while (writer.pending() > 0) {
            writer.flush();
        }

        long stored = jdbc.queryForObject("SELECT count(*) FROM check_results WHERE check_id = ?", Long.class, checkId);
        System.out.printf("[spike] %ds: %d amostras enviadas, %d gravadas, %d escritas JPA, %d leituras, erros=%d%n",
                seconds, offered.get(), stored, jpaWrites.get(), reads.get(), errors.size());
        assertThat(errors).as("erros de concorrência").isEmpty();
        assertThat(stored).isEqualTo(offered.get());
        assertThat(jpaWrites.get()).isGreaterThan(seconds);
    }
}
