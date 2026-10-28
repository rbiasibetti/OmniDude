package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ICMP via binário {@code ping} do SO (ADR-2): o Java não envia ICMP puro sem privilégios,
 * mas o {@code ping} do sistema já tem as permissões necessárias.
 */
@Component
public class ExternalPingProbe implements Probe {

    private static final Logger log = LoggerFactory.getLogger(ExternalPingProbe.class);
    private static final Pattern RTT = Pattern.compile("(?:time|tempo|tiempo|zeit)\\s*[=<]\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLY = Pattern.compile("ttl[=\\s]", Pattern.CASE_INSENSITIVE);

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    @Override
    public Set<CheckType> types() {
        return Set.of(CheckType.ICMP);
    }

    @PostConstruct
    void selfTest() {
        ProbeResult r = execute(new Target("127.0.0.1", null, 2000));
        if (!r.success()) {
            log.warn("Auto-teste do ping falhou ({}). Instale o utilitário 'ping' ou verifique permissões: "
                    + "sem ele todas as checagens ICMP falharão.", r.detail());
        }
    }

    @Override
    public ProbeResult execute(Target target) {
        if (!AddressValidator.isValidIp(target.address())) {
            return ProbeResult.fail("endereço inválido");
        }
        long start = System.nanoTime();
        Process process = null;
        try {
            process = new ProcessBuilder(command(target.address(), target.timeoutMs()))
                    .redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(target.timeoutMs() + 1500L, TimeUnit.MILLISECONDS)) {
                return ProbeResult.fail("timeout");
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parse(process.exitValue(), out, (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            return ProbeResult.fail("ping indisponível: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("interrompido");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Separado para teste: exit code 0 e uma linha de resposta (TTL) — no Windows, "host unreachable" também sai com 0. */
    static ProbeResult parse(int exitCode, String output, long wallMs) {
        if (exitCode != 0 || !REPLY.matcher(output).find()) {
            return ProbeResult.fail("sem resposta");
        }
        Matcher m = RTT.matcher(output);
        if (m.find()) {
            double ms = Double.parseDouble(m.group(1).replace(',', '.'));
            return ProbeResult.ok((int) Math.round(ms));
        }
        return ProbeResult.ok((int) wallMs);
    }

    static List<String> command(String ip, int timeoutMs) {
        if (OS.contains("win")) {
            return List.of("ping", "-n", "1", "-w", String.valueOf(timeoutMs), ip);
        }
        if (OS.contains("mac")) {
            return List.of("ping", "-n", "-c", "1", "-W", String.valueOf(timeoutMs), ip);
        }
        long seconds = Math.max(1, (timeoutMs + 999L) / 1000);
        return List.of("ping", "-n", "-c", "1", "-W", String.valueOf(seconds), ip);
    }
}
