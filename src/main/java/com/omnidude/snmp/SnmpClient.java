package com.omnidude.snmp;

import com.omnidude.monitoring.probe.SnmpCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthHMAC384SHA512;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Único ponto de acesso ao SNMP4J. Uma sessão UDP compartilhada atende v1/v2c (o SNMP4J demultiplexa as respostas por
 * request-id). Cada conjunto distinto de credenciais v3 ganha a sua própria sessão: a tabela USM é indexada pelo nome
 * do usuário, então dois perfis com o mesmo usuário e senhas diferentes colidiriam numa sessão única.
 */
@Component
public class SnmpClient {

    private static final Logger log = LoggerFactory.getLogger(SnmpClient.class);
    private static final long V3_IDLE_MS = 10 * 60_000L;

    /** Falha de rede, timeout ou erro do agente. */
    public static class SnmpException extends IOException {
        public SnmpException(String message) {
            super(message);
        }
    }

    private static final class V3Session {
        final Snmp snmp;
        final String username;
        final int securityLevel;
        volatile long lastUsedMs = System.currentTimeMillis();

        V3Session(Snmp snmp, String username, int securityLevel) {
            this.snmp = snmp;
            this.username = username;
            this.securityLevel = securityLevel;
        }
    }

    private Snmp community;
    private final Map<String, V3Session> v3Sessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() throws IOException {
        community = new Snmp(new DefaultUdpTransportMapping());
        community.listen();
    }

    @PreDestroy
    public void stop() throws IOException {
        for (V3Session s : v3Sessions.values()) {
            closeQuietly(s.snmp);
        }
        v3Sessions.clear();
        if (community != null) {
            community.close();
        }
    }

    /** Fecha sessões v3 sem uso (perfis editados ou apagados deixam sessões órfãs). */
    @Scheduled(fixedDelay = 5 * 60_000L, initialDelay = 5 * 60_000L)
    void evictIdleSessions() {
        long limit = System.currentTimeMillis() - V3_IDLE_MS;
        v3Sessions.entrySet().removeIf(e -> {
            if (e.getValue().lastUsedMs < limit) {
                closeQuietly(e.getValue().snmp);
                return true;
            }
            return false;
        });
    }

