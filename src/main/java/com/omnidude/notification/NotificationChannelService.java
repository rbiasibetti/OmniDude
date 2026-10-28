package com.omnidude.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.security.SecretCipher;
import com.omnidude.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canais de notificação. A configuração (URL, token, senha SMTP...) é um JSON criptografado em repouso; a API nunca a
 * devolve: só os campos não secretos e, para cada segredo, se ele existe. Ao editar, segredo em branco mantém o atual.
 */
@Service
public class NotificationChannelService {

    public record ChannelRequest(@NotBlank @Size(max = 80) String name, @NotNull ChannelType type, Boolean enabled,
                                 Map<String, String> config) {
    }

    /** {@code config}: só campos não secretos; {@code secrets}: nome do segredo → está definido? */
    public record ChannelView(Long id, String name, ChannelType type, boolean enabled, Map<String, String> config,
                              Map<String, Boolean> secrets) {
    }

    private static final Map<ChannelType, Set<String>> SECRETS = Map.of(
            ChannelType.WEBHOOK, Set.of("secret"),
            ChannelType.TELEGRAM, Set.of("botToken"),
            ChannelType.EMAIL, Set.of("password"));
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@<>,;]+@[^\\s@<>,;]+\\.[^\\s@<>,;]+$");
    private static final Pattern BOT_TOKEN = Pattern.compile("^\\d{3,}:[A-Za-z0-9_-]{20,}$");
    private static final Pattern CHAT_ID = Pattern.compile("^(-?\\d{3,}|@[A-Za-z0-9_]{5,})$");
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() { };

    private final NotificationChannelRepository channels;
    private final SecretCipher cipher;
    private final ObjectMapper json;

    public NotificationChannelService(NotificationChannelRepository channels, SecretCipher cipher, ObjectMapper json) {
        this.channels = channels;
        this.cipher = cipher;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<ChannelView> list() {
        return channels.findAllByOrderByNameAsc().stream().map(this::view).toList();
    }

    @Transactional
    public ChannelView create(ChannelRequest req) {
        String name = req.name().trim();
        if (channels.existsByName(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um canal com esse nome");
        }
        NotificationChannel c = new NotificationChannel();
        c.setName(name);
        c.setType(req.type());
        c.setEnabled(req.enabled() == null || req.enabled());
        c.setConfigEnc(encrypt(validate(req.type(), req.config(), Map.of())));
        return view(channels.save(c));
    }

    @Transactional
    public ChannelView update(long id, ChannelRequest req) {
        NotificationChannel c = find(id);
        String name = req.name().trim();
        if (channels.existsByNameAndIdNot(name, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um canal com esse nome");
        }
        if (req.type() != c.getType()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O tipo de um canal não muda: crie outro");
        }
        c.setName(name);
        if (req.enabled() != null) {
            c.setEnabled(req.enabled());
        }
        c.setConfigEnc(encrypt(validate(c.getType(), req.config(), config(c))));
        return view(c);
    }

    @Transactional
    public void delete(long id) {
        find(id);
        channels.deleteById(id);                                   // as regras do canal saem por ON DELETE CASCADE
    }

    public NotificationChannel find(long id) {
        return channels.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Canal não encontrado"));
    }

    /** Configuração descriptografada, só para o envio. */
    public Map<String, String> config(NotificationChannel c) {
        try {
            return json.readValue(cipher.decrypt(c.getConfigEnc()), MAP);
        } catch (IOException e) {
            throw new IllegalStateException("configuração do canal ilegível: " + c.getId(), e);
        }
    }

    private String encrypt(Map<String, String> config) {
        try {
            return cipher.encrypt(json.writeValueAsString(config));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private ChannelView view(NotificationChannel c) {
        Map<String, String> cfg = config(c);
        Map<String, String> open = new LinkedHashMap<>();
        Map<String, Boolean> secrets = new LinkedHashMap<>();
        Set<String> secretKeys = SECRETS.get(c.getType());
        cfg.forEach((k, v) -> {
            if (!secretKeys.contains(k)) {
                open.put(k, v);
            }
        });
        secretKeys.forEach(k -> secrets.put(k, cfg.get(k) != null && !cfg.get(k).isBlank()));
        return new ChannelView(c.getId(), c.getName(), c.getType(), c.isEnabled(), open, secrets);
    }

    // ------------------------------------------------------------------------------------------------

    /** Valida e normaliza. {@code existing}: a configuração atual (vazia na criação), de onde vêm os segredos mantidos. */
    Map<String, String> validate(ChannelType type, Map<String, String> in, Map<String, String> existing) {
        Map<String, String> req = in == null ? Map.of() : in;
        Map<String, String> out = new LinkedHashMap<>();
        switch (type) {
            case WEBHOOK -> {
                out.put("url", url(req.get("url"), "URL do webhook"));
                keepSecret(out, "secret", req, existing, false);
            }
            case TELEGRAM -> {
                keepSecret(out, "botToken", req, existing, true);
                if (!BOT_TOKEN.matcher(out.get("botToken")).matches()) {
                    throw bad("Token do bot inválido (esperado 123456:ABC...)");
                }
                String chat = trim(req.get("chatId"));
                if (!CHAT_ID.matcher(chat).matches()) {
                    throw bad("Chat inválido: use o id numérico (ex.: -1001234567890) ou @canal");
                }
                out.put("chatId", chat);
                String base = trim(req.get("apiBase"));
                if (!base.isEmpty()) {
                    out.put("apiBase", url(base, "Endereço da API"));
                }
            }
            case EMAIL -> {
                String host = trim(req.get("host"));
                if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")) {
                    throw bad("Servidor SMTP inválido");
                }
                String security = trim(req.get("security")).isEmpty() ? "STARTTLS" : trim(req.get("security")).toUpperCase();
                if (!Set.of("NONE", "STARTTLS", "SSL").contains(security)) {
                    throw bad("Segurança deve ser NONE, STARTTLS ou SSL");
                }
                String port = trim(req.get("port")).isEmpty()
                        ? (security.equals("SSL") ? "465" : security.equals("NONE") ? "25" : "587") : trim(req.get("port"));
                if (!port.matches("\\d{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                    throw bad("Porta SMTP inválida");
                }
                String from = trim(req.get("from"));
                if (!EMAIL.matcher(from).matches()) {
                    throw bad("Remetente inválido");
                }
                List<String> to = java.util.Arrays.stream(trim(req.get("to")).split("[,;\\s]+")).filter(s -> !s.isEmpty()).toList();
                if (to.isEmpty() || to.size() > 10 || !to.stream().allMatch(a -> EMAIL.matcher(a).matches())) {
                    throw bad("Informe de 1 a 10 destinatários válidos, separados por vírgula");
                }
                out.put("host", host);
                out.put("port", port);
                out.put("security", security);
                out.put("from", from);
                out.put("to", String.join(",", to));
                String user = trim(req.get("username"));
                if (!user.isEmpty()) {
                    out.put("username", user);
                    keepSecret(out, "password", req, existing, false);
                }
            }
            default -> throw bad("Tipo de canal desconhecido");
        }
        return out;
    }

    private static void keepSecret(Map<String, String> out, String key, Map<String, String> req, Map<String, String> existing,
                                   boolean required) {
        String given = req.get(key);
        if (given != null && !given.isBlank()) {
            out.put(key, given.trim());
        } else if (existing.get(key) != null && !existing.get(key).isBlank()) {
            out.put(key, existing.get(key));                         // em branco mantém o atual
        } else if (required) {
            throw bad("Informe " + (key.equals("botToken") ? "o token do bot" : key));
        }
    }

    private static String url(String value, String what) {
        String v = trim(value);
        try {
            URI u = URI.create(v);
            if (u.getHost() == null || !("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme()))) {
                throw new IllegalArgumentException();
            }
            if (v.length() > 500) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw bad(what + " inválida: use http:// ou https://");
        }
        return v;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
