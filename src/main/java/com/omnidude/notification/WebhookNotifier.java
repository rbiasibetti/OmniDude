package com.omnidude.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * POST de um JSON para uma URL. Com {@code secret}, assina o corpo (HMAC-SHA256) em {@code X-OmniDude-Signature:
 * sha256=<hex>}, para o receptor conferir a origem. Não segue redirecionamentos: a URL é a que o ADMIN cadastrou.
 */
@Component
public class WebhookNotifier implements Notifier {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;

    public WebhookNotifier(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public void send(Map<String, String> config, Message m) throws NotifyException {
        String body = payload(m);
        HttpRequest.Builder req;
        try {
            req = HttpRequest.newBuilder(URI.create(config.get("url"))).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8").header("User-Agent", "OmniDude");
        } catch (IllegalArgumentException e) {
            throw new NotifyException("URL do webhook inválida", false);
        }
        String secret = config.get("secret");
        if (secret != null && !secret.isBlank()) {
            req.header("X-OmniDude-Signature", "sha256=" + hmac(secret, body));
        }
        try {
            HttpResponse<Void> r = http.send(req.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.discarding());
            int code = r.statusCode();
            if (code < 200 || code >= 300) {
                throw new NotifyException("o webhook respondeu HTTP " + code, code >= 500 || code == 429 || code == 408);
            }
        } catch (IOException e) {
            throw new NotifyException("falha de rede ao chamar o webhook: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifyException("envio interrompido", false);
        }
    }

    String payload(Message m) throws NotifyException {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("source", "omnidude");
        o.put("eventId", m.eventId());
        o.put("ts", m.ts());
        o.put("severity", m.severity().name());
        o.put("type", m.type().name());
        o.put("deviceId", m.deviceId());
        o.put("deviceName", m.deviceName());
        o.put("message", m.text());
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new NotifyException("não foi possível montar o JSON", false);
        }
    }

    static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
