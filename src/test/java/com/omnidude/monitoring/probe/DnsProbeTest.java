package com.omnidude.monitoring.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.monitoring.CheckType;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DnsProbeTest {

    private final DnsProbe probe = new DnsProbe();
    private DatagramSocket server;
    private Thread thread;
    private final AtomicInteger received = new AtomicInteger();

    /** Servidor DNS falso: o handler recebe a consulta e devolve os bytes da resposta (ou null para não responder). */
    private int serve(Function<byte[], byte[]> handler) throws IOException {
        server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        thread = Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[512];
            while (!server.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    server.receive(p);
                    received.incrementAndGet();
                    byte[] reply = handler.apply(java.util.Arrays.copyOf(buf, p.getLength()));
                    if (reply != null) {
                        server.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
                    }
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** Resposta mínima: eco do id e da pergunta, com flags/rcode e contagem de respostas dados. */
    private static byte[] reply(byte[] query, int flags, int answers) {
        byte[] r = java.util.Arrays.copyOf(query, query.length + (answers > 0 ? 16 : 0));
        r[2] = (byte) (flags >> 8);
        r[3] = (byte) flags;
        r[6] = (byte) (answers >> 8);
        r[7] = (byte) answers;
        if (answers > 0) {                      // um registro A fictício: ponteiro de nome, tipo A, IN, ttl, rdlen 4, ip
            int o = query.length;
            byte[] rr = {(byte) 0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 10, 0, 0, 1};
            System.arraycopy(rr, 0, r, o, rr.length);
        }
        return r;
    }

    private ProbeResult run(int port, int timeoutMs, String... kv) {
        Map<String, String> params = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            params.put(kv[i], kv[i + 1]);
        }
        return probe.execute(new Target(CheckType.DNS, "127.0.0.1", port, timeoutMs, params, null));
    }

    @Test
    void answerWithRecordsSucceeds() throws IOException {
        int port = serve(q -> reply(q, 0x8180, 1));
        ProbeResult r = run(port, 1000, "name", "intranet.local");
        assertThat(r.success()).isTrue();
        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    void nxdomainFails() throws IOException {
        int port = serve(q -> reply(q, 0x8183, 0));
        ProbeResult r = run(port, 1000);
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("NXDOMAIN");
    }

    @Test
    void emptyAnswerFails() throws IOException {
        int port = serve(q -> reply(q, 0x8180, 0));
        assertThat(run(port, 1000).detail()).contains("sem registros");
    }

    @Test
    void mismatchedIdIsRejected() throws IOException {
        int port = serve(q -> {
            byte[] r = reply(q, 0x8180, 1);
            r[0] ^= 0x5A;
            return r;
        });
        assertThat(run(port, 1000).detail()).contains("id");
    }

    @Test
    void noReplyTimesOut() throws IOException {
        int port = serve(q -> null);
        ProbeResult r = run(port, 400);
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("timeout");
    }

    @Test
    void queryEncodingIsWellFormed() {
        byte[] q = DnsProbe.buildQuery(0x1234, "www.example.com.", 28);
        assertThat(q[0]).isEqualTo((byte) 0x12);
        assertThat(q[1]).isEqualTo((byte) 0x34);
        assertThat(q[12]).isEqualTo((byte) 3);                     // len("www")
        assertThat(new String(q, 13, 3)).isEqualTo("www");
        assertThat(q[q.length - 4]).isEqualTo((byte) 0);           // QTYPE AAAA = 28
        assertThat(q[q.length - 3]).isEqualTo((byte) 28);
        assertThat(q[q.length - 1]).isEqualTo((byte) 1);           // QCLASS IN
    }

    @Test
    void nameValidation() {
        assertThat(DnsProbe.isValidName("example.com")).isTrue();
        assertThat(DnsProbe.isValidName("srv-1._tcp.example.com.")).isTrue();
        assertThat(DnsProbe.isValidName("")).isFalse();
        assertThat(DnsProbe.isValidName("a..b")).isFalse();
        assertThat(DnsProbe.isValidName("-bad.com")).isFalse();
        assertThat(DnsProbe.isValidName("bad name.com")).isFalse();
        assertThat(DnsProbe.isValidName("a".repeat(64) + ".com")).isFalse();
        assertThat(DnsProbe.isValidName("x;rm.com")).isFalse();
    }

    @Test
    void invalidParametersNeverHitTheNetwork() {
        assertThat(run(53, 300, "name", "bad name").success()).isFalse();
        assertThat(run(53, 300, "recordType", "ANY").success()).isFalse();
    }
}
