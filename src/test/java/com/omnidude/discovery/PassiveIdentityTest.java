package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.discovery.PassiveIdentity.Identity;
import com.omnidude.discovery.PassiveIdentity.NetbiosName;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PassiveIdentityTest {

    private final List<AutoCloseable> closeables = new java.util.ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    /** Respondedor UDP em 127.0.0.1 numa porta livre; {@code reply} recebe o pedido e devolve a resposta (ou null). */
    private DatagramSocket udpResponder(Function<byte[], byte[]> reply) throws IOException {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        Thread t = Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[1500];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    byte[] r = reply.apply(java.util.Arrays.copyOf(p.getData(), p.getLength()));
                    if (r != null) {
                        socket.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        });
        closeables.add(socket::close);
        closeables.add(t::interrupt);
        return socket;
    }

    private static byte[] nbstatResponse(byte[] query, String computer, String group) {
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        r.write(query[0]);
        r.write(query[1]);
        r.writeBytes(new byte[] {(byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0});    // resposta, 1 resposta
        r.writeBytes(java.util.Arrays.copyOfRange(query, 12, 46));            // nome (34 bytes)
        r.writeBytes(new byte[] {0, 0x21, 0, 1, 0, 0, 0, 0});                 // tipo, classe, ttl
        r.write(0);
        r.write(1 + 2 * 18 + 6);                                              // rdlength
        r.write(2);                                                           // 2 nomes
        r.writeBytes(entry(computer, 0x00, 0x04));                            // único, ativo
        r.writeBytes(entry(group, 0x00, 0x84));                               // grupo
        r.writeBytes(new byte[6]);                                            // MAC da placa
        return r.toByteArray();
    }

    private static byte[] entry(String name, int suffix, int flags) {
        byte[] e = new byte[18];
        java.util.Arrays.fill(e, 0, 15, (byte) ' ');
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(n, 0, e, 0, n.length);
        e[15] = (byte) suffix;
        e[16] = (byte) flags;
        return e;
    }

    @Test
    void netbiosNameAndWorkgroup() throws Exception {
        DatagramSocket nb = udpResponder(q -> nbstatResponse(q, "PC-JOAO", "WORKGROUP"));
        PassiveIdentity id = new PassiveIdentity(nb.getLocalPort(), 1, 1);
        Optional<NetbiosName> r = id.netbios("127.0.0.1", 800);
        assertThat(r).isPresent();
        assertThat(r.get().computer()).isEqualTo("PC-JOAO");
        assertThat(r.get().workgroup()).isEqualTo("WORKGROUP");
    }

    @Test
    void silentOrClosedPortsContributeNothingAndReturnQuickly() throws Exception {
        int closed;
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            closed = s.getLocalPort();
        }
        long start = System.nanoTime();
        Identity r = new PassiveIdentity(closed, closed, closed).identify("127.0.0.1", 1000);
        assertThat(r.isEmpty()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).as("porta fechada encerra pelo ICMP, sem esperar o timeout inteiro")
                .isLessThan(1500);
    }

    @Test
    void garbledNetbiosResponseIsIgnored() throws Exception {
        DatagramSocket nb = udpResponder(q -> new byte[] {q[0], q[1], 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13});
        assertThat(new PassiveIdentity(nb.getLocalPort(), 1, 1).netbios("127.0.0.1", 600)).isEmpty();
    }

    @Test
    void upnpRouterIsIdentifiedByTypeAndDescription() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/desc.xml", ex -> {
            byte[] body = ("<root><device><deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>"
                    + "<friendlyName>Roteador da Sala</friendlyName><manufacturer>Acme Networks</manufacturer>"
                    + "<modelName>AX-1800</modelName></device></root>").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        http.start();
        closeables.add(() -> http.stop(0));
        String location = "http://127.0.0.1:" + http.getAddress().getPort() + "/desc.xml";
        DatagramSocket ssdp = udpResponder(q -> ("HTTP/1.1 200 OK\r\nSERVER: Linux/4.9 UPnP/1.0 miniupnpd/2.1\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nLOCATION: " + location + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));

        Identity r = new PassiveIdentity(1, ssdp.getLocalPort(), 1).identify("127.0.0.1", 800);
        assertThat(r.hostname()).isEqualTo("Roteador da Sala");
        assertThat(r.hints()).contains("UPnP: Acme Networks AX-1800").contains("[InternetGatewayDevice]");
    }

    @Test
    void upnpDescriptionOnAnotherHostIsNeverFetched() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] touched = {false};
        http.createContext("/", ex -> {
            touched[0] = true;
            ex.sendResponseHeaders(200, 0);
            ex.close();
        });
        http.start();
        closeables.add(() -> http.stop(0));
        // o host diz que a descrição está em outro endereço (127.0.0.2): ignorada, sem requisição
        String location = "http://127.0.0.2:" + http.getAddress().getPort() + "/x.xml";
        DatagramSocket ssdp = udpResponder(q -> ("HTTP/1.1 200 OK\r\nSERVER: x\r\nST: urn:schemas-upnp-org:device:Printer:1\r\n"
                + "LOCATION: " + location + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        Identity r = new PassiveIdentity(1, ssdp.getLocalPort(), 1).identify("127.0.0.1", 800);
        assertThat(touched[0]).isFalse();
        assertThat(r.hints()).contains("[Printer]");
    }

    @Test
    void mdnsReverseLookupGivesTheLocalName() throws Exception {
        DatagramSocket mdns = udpResponder(q -> {
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            r.write(q[0]);
            r.write(q[1]);
            r.writeBytes(new byte[] {(byte) 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0});
            r.writeBytes(new byte[] {(byte) 0xC0, 0x0C, 0, 12, 0, 1, 0, 0, 0, 120});      // nome por ponteiro, PTR, ttl
            byte[] name = new byte[] {7, 'i', 'p', 'h', 'o', 'n', 'e', '1', 5, 'l', 'o', 'c', 'a', 'l', 0};
            r.write(0);
            r.write(name.length);
            r.writeBytes(name);
            return r.toByteArray();
        });
        // o pedido precisa ter pelo menos o cabeçalho + a pergunta, para a resposta apontar para o offset 12
        Optional<String> n = new PassiveIdentity(1, 1, mdns.getLocalPort()).mdns("127.0.0.1", 800);
        assertThat(n).hasValue("iphone1");
    }

    @Test
    void hintsAreCombinedAcrossProtocols() throws Exception {
        DatagramSocket nb = udpResponder(q -> nbstatResponse(q, "SERVIDOR", "CASA"));
        DatagramSocket ssdp = udpResponder(q -> ("HTTP/1.1 200 OK\r\nSERVER: Samba UPnP\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        Identity r = new PassiveIdentity(nb.getLocalPort(), ssdp.getLocalPort(), 1).identify("127.0.0.1", 800);
        assertThat(r.hostname()).isEqualTo("SERVIDOR");
        assertThat(r.hints()).contains("NetBIOS: SERVIDOR (CASA)").contains("[MediaServer]");
    }
}
