package com.omnidude.metrics;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Único caminho de escrita de métricas (ADR-1): as threads de checagem só enfileiram; uma thread
 * grava em lote a cada segundo, numa transação, para não disputar o lock de escrita do SQLite.
 */
@Component
public class MetricsWriter {

    private static final Logger log = LoggerFactory.getLogger(MetricsWriter.class);
    private static final int MAX_BATCH = 5000;

    private final BlockingQueue<Object[]> checkResults = new LinkedBlockingQueue<>(100_000);
    private final BlockingQueue<Object[]> interfaceMetrics = new LinkedBlockingQueue<>(100_000);
    private final AtomicLong dropped = new AtomicLong();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MetricsWriter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void offerCheckResult(long checkId, long ts, boolean success, Integer responseMs) {
        if (!checkResults.offer(new Object[] {checkId, ts, success ? 1 : 0, responseMs, checkId})) {
            if (dropped.incrementAndGet() % 1000 == 1) {
                log.warn("Fila de métricas cheia; {} amostras descartadas até agora", dropped.get());
            }
        }
    }

    /** Taxas e erros são {@code null} na primeira amostra, após reset de contador ou quando não há leitura. */
    public void offerInterfaceMetric(long interfaceId, long ts, long inOctets, long outOctets, Long inBps, Long outBps,
                                     Long inErrors, Long outErrors) {
        if (!interfaceMetrics.offer(new Object[] {interfaceId, ts, inOctets, outOctets, inBps, outBps, inErrors,
                outErrors, interfaceId})) {
            if (dropped.incrementAndGet() % 1000 == 1) {
                log.warn("Fila de métricas cheia; {} amostras descartadas até agora", dropped.get());
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<Object[]> results = new ArrayList<>();
        checkResults.drainTo(results, MAX_BATCH);
        List<Object[]> traffic = new ArrayList<>();
        interfaceMetrics.drainTo(traffic, MAX_BATCH);
        if (results.isEmpty() && traffic.isEmpty()) {
            return;
        }
        try {
            tx.executeWithoutResult(s -> {
                if (!results.isEmpty()) {
                    jdbc.batchUpdate("INSERT OR REPLACE INTO check_results(check_id, ts, success, response_ms) "
                            + "SELECT ?,?,?,? WHERE EXISTS (SELECT 1 FROM service_checks WHERE id = ?)", results);
                }
                if (!traffic.isEmpty()) {
                    jdbc.batchUpdate("INSERT OR REPLACE INTO interface_metrics(interface_id, ts, in_octets, out_octets, "
                            + "in_bps, out_bps, in_errors, out_errors) SELECT ?,?,?,?,?,?,?,? "
                            + "WHERE EXISTS (SELECT 1 FROM interfaces WHERE id = ?)", traffic);
                }
            });
        } catch (RuntimeException e) {
            log.error("Falha ao gravar {} resultados e {} amostras de tráfego: {}", results.size(), traffic.size(),
                    e.toString());
        }
    }

    @PreDestroy
    void drain() {
        while (!checkResults.isEmpty() || !interfaceMetrics.isEmpty()) {
            flush();
        }
    }

    public int pending() {
        return checkResults.size() + interfaceMetrics.size();
    }
}
