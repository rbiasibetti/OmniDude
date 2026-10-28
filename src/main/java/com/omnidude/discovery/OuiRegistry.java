package com.omnidude.discovery;

import com.omnidude.config.OmniDudeProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fabricante de uma placa de rede a partir do prefixo do MAC (OUI), com as tabelas do arp-scan: {@code ieee-oui.txt}
 * (registro IEEE: 24, 28 e 36 bits) e {@code mac-vendor.txt} (entradas extras). Formato das linhas:
 * {@code <prefixo hexadecimal><TAB><fabricante>}, de 1 a 12 dígitos, com ou sem separadores. Vence o prefixo mais longo;
 * em prefixos do mesmo tamanho, o arquivo carregado depois vence (IEEE, depois mac-vendor.txt embutido, depois o
 * {@code mac-vendor.txt} do diretório de dados, que permite corrigir ou acrescentar entradas sem recompilar).
 */
@Component
public class OuiRegistry {

    private static final Logger log = LoggerFactory.getLogger(OuiRegistry.class);
    private static final int MAX_DIGITS = 12;

    private final Map<String, String> byPrefix = new HashMap<>(1 << 17);
    private final Map<String, String> interned = new HashMap<>();

    @Autowired
    public OuiRegistry(OmniDudeProperties props) throws IOException {
        this(Path.of(props.dataDir()).toAbsolutePath());
    }

    /** Para testes e uso fora do Spring: {@code dataDir} pode não existir. */
    public OuiRegistry(Path dataDir) throws IOException {
        load("/oui/ieee-oui.txt");
        load("/oui/mac-vendor.txt");
        Path override = dataDir.resolve("mac-vendor.txt");
        if (Files.isRegularFile(override)) {
            try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                int n = parse(r, override.toString());
                log.info("{} prefixo(s) de fabricante lidos de {}", n, override);
            }
        }
        interned.clear();
        log.info("Tabela de fabricantes carregada: {} prefixos", byPrefix.size());
    }

    private void load(String resource) throws IOException {
        try (InputStream in = OuiRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("tabela de fabricantes ausente do classpath: " + resource);
            }
            parse(new InputStreamReader(in, StandardCharsets.UTF_8), resource);
        }
    }

    private int parse(Reader reader, String origin) throws IOException {
        int n = 0;
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String prefix = normalize(line.substring(0, tab));
            String vendor = line.substring(tab + 1).trim();
            if (prefix == null || vendor.isEmpty()) {
                log.debug("{}: linha ignorada: {}", origin, line);
                continue;
            }
            byPrefix.put(prefix, interned.computeIfAbsent(vendor, v -> v));
            n++;
        }
        return n;
    }

    /** Dígitos hexadecimais em maiúsculas, sem separadores; {@code null} se não for um prefixo válido. */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(MAX_DIGITS);
        for (char c : text.trim().toCharArray()) {
            if (c == ':' || c == '-' || c == '.') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                return null;
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.isEmpty() || sb.length() > MAX_DIGITS ? null : sb.toString();
    }

    /** Fabricante do MAC (em qualquer formato usual), pelo prefixo mais longo que casar. */
    public Optional<String> vendor(String mac) {
        String hex = mac == null ? null : normalize(mac);
        if (hex == null || hex.length() < 6) {
            return Optional.empty();
        }
        for (int len = Math.min(hex.length(), MAX_DIGITS); len >= 1; len--) {
            String vendor = byPrefix.get(hex.substring(0, len));
            if (vendor != null) {
                return Optional.of(vendor);
            }
        }
        return Optional.empty();
    }

    /**
     * Bit "administrado localmente" do primeiro byte: o MAC não foi atribuído pelo fabricante (endereço aleatório de
     * celular, interface virtual, bridge, VM...). Nesses casos o OUI não identifica o fabricante.
     */
    public static boolean locallyAdministered(String mac) {
        String hex = mac == null ? null : normalize(mac);
        return hex != null && hex.length() >= 2 && (Integer.parseInt(hex.substring(0, 2), 16) & 0x02) != 0;
    }

    public int size() {
        return byPrefix.size();
    }
}
