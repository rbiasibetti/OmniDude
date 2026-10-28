package com.omnidude.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mensagem por um bot do Telegram ({@code sendMessage}). O token vai na URL da API, então nenhuma mensagem de erro
 * produzida aqui inclui a URL. {@code apiBase} (opcional) permite um servidor Bot API próprio.
 */
@Component
public class TelegramNotifier implements Notifier {

    static final String DEFAULT_API = "https://api.telegram.org";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;

    public TelegramNotifier(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ChannelType type() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public void send(Map<String, String> config, Message m) throws NotifyException {
        String base = config.getOrDefault("apiBase", "");
        base = (base.isBlank() ? DEFAULT_API : base).replaceAll("/+$", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", config.get("chatId"));
        body.put("text", icon(m) + " " + m.headline());
        body.put("disable_web_page_preview", true);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/bot" + config.get("botToken") + "/sendMessage"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code < 200 || code >= 300) {
                throw new NotifyException("o Telegram respondeu HTTP " + code + description(r.body()),
                        code >= 500 || code == 429);
            }
        } catch (IllegalArgumentException e) {
            throw new NotifyException("endereço da API do Telegram inválido", false);
        } catch (IOException e) {
            throw new NotifyException("falha de rede ao chamar o Telegram: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifyException("envio interrompido", false);
        }
    }

    private String description(String body) {
        try {
            String d = json.readTree(body).path("description").asText("");
            return d.isBlank() ? "" : " (" + (d.length() > 120 ? d.substring(0, 120) : d) + ")";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String icon(Message m) {
        return switch (m.severity()) {
            case CRITICAL -> "🔴";
            case WARNING -> "🟠";
            case INFO -> "🟢";
        };
    }
}
