package com.omnidude.monitoring;

import com.omnidude.monitoring.probe.DnsProbe;
import com.omnidude.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Regras de porta e parâmetros por tipo. Tudo que vira linha de comando, URL ou consulta DNS é validado aqui, antes
 * de chegar ao banco ou às sondas.
 */
public final class CheckValidator {

    public record Normalized(Integer port, Map<String, String> params) {
    }

    private static final Pattern HTTP_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/?-]{0,199}$");
    private static final Pattern STATUS_SPEC = Pattern.compile("^[1-5]\\d\\d(-[1-5]\\d\\d)?(,[1-5]\\d\\d(-[1-5]\\d\\d)?)*$");
    private static final Set<String> DNS_TYPES = Set.of("A", "AAAA", "MX", "NS", "TXT", "CNAME");

    private CheckValidator() {
    }

    /** @param profilePort porta SNMP do perfil do dispositivo, usada como padrão das checagens SNMP. */
    public static Normalized validate(CheckType type, Integer port, Map<String, String> rawParams, Integer profilePort) {
        Map<String, String> params = clean(rawParams);
        Integer effectivePort = switch (type) {
            case ICMP -> {
                if (port != null) {
                    throw bad("ICMP não usa porta");
                }
                yield null;
            }
            case TCP -> {
                if (port == null) {
                    throw bad("Informe a porta TCP");
                }
                yield port;
            }
            case HTTP -> port == null ? 80 : port;
            case HTTPS -> port == null ? 443 : port;
            case DNS -> port == null ? 53 : port;
            case SNMP -> port == null ? (profilePort == null ? 161 : profilePort) : port;
        };
        switch (type) {
            case HTTP, HTTPS -> validateHttp(type, params);
            case DNS -> validateDns(params);
            default -> {
                if (!params.isEmpty()) {
                    throw bad("Parâmetros não se aplicam a checagens " + type);
                }
            }
        }
        return new Normalized(effectivePort, params);
    }

    private static void validateHttp(CheckType type, Map<String, String> p) {
        reject(p, Set.of("path", "method", "expectedStatus", "contains", "verifyTls"));
        if (p.containsKey("path") && !HTTP_PATH.matcher(p.get("path")).matches()) {
            throw bad("Caminho HTTP inválido (deve começar com / e ter até 200 caracteres)");
        }
        if (p.containsKey("method") && !Set.of("GET", "HEAD").contains(p.get("method"))) {
            throw bad("Método deve ser GET ou HEAD");
        }
        if (p.containsKey("expectedStatus") && !STATUS_SPEC.matcher(p.get("expectedStatus")).matches()) {
            throw bad("Status esperado inválido (ex.: 200, 200-399, 200,204)");
        }
        if (p.containsKey("contains")) {
            if (p.get("contains").length() > 100) {
                throw bad("Texto esperado muito longo (máximo 100)");
            }
            if ("HEAD".equals(p.get("method"))) {
                throw bad("Texto esperado não funciona com HEAD");
            }
        }
        if (p.containsKey("verifyTls")) {
            if (!Set.of("true", "false").contains(p.get("verifyTls"))) {
                throw bad("verifyTls deve ser true ou false");
            }
            if (type == CheckType.HTTP) {
                p.remove("verifyTls");
            }
        }
    }

    private static void validateDns(Map<String, String> p) {
        reject(p, Set.of("name", "recordType"));
        if (p.containsKey("name") && !DnsProbe.isValidName(p.get("name"))) {
            throw bad("Nome DNS inválido");
        }
        if (p.containsKey("recordType")) {
            String t = p.get("recordType").toUpperCase();
            if (!DNS_TYPES.contains(t)) {
                throw bad("Tipo de registro deve ser um de " + String.join(", ", new java.util.TreeSet<>(DNS_TYPES)));
            }
            p.put("recordType", t);
        }
    }

    private static void reject(Map<String, String> p, Set<String> allowed) {
        for (String k : p.keySet()) {
            if (!allowed.contains(k)) {
                throw bad("Parâmetro desconhecido: " + k);
            }
        }
    }

    private static Map<String, String> clean(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    out.put(k.trim(), v.trim());
                }
            });
        }
        return out;
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }
}
