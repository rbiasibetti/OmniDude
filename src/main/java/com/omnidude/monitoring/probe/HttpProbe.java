package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.stereotype.Component;

/**
 * HTTP/HTTPS. Só o IP do dispositivo é alvo (o caminho vem validado de {@code CheckValidator}), então a sonda não
 * pode ser usada para alcançar outros hosts. Redirecionamentos NÃO são seguidos: o status avaliado é o do serviço.
 * Parâmetros: path, method (GET|HEAD), expectedStatus ("200-399", "200,204"...), contains, verifyTls.
 */
@Component
public class HttpProbe implements Probe {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final HttpClient verifying = HttpClient.newBuilder().followRedirects(Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final HttpClient insecure = buildInsecureClient();

    @Override
    public Set<CheckType> types() {
        return Set.of(CheckType.HTTP, CheckType.HTTPS);
    }

    @Override
    public ProbeResult execute(Target target) {
        if (!AddressValidator.isValidIp(target.address()) || target.port() == null) {
            return ProbeResult.fail("alvo inválido");
        }
        boolean https = target.type() == CheckType.HTTPS;
        String host = target.address().indexOf(':') >= 0 ? "[" + target.address() + "]" : target.address();
        String method = target.param("method", "GET");
        String contains = target.param("contains", null);
        boolean verify = !"false".equalsIgnoreCase(target.param("verifyTls", "true"));
        long start = System.nanoTime();
        try {
            URI uri = new URI(https ? "https" : "http", null, host.replace("[", "").replace("]", ""),
                    target.port(), target.param("path", "/"), null, null);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(target.timeoutMs()))
                    .method("HEAD".equals(method) ? "HEAD" : "GET", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", "OmniDude-NMS").build();
            HttpClient client = https && !verify ? insecure : verifying;
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int ms = TcpProbe.elapsedMs(start);
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (!statusAccepted(target.param("expectedStatus", "200-399"), status)) {
                    return ProbeResult.fail("status HTTP " + status);
                }
                if (contains != null && !"HEAD".equals(method)) {
                    String text = new String(body.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
                    if (!text.contains(contains)) {
                        return ProbeResult.fail("conteúdo esperado não encontrado");
                    }
                }
            }
            return ProbeResult.ok(ms);
        } catch (HttpTimeoutException e) {
            return ProbeResult.fail("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.fail("interrompido");
        } catch (IOException | java.net.URISyntaxException e) {
            return ProbeResult.fail(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** "200-399,404": lista de códigos e faixas. */
    static boolean statusAccepted(String spec, int status) {
        for (String part : spec.split(",")) {
            String p = part.trim();
            int dash = p.indexOf('-');
            try {
                if (dash < 0 ? status == Integer.parseInt(p)
                        : status >= Integer.parseInt(p.substring(0, dash)) && status <= Integer.parseInt(p.substring(dash + 1))) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** Cliente que aceita qualquer certificado: só usado quando o administrador desliga {@code verifyTls}. */
    private static HttpClient buildInsecureClient() {
        try {
            // X509ExtendedTrustManager (e não X509TrustManager): só assim o JSSE dispensa também a verificação
            // de nome de host, que falharia sempre, já que o alvo é um IP.
            TrustManager trustAll = new X509ExtendedTrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }
                @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trustAll}, null);
            return HttpClient.newBuilder().followRedirects(Redirect.NEVER).sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
