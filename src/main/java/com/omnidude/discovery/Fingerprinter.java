package com.omnidude.discovery;

import com.omnidude.config.OmniDudeProperties;
import com.omnidude.device.DeviceType;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Deduz tipo e sistema operacional por sysObjectID, sysDescr, portas abertas e fabricante do MAC, com regras em YAML
 * (ver fingerprints.yml).
 */
@Component
public class Fingerprinter {

    private static final Logger log = LoggerFactory.getLogger(Fingerprinter.class);

    public record Fingerprint(DeviceType type, String os, String rule) {
    }

    record Rule(String name, String sysObjectId, Pattern sysDescr, Set<Integer> ports, Pattern vendor, Pattern banner,
                DeviceType type, String os) {

        boolean matches(String objectId, String descr, Collection<Integer> openPorts, String macVendor, String hints) {
            if (banner != null && (hints == null || !banner.matcher(hints).find())) {
                return false;
            }
            if (vendor != null && (macVendor == null || !vendor.matcher(macVendor).find())) {
                return false;
            }
            if (sysObjectId != null) {
                if (objectId == null) {
                    return false;
                }
                String o = objectId.startsWith(".") ? objectId.substring(1) : objectId;
                if (!(o.equals(sysObjectId) || o.startsWith(sysObjectId + "."))) {
                    return false;
                }
            }
            if (sysDescr != null && (descr == null || !sysDescr.matcher(descr).find())) {
                return false;
            }
            if (ports != null) {
                return openPorts != null && openPorts.stream().anyMatch(ports::contains);
            }
            return true;
        }
    }

    private final List<Rule> rules;

    @Autowired   // há um segundo construtor (para testes): sem isto o Spring não sabe qual usar
    public Fingerprinter(OmniDudeProperties props) throws IOException {
        List<Rule> all = new ArrayList<>();
        Path override = Path.of(props.dataDir()).toAbsolutePath().resolve("fingerprints.yml");
        if (Files.isRegularFile(override)) {
            try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                all.addAll(parse(r, override.toString()));
            }
        }
        try (InputStream in = Fingerprinter.class.getResourceAsStream("/fingerprints.yml")) {
            if (in == null) {
                throw new IllegalStateException("fingerprints.yml padrão ausente do classpath");
            }
            all.addAll(parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), "fingerprints.yml (padrão)"));
        }
        this.rules = List.copyOf(all);
    }

    /** Para testes: regras direto de textos YAML, na ordem dada. */
    public static Fingerprinter fromYaml(String... yamlTexts) {
        List<Rule> all = new ArrayList<>();
        for (String text : yamlTexts) {
            all.addAll(parse(new java.io.StringReader(text), "teste"));
        }
        return new Fingerprinter(List.copyOf(all));
    }

    private Fingerprinter(List<Rule> rules) {
        this.rules = rules;
    }

    public Optional<Fingerprint> match(String sysObjectId, String sysDescr, Collection<Integer> openPorts) {
        return match(sysObjectId, sysDescr, openPorts, null);
    }

    /** @param macVendor fabricante do MAC (veja {@link OuiRegistry}), ou {@code null} se o MAC não é conhecido */
    public Optional<Fingerprint> match(String sysObjectId, String sysDescr, Collection<Integer> openPorts,
                                       String macVendor) {
        return match(sysObjectId, sysDescr, openPorts, macVendor, null);
    }

    /** @param hints o que o host anunciou (NetBIOS, UPnP, mDNS), ou {@code null} */
    public Optional<Fingerprint> match(String sysObjectId, String sysDescr, Collection<Integer> openPorts,
                                       String macVendor, String hints) {
        for (Rule r : rules) {
            if (r.matches(sysObjectId, sysDescr, openPorts, macVendor, hints)) {
                return Optional.of(new Fingerprint(r.type(), r.os(), r.name()));
            }
        }
        return Optional.empty();
    }

    public int size() {
        return rules.size();
    }

    @SuppressWarnings("unchecked")
    private static List<Rule> parse(Reader reader, String origin) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        List<Rule> out = new ArrayList<>();
        if (!(doc instanceof Map<?, ?> root) || !(root.get("rules") instanceof List<?> list)) {
            log.warn("{}: sem a lista 'rules'; ignorado", origin);
            return out;
        }
        int n = 0;
        for (Object item : list) {
            n++;
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            try {
                Object ports = m.get("ports");
                Set<Integer> portSet = null;
                if (ports instanceof List<?> pl) {
                    portSet = new HashSet<>();
                    for (Object p : pl) {
                        portSet.add(((Number) p).intValue());
                    }
                }
                String objectId = m.get("sysObjectId") == null ? null : String.valueOf(m.get("sysObjectId")).trim();
                Pattern descr = m.get("sysDescr") == null ? null
                        : Pattern.compile(String.valueOf(m.get("sysDescr")), Pattern.CASE_INSENSITIVE);
                Pattern vendor = m.get("vendor") == null ? null
                        : Pattern.compile(String.valueOf(m.get("vendor")), Pattern.CASE_INSENSITIVE);
                Pattern banner = m.get("banner") == null ? null
                        : Pattern.compile(String.valueOf(m.get("banner")), Pattern.CASE_INSENSITIVE);
                if (objectId == null && descr == null && portSet == null && vendor == null && banner == null) {
                    log.warn("{}: regra #{} sem condição; ignorada", origin, n);
                    continue;
                }
                out.add(new Rule(String.valueOf(m.get("name") == null ? "regra " + n : m.get("name")), objectId, descr,
                        portSet, vendor, banner, DeviceType.valueOf(String.valueOf(m.get("type")).toUpperCase()),
                        m.get("os") == null ? null : String.valueOf(m.get("os"))));
            } catch (IllegalArgumentException | ClassCastException e) {
                log.warn("{}: regra #{} inválida ({}); ignorada", origin, n, e.getMessage());
            }
        }
        return out;
    }
}
