package com.omnidude.security;

import com.omnidude.config.OmniDudeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM para segredos em repouso (comunidades SNMP, tokens). Formato: {@code v1:base64(iv|cifra)}.
 * A chave vem de OMNIDUDE_SECRET_KEY (base64 de 32 bytes) ou de {@code secret.key} no diretório de dados.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public SecretCipher(OmniDudeProperties props) throws IOException {
        this.key = new SecretKeySpec(loadKey(props), "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar", e);
        }
    }

    public String decrypt(String token) {
        if (token == null || !token.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Formato de segredo desconhecido");
        }
        try {
            byte[] in = Base64.getDecoder().decode(token.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, java.util.Arrays.copyOf(in, IV_BYTES)));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar (chave incorreta ou dado corrompido)", e);
        }
    }

    private static byte[] loadKey(OmniDudeProperties props) throws IOException {
        String configured = props.secretKey();
        if (configured != null && !configured.isBlank()) {
            return decodeKey(configured.trim());
        }
        Path file = Path.of(props.dataDir()).toAbsolutePath().resolve("secret.key");
        if (Files.exists(file)) {
            com.omnidude.config.FilePermissions.restrict(file);     // uma chave legível por outros usuários não protege nada
            return decodeKey(Files.readString(file).trim());
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        Files.createDirectories(file.getParent());
        if (com.omnidude.config.FilePermissions.isPosix(file)) {
            Files.createFile(file, com.omnidude.config.FilePermissions.ownerOnlyFile());   // já nasce 600: sem janela de leitura
            Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
        } else {
            Files.writeString(file, Base64.getEncoder().encodeToString(fresh));
            log.warn("Sistema de arquivos sem permissões POSIX: proteja {} manualmente", file);
        }
        log.warn("Chave de criptografia gerada em {}. Faça backup dela junto com o banco, ou defina OMNIDUDE_SECRET_KEY.", file);
        return fresh;
    }

    private static byte[] decodeKey(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64);
        if (raw.length != 32) {
            throw new IllegalStateException("A chave de criptografia deve ter 32 bytes (base64)");
        }
        return raw;
    }
}