    /** GET de uma ou mais variáveis; valores {@code noSuchObject} voltam como exceção do Variable (não lançam). */
    public List<VariableBinding> get(String ip, int port, SnmpCredentials cred, int timeoutMs, OID... oids)
            throws SnmpException {
        Session session = session(cred);
        PDU pdu = cred.isV3() ? new ScopedPDU() : new PDU();
        pdu.setType(PDU.GET);
        for (OID oid : oids) {
            pdu.add(new VariableBinding(oid));
        }
        try {
            ResponseEvent<UdpAddress> event = session.snmp.send(pdu, target(session, ip, port, cred, timeoutMs));
            PDU response = event.getResponse();
            if (response == null) {
                throw new SnmpException(event.getError() != null ? event.getError().getMessage() : "timeout");
            }
            if (response.getErrorStatus() != PDU.noError) {
                throw new SnmpException("erro SNMP: " + response.getErrorStatusText());
            }
            return new ArrayList<>(response.getVariableBindings());
        } catch (SnmpException e) {
            throw e;
        } catch (IOException e) {
            throw new SnmpException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Percorre a subárvore de {@code root} (GETBULK em v2c/v3, GETNEXT em v1). */
    public List<VariableBinding> walk(String ip, int port, SnmpCredentials cred, int timeoutMs, OID root)
            throws SnmpException {
        Session session = session(cred);
        TreeUtils tree = new TreeUtils(session.snmp,
                new DefaultPDUFactory("1".equals(cred.version()) ? PDU.GETNEXT : PDU.GETBULK));
        List<VariableBinding> out = new ArrayList<>();
        try {
            for (TreeEvent event : tree.getSubtree(target(session, ip, port, cred, timeoutMs), root)) {
                if (event.isError()) {
                    throw new SnmpException(event.getErrorMessage());
                }
                if (event.getVariableBindings() != null) {
                    for (VariableBinding vb : event.getVariableBindings()) {
                        if (vb != null && !vb.getVariable().isException()) {
                            out.add(vb);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            if (e instanceof SnmpException se) {
                throw se;
            }
            throw new SnmpException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return out;
    }

    private record Session(Snmp snmp, String v3User, int v3Level) {
    }

    private Session session(SnmpCredentials cred) throws SnmpException {
        if (!cred.isV3()) {
            return new Session(community, null, 0);
        }
        SnmpCredentials.V3 v3 = cred.v3();
        if (v3 == null) {
            throw new SnmpException("credenciais SNMPv3 ausentes");
        }
        V3Session s = v3Sessions.computeIfAbsent(fingerprint(v3), k -> createV3(v3));
        s.lastUsedMs = System.currentTimeMillis();
        return new Session(s.snmp, s.username, s.securityLevel);
    }

    private V3Session createV3(SnmpCredentials.V3 v3) {
        try {
            USM usm = new USM(securityProtocols(), new OctetString(MPv3.createLocalEngineID()), 0);
            MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
            dispatcher.addMessageProcessingModel(new MPv3(usm));
            Snmp snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping());
            snmp.getUSM().addUser(usmUser(v3));
            snmp.listen();
            return new V3Session(snmp, v3.username(), securityLevel(v3));
        } catch (IOException e) {
            throw new IllegalStateException("não foi possível abrir a sessão SNMPv3: " + e.getMessage(), e);
        }
    }

    /** O SNMP4J só registra SHA-2 por padrão; o SHA-1 (HMAC-SHA-96) é o mais comum nos equipamentos, então entra aqui. */
    public static SecurityProtocols securityProtocols() {
        SecurityProtocols protocols = SecurityProtocols.getInstance().addDefaultProtocols();
        if (protocols.getAuthenticationProtocol(AuthSHA.ID) == null) {
            protocols.addAuthenticationProtocol(new AuthSHA());
        }
        return protocols;
    }

    static UsmUser usmUser(SnmpCredentials.V3 v3) {
        OID auth = v3.authenticates() ? authProtocol(v3.authProtocol()) : null;
        OID priv = v3.encrypts() ? privProtocol(v3.privProtocol()) : null;
        return new UsmUser(new OctetString(v3.username()), auth,
                auth == null ? null : new OctetString(v3.authPassword()),
                priv, priv == null ? null : new OctetString(v3.privPassword()));
    }

    static int securityLevel(SnmpCredentials.V3 v3) {
        if (v3.encrypts()) {
            return SecurityLevel.AUTH_PRIV;
        }
        return v3.authenticates() ? SecurityLevel.AUTH_NOPRIV : SecurityLevel.NOAUTH_NOPRIV;
    }

    static OID authProtocol(String name) {
        return switch (name == null ? "" : name) {
            case "SHA" -> AuthSHA.ID;
            case "SHA256" -> AuthHMAC192SHA256.ID;
            case "SHA512" -> AuthHMAC384SHA512.ID;
            default -> throw new IllegalArgumentException("protocolo de autenticação desconhecido: " + name);
        };
    }

    static OID privProtocol(String name) {
        return switch (name == null ? "" : name) {
            case "AES128" -> PrivAES128.ID;
            case "AES192" -> PrivAES192.ID;
            case "AES256" -> PrivAES256.ID;
            default -> throw new IllegalArgumentException("protocolo de privacidade desconhecido: " + name);
        };
    }

    /** Identifica o conjunto de credenciais sem guardar as senhas como chave do mapa. */
    private static String fingerprint(SnmpCredentials.V3 v3) {
        String joined = String.join("\0", nz(v3.username()), nz(v3.securityLevel()), nz(v3.authProtocol()),
                nz(v3.authPassword()), nz(v3.privProtocol()), nz(v3.privPassword()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Target<UdpAddress> target(Session session, String ip, int port, SnmpCredentials cred, int timeoutMs)
            throws SnmpException {
        try {
            UdpAddress address = new UdpAddress(InetAddress.getByName(ip), port);
            Target<UdpAddress> t;
            if (cred.isV3()) {
                UserTarget<UdpAddress> u = new UserTarget<>();
                u.setSecurityName(new OctetString(session.v3User));
                u.setSecurityLevel(session.v3Level);
                u.setVersion(SnmpConstants.version3);
                t = u;
            } else {
                CommunityTarget<UdpAddress> c = new CommunityTarget<>();
                c.setCommunity(new OctetString(cred.community()));
                c.setVersion("1".equals(cred.version()) ? SnmpConstants.version1 : SnmpConstants.version2c);
                t = c;
            }
            t.setAddress(address);
            t.setTimeout(timeoutMs);
            t.setRetries(cred.retries());
            return t;
        } catch (IOException e) {
            throw new SnmpException("endereço inválido: " + ip);
        }
    }

    private static void closeQuietly(Snmp snmp) {
        try {
            snmp.close();
        } catch (IOException e) {
            log.debug("Falha ao fechar sessão SNMP: {}", e.toString());
        }
    }
}
