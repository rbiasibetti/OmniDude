package com.omnidude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Servidor SMTP mínimo para os testes: aceita mensagens, opcionalmente exige AUTH PLAIN, e guarda o que recebeu. */
public class FakeSmtpServer implements AutoCloseable {

    public record Received(String from, List<String> to, String data) {
    }

    private final ServerSocket server;
    private final String user;
    private final String password;
    private final List<Received> messages = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public FakeSmtpServer(String user, String password) throws IOException {
        this.user = user;
        this.password = password;
        this.server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().start(this::accept);
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<Received> messages() {
        return messages;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket s = server.accept();
                Thread.ofVirtual().start(() -> session(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void session(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
             OutputStream out = s.getOutputStream()) {
            send(out, "220 fake ESMTP");
            String from = null;
            List<String> to = new ArrayList<>();
            boolean authed = user == null;
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.toUpperCase();
                if (cmd.startsWith("EHLO") || cmd.startsWith("HELO")) {
                    send(out, "250-fake\r\n" + (user != null ? "250-AUTH PLAIN\r\n" : "") + "250 8BITMIME");
                } else if (cmd.startsWith("AUTH PLAIN")) {
                    String arg = line.length() > 11 ? line.substring(11).trim() : "";
                    if (arg.isEmpty()) {
                        send(out, "334 ");
                        arg = in.readLine();
                    }
                    String[] parts = new String(Base64.getDecoder().decode(arg), StandardCharsets.UTF_8).split("\0");
                    authed = parts.length == 3 && parts[1].equals(user) && parts[2].equals(password);
                    send(out, authed ? "235 ok" : "535 5.7.8 credenciais recusadas");
                } else if (cmd.startsWith("MAIL FROM")) {
                    if (!authed) {
                        send(out, "530 autenticação necessária");
                        continue;
                    }
                    from = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                    to = new ArrayList<>();
                    send(out, "250 ok");
                } else if (cmd.startsWith("RCPT TO")) {
                    to.add(line.substring(line.indexOf('<') + 1, line.indexOf('>')));
                    send(out, "250 ok");
                } else if (cmd.startsWith("DATA")) {
                    send(out, "354 envie");
                    StringBuilder data = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        data.append(line).append('\n');
                    }
                    messages.add(new Received(from, to, data.toString()));
                    send(out, "250 aceito");
                } else if (cmd.startsWith("QUIT")) {
                    send(out, "221 tchau");
                    return;
                } else if (cmd.startsWith("RSET") || cmd.startsWith("NOOP")) {
                    send(out, "250 ok");
                } else {
                    send(out, "502 comando não implementado");
                }
            }
        } catch (IOException ignored) {
            // cliente fechou
        }
    }

    private static void send(OutputStream out, String text) throws IOException {
        out.write((text + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }
}
