package com.omnidude.snmp;

import com.omnidude.monitoring.probe.SnmpCredentials;
import com.omnidude.security.SecretCipher;
import com.omnidude.web.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnmpProfileService {

    public record ProfileRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "1|2c|3", message = "versão deve ser 1, 2c ou 3") String version,
            @Min(1) @Max(65535) Integer port,
            @Min(200) @Max(30000) Integer timeoutMs,
            @Min(0) @Max(5) Integer retries,
            @Size(max = 255) String community,
            @Size(max = 80) String username,
            @Pattern(regexp = "NO_AUTH_NO_PRIV|AUTH_NO_PRIV|AUTH_PRIV", message = "nível de segurança inválido")
            String securityLevel,
            @Pattern(regexp = "SHA|SHA256|SHA512", message = "protocolo de autenticação inválido") String authProtocol,
            @Size(max = 255) String authPassword,
            @Pattern(regexp = "AES128|AES192|AES256", message = "protocolo de privacidade inválido") String privProtocol,
            @Size(max = 255) String privPassword) {
    }

    /** Nunca inclui o segredo: só informa se existe. */
    public record ProfileView(Long id, String name, String version, int port, int timeoutMs, int retries,
                              boolean hasCommunity, String username, String securityLevel, String authProtocol,
                              boolean hasAuthPassword, String privProtocol, boolean hasPrivPassword) {
        static ProfileView of(SnmpProfile p) {
            return new ProfileView(p.getId(), p.getName(), p.getVersion(), p.getPort(), p.getTimeoutMs(),
                    p.getRetries(), p.getCommunityEnc() != null, p.getV3Username(), p.getV3SecurityLevel(),
                    p.getV3AuthProtocol(), p.getV3AuthPassEnc() != null, p.getV3PrivProtocol(),
                    p.getV3PrivPassEnc() != null);
        }
    }

    private final SnmpProfileRepository profiles;
    private final SecretCipher cipher;

    public SnmpProfileService(SnmpProfileRepository profiles, SecretCipher cipher) {
        this.profiles = profiles;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public List<ProfileView> list() {
        return profiles.findAllByOrderByNameAsc().stream().map(ProfileView::of).toList();
    }

    @Transactional
    public ProfileView create(ProfileRequest req) {
        if (profiles.existsByName(req.name().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um perfil com esse nome");
        }
        SnmpProfile p = new SnmpProfile();
        apply(p, req, true);
        return ProfileView.of(profiles.save(p));
    }

    @Transactional
    public ProfileView update(long id, ProfileRequest req) {
        SnmpProfile p = profiles.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Perfil não encontrado"));
        if (profiles.existsByNameAndIdNot(req.name().trim(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um perfil com esse nome");
        }
        apply(p, req, false);
        return ProfileView.of(p);
    }

    @Transactional
    public void delete(long id) {
        if (!profiles.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Perfil não encontrado");
        }
        profiles.deleteById(id);   // dispositivos que o usam ficam sem perfil (ON DELETE SET NULL)
    }

    @Transactional(readOnly = true)
    public boolean exists(long id) {
        return profiles.existsById(id);
    }

    /** Credenciais descriptografadas para a sonda; vazio se o perfil não existe ou não é utilizável. */
    @Transactional(readOnly = true)
    public Optional<SnmpCredentials> credentials(Long profileId) {
        return profileId == null ? Optional.empty() : profiles.findById(profileId).flatMap(this::credentialsOf);
    }

    /** Credenciais + porta/timeout do perfil, para descoberta, inventário e coleta de tráfego. */
    @Transactional(readOnly = true)
    public Optional<SnmpAccess> access(Long profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return profiles.findById(profileId).flatMap(p -> credentialsOf(p)
                .map(c -> new SnmpAccess(p.getId(), c, p.getPort(), p.getTimeoutMs())));
    }

    private Optional<SnmpCredentials> credentialsOf(SnmpProfile p) {
        if ("3".equals(p.getVersion())) {
            if (p.getV3Username() == null || p.getV3SecurityLevel() == null) {
                return Optional.empty();
            }
            var v3 = new SnmpCredentials.V3(p.getV3Username(), p.getV3SecurityLevel(), p.getV3AuthProtocol(),
                    decryptOrNull(p.getV3AuthPassEnc()), p.getV3PrivProtocol(), decryptOrNull(p.getV3PrivPassEnc()));
            return Optional.of(new SnmpCredentials("3", null, p.getRetries(), v3));
        }
        return p.getCommunityEnc() == null ? Optional.empty()
                : Optional.of(new SnmpCredentials(p.getVersion(), cipher.decrypt(p.getCommunityEnc()), p.getRetries()));
    }

    private String decryptOrNull(String enc) {
        return enc == null ? null : cipher.decrypt(enc);
    }

    @Transactional(readOnly = true)
    public Optional<Integer> port(Long profileId) {
        return profileId == null ? Optional.empty() : profiles.findById(profileId).map(SnmpProfile::getPort);
    }

    private void apply(SnmpProfile p, ProfileRequest req, boolean creating) {
        boolean v3 = "3".equals(req.version());
        if (v3) {
            applyV3(p, req, creating);
        } else {
            boolean hasNewSecret = notBlank(req.community());
            if (creating && !hasNewSecret) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Informe a community SNMP");
            }
            if (hasNewSecret) {                       // em edição, vazio mantém o segredo atual
                p.setCommunityEnc(cipher.encrypt(req.community()));
            }
            p.setV3Username(null);                    // trocar de v3 para v2c não deixa credenciais velhas
            p.setV3SecurityLevel(null);
            p.setV3AuthProtocol(null);
            p.setV3AuthPassEnc(null);
            p.setV3PrivProtocol(null);
            p.setV3PrivPassEnc(null);
        }
        p.setName(req.name().trim());
        p.setVersion(req.version());
        p.setPort(req.port() == null ? 161 : req.port());
        p.setTimeoutMs(req.timeoutMs() == null ? 2000 : req.timeoutMs());
        p.setRetries(req.retries() == null ? 1 : req.retries());
    }

    private void applyV3(SnmpProfile p, ProfileRequest req, boolean creating) {
        if (!notBlank(req.username())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o usuário SNMPv3");
        }
        if (req.securityLevel() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o nível de segurança SNMPv3");
        }
        boolean auth = !"NO_AUTH_NO_PRIV".equals(req.securityLevel());
        boolean priv = "AUTH_PRIV".equals(req.securityLevel());
        if (auth && req.authProtocol() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o protocolo de autenticação");
        }
        if (priv && req.privProtocol() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o protocolo de privacidade");
        }
        boolean keepAuth = !creating && p.getV3AuthPassEnc() != null && !notBlank(req.authPassword());
        boolean keepPriv = !creating && p.getV3PrivPassEnc() != null && !notBlank(req.privPassword());
        if (auth && !keepAuth) {
            requirePassphrase(req.authPassword(), "autenticação");
        }
        if (priv && !keepPriv) {
            requirePassphrase(req.privPassword(), "privacidade");
        }
        p.setV3Username(req.username().trim());
        p.setV3SecurityLevel(req.securityLevel());
        p.setV3AuthProtocol(auth ? req.authProtocol() : null);
        p.setV3PrivProtocol(priv ? req.privProtocol() : null);
        if (!auth) {
            p.setV3AuthPassEnc(null);
        } else if (!keepAuth) {
            p.setV3AuthPassEnc(cipher.encrypt(req.authPassword()));
        }
        if (!priv) {
            p.setV3PrivPassEnc(null);
        } else if (!keepPriv) {
            p.setV3PrivPassEnc(cipher.encrypt(req.privPassword()));
        }
        p.setCommunityEnc(null);
    }

    /** O RFC 3414 exige ao menos 8 caracteres nas senhas USM. */
    private static void requirePassphrase(String value, String what) {
        if (!notBlank(value) || value.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A senha de " + what + " do SNMPv3 deve ter ao menos 8 caracteres");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
