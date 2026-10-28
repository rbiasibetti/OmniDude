package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pergunta ao dispositivo (um servidor DNS) por um nome e exige resposta NOERROR com ao menos um registro.
 * Cliente mínimo em UDP, com repetição em TCP quando a resposta vem truncada. Parâmetros: name, recordType.
 */
@Component
public class DnsProbe implements Probe {

    static final Map<String, Integer> RECORD_TYPES =
            Map.of("A", 1, "NS", 2, "CNAME", 5, "MX", 15, "TXT", 16, "AAAA", 28);
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$");

    private final SecureRandom random = new SecureRandom();

    @Override
    public Set<CheckType> types() {
        return Set.of(CheckType.DNS);
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 253) {
            return false;
        }
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (n.isEmpty()) {
            return false;
        }
        for (String label : n.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ProbeResult execute(Target target) {
        String name = target.param("name", "example.com");
        Integer qtype = RECORD_TYPES.get(target.param("recordType", "A").toUpperCase());
        if (!AddressValidator.isValidIp(target.address()) || !isValidName(name) || qtype == null) {
            return ProbeResult.fail("parâmetros inválidos");
        }
        int port = target.port() == null ? 53 : target.port();
        int id = random.nextInt(0x10000);
        byte[] query = buildQuery(id, name, qtype);
        long start = System.nanoTime();
        try {
            byte[] response = udp(target.address(), port, query, target.timeoutMs());
            if (response.length >= 4 && (response[2] & 0x02) != 0) {      // TC: truncada, repete em TCP
                response = tcp(target.address(), port, query, target.timeoutMs());
            }
            int ms = TcpProbe.elapsedMs(start);
            return evaluate(response, id).orElse(ProbeResult.ok(ms));
        } catch (SocketTimeoutException e) {
            return ProbeResult.fail("timeout");
        } catch (IOException e) {
            return ProbeResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Valida o cabeçalho da resposta; devolve a falha, ou vazio se a resposta é aceitável. */
    static java.util.Optional<ProbeResult> evaluate(byte[] r, int expectedId) {
        if (r.length < 12) {
            return java.util.Optional.of(ProbeResult.fail("resposta curta"));
        }
        ByteBuffer b = ByteBuffer.wrap(r);
        int id = b.getShort(0) & 0xFFFF;
        int flags = b.getShort(2) & 0xFFFF;
        int rcode = flags & 0x0F;
        int answers = b.getShort(6) & 0xFFFF;
        if (id != expectedId) {
            return java.util.Optional.of(ProbeResult.fail("id da resposta não confere"));
        }
        if ((flags & 0x8000) == 0) {
            return java.util.Optional.of(ProbeResult.fail("não é uma resposta"));
        }
        if (rcode != 0) {
            return java.util.Optional.of(ProbeResult.fail("rcode " + rcode + (rcode == 3 ? " (NXDOMAIN)" : "")));
        }
        if (answers == 0) {
            return java.util.Optional.of(ProbeResult.fail("sem registros na resposta"));
        }
        return java.util.Optional.empty();
    }

    static byte[] buildQuery(int id, String name, int qtype) {
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        ByteBuffer b = ByteBuffer.allocate(12 + n.length() + 2 + 4);
        b.putShort((short) id).putShort((short) 0x0100).putShort((short) 1).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0);
        for (String label : n.split("\\.")) {
            b.put((byte) label.length()).put(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        b.put((byte) 0).putShort((short) qtype).putShort((short) 1);
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    private static byte[] udp(String address, int port, byte[] query, int timeoutMs) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(query, query.length, InetAddress.getByName(address), port));
            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            return java.util.Arrays.copyOf(buf, packet.getLength());
        }
    }

    private static byte[] tcp(String address, int port, byte[] query, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] resp = new byte[in.readUnsignedShort()];
            in.readFully(resp);
            return resp;
        }
    }
}
