package com.omnidude.discovery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Identidade de um host por protocolos de anúncio, consultados em unicast no host já encontrado (sem multicast, que
 * dependeria da rede e de privilégios): NetBIOS (NBSTAT, UDP 137: nome da máquina e grupo, típico de Windows e Samba),
 * SSDP/UPnP (UDP 1900 + descrição XML: fabricante, modelo e tipo do dispositivo; roteadores respondem como
 * InternetGatewayDevice) e mDNS (PTR reverso em UDP 5353: nome .local de Apple, Linux, impressoras). Cada protocolo
 * que o host não fala simplesmente não contribui.
 */
@Component
public class PassiveIdentity {

    /** O que se descobriu. {@code hints} é um texto curto para a tela e para as regras do fingerprint. */
    public record Identity(String hostname, String hints) {
        public static final Identity NONE = new Identity(null, null);

        public boolean isEmpty() {
            return hostname == null && hints == null;
        }
    }

    record NetbiosName(String computer, String workgroup) {
    }

    record Upnp(String server, String deviceType, String friendlyName, String manufacturer, String model) {
    }

    private static final int MAX_SSDP_RESPONSES = 8;
    private static final int MAX_XML_BYTES = 64 * 1024;
    private static final Pattern TAG = Pattern.compile("<(friendlyName|manufacturer|modelName|modelNumber|deviceType)>\\s*([^<]{1,120}?)\\s*</\\1>");

    private final SecureRandom random = new SecureRandom();
    private final int netbiosPort;
    private final int ssdpPort;
    private final int mdnsPort;

    public PassiveIdentity() {
        this(137, 1900, 5353);
    }

    /** Portas configuráveis para testes com respondedores locais. */
    public PassiveIdentity(int netbiosPort, int ssdpPort, int mdnsPort) {
        this.netbiosPort = netbiosPort;
        this.ssdpPort = ssdpPort;
        this.mdnsPort = mdnsPort;
    }

    /** Consulta os três protocolos em sequência (cada um com o timeout dado) e junta o que respondeu. */
    public Identity identify(String ip, int timeoutMs) {
        String hostname = null;
        List<String> hints = new ArrayList<>();
        Optional<NetbiosName> nb = netbios(ip, timeoutMs);
        if (nb.isPresent()) {
            hostname = nb.get().computer();
            hints.add("NetBIOS: " + nb.get().computer() + (nb.get().workgroup() != null ? " (" + nb.get().workgroup() + ")" : ""));
        }
        Optional<Upnp> up = ssdp(ip, timeoutMs);
        if (up.isPresent()) {
            Upnp u = up.get();
            StringBuilder sb = new StringBuilder("UPnP: ");
            sb.append(join(" ", u.manufacturer(), u.model()));
            if (sb.length() == 6) {
                sb.append(u.friendlyName() != null ? u.friendlyName() : u.server() != null ? u.server() : "dispositivo");
            }
            if (u.deviceType() != null) {
                sb.append(" [").append(u.deviceType()).append(']');
            }
            hints.add(sb.toString());
            if (hostname == null && u.friendlyName() != null) {
                hostname = u.friendlyName();
            }
        }
        Optional<String> md = mdns(ip, timeoutMs);
        if (md.isPresent()) {
            hints.add("mDNS: " + md.get());
            if (hostname == null) {
                hostname = md.get();
            }
        }
        return hints.isEmpty() ? Identity.NONE : new Identity(clean(hostname), String.join(" · ", hints));
    }

    private static String clean(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll("[\\p{Cntrl}]", " ").trim();
        return n.isEmpty() ? null : n.length() > 80 ? n.substring(0, 80) : n;
    }

    private static String join(String sep, String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        return String.join(sep, out);
    }

    // ------------------------------------------------------------------------------------------------ NetBIOS

