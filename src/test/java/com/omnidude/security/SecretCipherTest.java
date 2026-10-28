package com.omnidude.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omnidude.config.OmniDudeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretCipherTest {

    private static OmniDudeProperties props(String dir, String key) {
        return new OmniDudeProperties(dir, key, null, null, null, null, null);
    }

    @Test
    void roundTripWithConfiguredKey(@TempDir Path dir) throws IOException {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SecretCipher c = new SecretCipher(props(dir.toString(), key));
        String token = c.encrypt("public-community");
        assertThat(token).startsWith("v1:").doesNotContain("public-community");
        assertThat(c.decrypt(token)).isEqualTo("public-community");
    }

    @Test
    void sameInputGivesDifferentCiphertexts(@TempDir Path dir) throws IOException {
        SecretCipher c = new SecretCipher(props(dir.toString(), ""));
        assertThat(c.encrypt("x")).isNotEqualTo(c.encrypt("x"));
    }

    @Test
    void generatedKeyFileIsPersistedAndReused(@TempDir Path dir) throws IOException {
        SecretCipher first = new SecretCipher(props(dir.toString(), ""));
        String token = first.encrypt("segredo");
        assertThat(Files.exists(dir.resolve("secret.key"))).isTrue();
        assertThat(new SecretCipher(props(dir.toString(), "")).decrypt(token)).isEqualTo("segredo");
    }

    @Test
    void wrongKeyOrTamperingIsRejected(@TempDir Path dir) throws IOException {
        SecretCipher a = new SecretCipher(props(dir.toString(), Base64.getEncoder().encodeToString(new byte[32])));
        byte[] other = new byte[32];
        other[0] = 1;
        SecretCipher b = new SecretCipher(props(dir.toString(), Base64.getEncoder().encodeToString(other)));
        String token = a.encrypt("x");
        assertThatThrownBy(() -> b.decrypt(token)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> a.decrypt("texto-puro")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyWithWrongSizeIsRejected(@TempDir Path dir) {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new SecretCipher(props(dir.toString(), shortKey)))
                .isInstanceOf(IllegalStateException.class);
    }
}
