package com.omnidude.notification;

import jakarta.mail.Authenticator;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import org.springframework.stereotype.Component;

/** E-mail por SMTP: sem criptografia, STARTTLS ou SSL; autenticação opcional. Texto simples em UTF-8. */
@Component
public class EmailNotifier implements Notifier {

    private static final String TIMEOUT = "10000";

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public void send(Map<String, String> c, com.omnidude.notification.Message m) throws NotifyException {
        String security = c.getOrDefault("security", "STARTTLS");
        Properties p = new Properties();
        p.put("mail.smtp.host", c.get("host"));
        p.put("mail.smtp.port", c.get("port"));
        p.put("mail.smtp.connectiontimeout", TIMEOUT);
        p.put("mail.smtp.timeout", TIMEOUT);
        p.put("mail.smtp.writetimeout", TIMEOUT);
        switch (security) {
            case "SSL" -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "STARTTLS" -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");        // nunca cai para texto puro sem avisar
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> { }
        }
        String user = c.get("username");
        boolean auth = user != null && !user.isBlank();
        p.put("mail.smtp.auth", String.valueOf(auth));
        Session session = auth ? Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, c.getOrDefault("password", ""));
            }
        }) : Session.getInstance(p);
        try {
            MimeMessage mail = new MimeMessage(session);
            mail.setFrom(new InternetAddress(c.get("from")));
            mail.setRecipients(RecipientType.TO, InternetAddress.parse(c.get("to")));
            mail.setSubject("[OmniDude] " + m.severity() + ": " + oneLine(m.text()), "UTF-8");
            mail.setSentDate(new Date(m.ts() * 1000));
            mail.setText(body(m), "UTF-8");
            Transport.send(mail);
        } catch (jakarta.mail.AuthenticationFailedException e) {
            throw new NotifyException("o servidor SMTP recusou o usuário ou a senha", false);
        } catch (MessagingException e) {
            // A mensagem da biblioteca pode citar o servidor, nunca a senha; ainda assim fica curta.
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new NotifyException("falha no envio do e-mail: " + (why.length() > 160 ? why.substring(0, 160) : why),
                    !(e instanceof jakarta.mail.internet.AddressException));
        }
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 150 ? t.substring(0, 150) : t;
    }

    private static String body(com.omnidude.notification.Message m) {
        return m.text() + "\n\n"
                + "Gravidade: " + m.severity() + "\n"
                + "Tipo: " + m.type() + "\n"
                + (m.deviceName() != null ? "Dispositivo: " + m.deviceName() + "\n" : "")
                + "Horário: " + java.time.Instant.ofEpochSecond(m.ts()) + "\n\n"
                + "-- OmniDude NMS";
    }
}
