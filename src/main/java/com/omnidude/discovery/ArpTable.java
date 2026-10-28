package com.omnidude.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tabela ARP do servidor onde o OmniDude roda: só conhece hosts da mesma sub-rede L2 que já trocaram tráfego com ele
 * (por isso a descoberta lê depois da varredura). Linux: /proc/net/arp; macOS/Windows: saída de {@code arp -a}.
 */
@Component
public class ArpTable {

    private static final Pattern IP = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    private static final Pattern MAC = Pattern.compile("\\b([0-9a-fA-F]{1,2}(?:[:-][0-9a-fA-F]{1,2}){5})\\b");

    /** IP → MAC. Falhas de leitura resultam em mapa vazio: o MAC é um enriquecimento, não um requisito. */
    public Map<String, String> snapshot() {
        try {
            Path proc = Path.of("/proc/net/arp");
            if (Files.isReadable(proc)) {
                return parse(Files.readString(proc, StandardCharsets.UTF_8));
            }
            Process p = new ProcessBuilder("arp", "-a").redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Map.of();
            }
            return parse(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    static Map<String, String> parse(String text) {
        Map<String, String> out = new HashMap<>();
        for (String line : text.split("\\R")) {
            Matcher ip = IP.matcher(line);
            Matcher mac = MAC.matcher(line);
            if (ip.find() && mac.find()) {
                MacAddress.parse(mac.group(1)).ifPresent(m -> out.put(ip.group(1), m));
            }
        }
        return out;
    }
}
