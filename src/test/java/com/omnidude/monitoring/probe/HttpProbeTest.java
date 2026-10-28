package com.omnidude.monitoring.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.monitoring.CheckType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpProbeTest {

    private final HttpProbe probe = new HttpProbe();
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reply("/ok", 200, "hello omnidude");
        reply("/missing", 404, "nope");
        reply("/boom", 500, "erro");
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(String path, int status, String body) {
        server.createContext(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, "HEAD".equals(ex.getRequestMethod()) ? -1 : bytes.length);
            if (!"HEAD".equals(ex.getRequestMethod())) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
    }

    private ProbeResult run(CheckType type, Integer p, int timeoutMs, String... kv) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return probe.execute(new Target(type, "127.0.0.1", p, timeoutMs, params, null));
    }

    @Test
    void okByDefault() {
        ProbeResult r = run(CheckType.HTTP, port, 2000, "path", "/ok");
        assertThat(r.success()).isTrue();
        assertThat(r.responseMs()).isBetween(0, 2000);
    }

    @Test
    void errorStatusFailsUnlessExpected() {
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/boom").success()).isFalse();
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/boom").detail()).contains("500");
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/boom", "expectedStatus", "500").success()).isTrue();
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/missing", "expectedStatus", "200,404").success()).isTrue();
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/missing", "expectedStatus", "200-299").success()).isFalse();
    }

    @Test
    void redirectIsNotFollowedAndCountsAsItsOwnStatus() {
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/redirect").success()).isTrue();             // 302 em 200-399
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/redirect", "expectedStatus", "200").success()).isFalse();
    }

    @Test
    void bodyMustContainExpectedText() {
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/ok", "contains", "omnidude").success()).isTrue();
        ProbeResult r = run(CheckType.HTTP, port, 2000, "path", "/ok", "contains", "ausente");
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("conteúdo");
    }

    @Test
    void headSkipsBodyCheck() {
        assertThat(run(CheckType.HTTP, port, 2000, "path", "/ok", "method", "HEAD", "contains", "x").success()).isTrue();
    }

    @Test
    void slowResponseTimesOut() {
        ProbeResult r = run(CheckType.HTTP, port, 500, "path", "/slow");
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("timeout");
    }

    @Test
    void connectionRefusedFails() throws IOException {
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertThat(run(CheckType.HTTP, closed, 1000).success()).isFalse();
    }

    @Test
    void httpsAgainstPlainHttpServerFailsInBothTlsModes() {
        assertThat(run(CheckType.HTTPS, port, 2000, "path", "/ok").success()).isFalse();
        assertThat(run(CheckType.HTTPS, port, 2000, "path", "/ok", "verifyTls", "false").success()).isFalse();
    }

    @Test
    void statusSpecParsing() {
        assertThat(HttpProbe.statusAccepted("200-399", 302)).isTrue();
        assertThat(HttpProbe.statusAccepted("200-399", 400)).isFalse();
        assertThat(HttpProbe.statusAccepted("200, 204,301-302", 204)).isTrue();
        assertThat(HttpProbe.statusAccepted("abc", 200)).isFalse();
    }
}