    Optional<NetbiosName> netbios(String ip, int timeoutMs) {
        byte[] q = new byte[50];
        int id = random.nextInt(0x10000);
        q[0] = (byte) (id >> 8);
        q[1] = (byte) id;
        q[5] = 1;                                                   // 1 pergunta
        q[12] = 0x20;                                               // nome codificado: "*" + 15 nulos
        q[13] = 'C';
        q[14] = 'K';
        for (int i = 15; i < 45; i++) {
            q[i] = 'A';
        }
        q[47] = 0x21;                                               // tipo NBSTAT (a posição 45 é o fim do nome)                                               // NBSTAT
        q[49] = 0x01;                                               // IN
        byte[] r = exchange(ip, netbiosPort, q, timeoutMs, id >> 8, id & 0xFF);
        return r == null ? Optional.empty() : parseNbstat(r);
    }

    static Optional<NetbiosName> parseNbstat(byte[] r) {
        try {
            int pos = 12;
            int qd = u16(r, 4);
            for (int i = 0; i < qd; i++) {
                pos = skipName(r, pos) + 4;
            }
            pos = skipName(r, pos) + 8;                             // nome, tipo, classe, ttl
            int rdlen = u16(r, pos);
            pos += 2;
            int count = r[pos] & 0xFF;
            pos++;
            if (count == 0 || pos + count * 18 > r.length || rdlen < 1) {
                return Optional.empty();
            }
            String computer = null;
            String group = null;
            for (int i = 0; i < count; i++) {
                int at = pos + i * 18;
                String name = new String(r, at, 15, StandardCharsets.ISO_8859_1).trim();
                int suffix = r[at + 15] & 0xFF;
                boolean isGroup = (r[at + 16] & 0x80) != 0;
                if (suffix == 0x00 && !name.isEmpty()) {
                    if (isGroup && group == null) {
                        group = name;
                    } else if (!isGroup && computer == null) {
                        computer = name;
                    }
                }
            }
            return computer == null ? Optional.empty() : Optional.of(new NetbiosName(computer, group));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------------ SSDP

    Optional<Upnp> ssdp(String ip, int timeoutMs) {
        byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n"
                + "ST: ssdp:all\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        List<String> responses = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(Math.max(100, timeoutMs / 2));
            s.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(ip), ssdpPort));
            long end = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] buf = new byte[2048];
            while (responses.size() < MAX_SSDP_RESPONSES && System.nanoTime() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (ip.equals(p.getAddress().getHostAddress())) {
                    responses.add(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return parseSsdp(ip, responses, timeoutMs);
    }

    Optional<Upnp> parseSsdp(String ip, List<String> responses, int timeoutMs) {
        String server = null;
        String type = null;
        String location = null;
        Set<String> types = new LinkedHashSet<>();
        for (String resp : responses) {
            for (String line : resp.split("\r?\n")) {
                int c = line.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = line.substring(0, c).trim().toUpperCase();
                String v = line.substring(c + 1).trim();
                switch (k) {
                    case "SERVER" -> server = server == null ? v : server;
                    case "LOCATION" -> location = location == null ? v : location;
                    case "ST", "NT" -> {
                        Matcher m = Pattern.compile("device:([A-Za-z0-9_-]+)").matcher(v);
                        if (m.find()) {
                            types.add(m.group(1));
                        }
                    }
                    default -> { }
                }
            }
        }
        if (responses.isEmpty()) {
            return Optional.empty();
        }
        // o mais específico para quem decide tipo: roteador e impressora ganham do genérico
        for (String preferred : new String[] {"InternetGatewayDevice", "Printer", "WFADevice"}) {
            if (types.contains(preferred)) {
                type = preferred;
            }
        }
        if (type == null && !types.isEmpty()) {
            type = types.iterator().next();
        }
        String friendly = null;
        String maker = null;
        String model = null;
        String xml = location == null ? null : fetchDescription(ip, location, timeoutMs);
        if (xml != null) {
            Matcher m = TAG.matcher(xml);
            while (m.find()) {
                String v = m.group(2);
                switch (m.group(1)) {
                    case "friendlyName" -> friendly = friendly == null ? v : friendly;
                    case "manufacturer" -> maker = maker == null ? v : maker;
                    case "modelName" -> model = model == null ? v : model;
                    case "deviceType" -> {
                        Matcher t = Pattern.compile("device:([A-Za-z0-9_-]+)").matcher(v);
                        if (type == null && t.find()) {
                            type = t.group(1);
                        }
                    }
                    default -> { }
                }
            }
        }
        return Optional.of(new Upnp(server, type, clean(friendly), clean(maker), clean(model)));
    }

    /** Baixa a descrição do dispositivo, só do próprio host (a URL vem da rede e nunca pode apontar para outro destino). */
    private String fetchDescription(String ip, String location, int timeoutMs) {
        try {
            URI uri = URI.create(location);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !ip.equals(InetAddress.getByName(uri.getHost()).getHostAddress())) {
                return null;
            }
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < MAX_XML_BYTES) {
                    out.write(buf, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ mDNS

    Optional<String> mdns(String ip, int timeoutMs) {
        String[] o = ip.split("\\.");
        if (o.length != 4) {
            return Optional.empty();
        }
        String name = o[3] + "." + o[2] + "." + o[1] + "." + o[0] + ".in-addr.arpa";
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        int id = 1 + random.nextInt(0xFFFF);                          // id ≠ 0 e porta de origem ≠ 5353: resposta unicast
        q.write(id >> 8);
        q.write(id);
        q.writeBytes(new byte[] {0, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String label : name.split("\\.")) {
            q.write(label.length());
            q.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        q.write(0);
        q.writeBytes(new byte[] {0, 12, 0, 1});                       // PTR, IN
        byte[] r = exchange(ip, mdnsPort, q.toByteArray(), timeoutMs, id >> 8, id & 0xFF);
        return r == null ? Optional.empty() : parsePtr(r);
    }

    static Optional<String> parsePtr(byte[] r) {
        try {
            int an = u16(r, 6);
            int pos = 12;
            for (int i = 0; i < u16(r, 4); i++) {
                pos = skipName(r, pos) + 4;
            }
            for (int i = 0; i < an; i++) {
                pos = skipName(r, pos);
                int type = u16(r, pos);
                int rdlen = u16(r, pos + 8);
                pos += 10;
                if (type == 12) {
                    String host = readName(r, pos);
                    if (host.endsWith(".local")) {
                        host = host.substring(0, host.length() - 6);
                    }
                    return host.isBlank() ? Optional.empty() : Optional.of(host);
                }
                pos += rdlen;
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ comum

    /** Um pacote UDP e a primeira resposta cujo id (2 primeiros bytes) é o enviado; {@code null} se não veio. */
    private static byte[] exchange(String ip, int port, byte[] query, int timeoutMs, int id0, int id1) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            s.connect(InetAddress.getByName(ip), port);               // porta fechada: o ICMP de volta encerra na hora
            s.send(new DatagramPacket(query, query.length));
            byte[] buf = new byte[1500];
            long end = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                if (p.getLength() > 12 && ip.equals(p.getAddress().getHostAddress())
                        && (p.getData()[0] & 0xFF) == id0 && (p.getData()[1] & 0xFF) == id1) {
                    return java.util.Arrays.copyOf(p.getData(), p.getLength());
                }
            }
        } catch (IOException e) {
            // sem resposta: o host não fala esse protocolo
        }
        return null;
    }

    private static int u16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    /** Posição logo depois de um nome DNS (sequência de rótulos ou ponteiro de compressão). */
    private static int skipName(byte[] b, int pos) {
        for (int guard = 0; guard < 128; guard++) {
            int len = b[pos] & 0xFF;
            if (len == 0) {
                return pos + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return pos + 2;
            }
            pos += 1 + len;
        }
        throw new IllegalArgumentException("nome DNS malformado");
    }

    private static String readName(byte[] b, int pos) {
        StringBuilder sb = new StringBuilder();
        for (int guard = 0; guard < 128; guard++) {
            int len = b[pos] & 0xFF;
            if (len == 0) {
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                pos = ((len & 0x3F) << 8) | (b[pos + 1] & 0xFF);
                continue;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(b, pos + 1, len, StandardCharsets.UTF_8));
            pos += 1 + len;
        }
        return sb.toString();
    }
}
