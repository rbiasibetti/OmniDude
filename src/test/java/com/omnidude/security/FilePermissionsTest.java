package com.omnidude.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.config.FilePermissions;
import com.omnidude.config.OmniDudeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePermissionsTest {

    private static String perms(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    @Test
    void restrictClosesDirectoriesAndFilesToTheOwnerAndIsIdempotent(@TempDir Path dir) throws Exception {
        Path data = Files.createDirectory(dir.resolve("dados"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        Path db = Files.createFile(data.resolve("omnidude.db"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        assertThat(FilePermissions.restrict(data)).isTrue();
        assertThat(FilePermissions.restrict(db)).isTrue();
        assertThat(perms(data)).isEqualTo("rwx------");
        assertThat(perms(db)).isEqualTo("rw-------");
        assertThat(FilePermissions.restrict(db)).as("já restrito: nada a fazer").isFalse();
        assertThat(FilePermissions.restrict(data.resolve("nao-existe"))).isFalse();
    }

    @Test
    void theGeneratedEncryptionKeyIsBornOwnerOnly(@TempDir Path dir) throws Exception {
        new SecretCipher(new OmniDudeProperties(dir.toString(), "", null, null, null, null, null));
        assertThat(perms(dir.resolve("secret.key"))).isEqualTo("rw-------");
    }

    @Test
    void anExistingWorldReadableKeyIsTightenedOnStartup(@TempDir Path dir) throws Exception {
        Path key = Files.writeString(dir.resolve("secret.key"), java.util.Base64.getEncoder().encodeToString(new byte[32]));
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));
        new SecretCipher(new OmniDudeProperties(dir.toString(), "", null, null, null, null, null));
        assertThat(perms(key)).isEqualTo("rw-------");
    }

    @Test
    void startupCheckRestrictsTheDataFilesIncludingWalAndBackups(@TempDir Path dir) throws Exception {
        for (String f : new String[] {"omnidude.db", "omnidude.db-wal", "omnidude.db-shm", "fingerprints.yml", "outro.txt"}) {
            Path p = Files.writeString(dir.resolve(f), "x");
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-rw-r--"));
        }
        Path backups = Files.createDirectory(dir.resolve("backups"));
        Files.setPosixFilePermissions(backups, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        new SecurityStartupCheck(new org.springframework.mock.env.MockEnvironment(),
                new OmniDudeProperties(dir.toString(), "", null, null, null, null, null)).restrictDataFiles();

        assertThat(perms(dir)).isEqualTo("rwx------");
        assertThat(perms(backups)).isEqualTo("rwx------");
        for (String f : new String[] {"omnidude.db", "omnidude.db-wal", "omnidude.db-shm", "fingerprints.yml"}) {
            assertThat(perms(dir.resolve(f))).as(f).isEqualTo("rw-------");
        }
        assertThat(perms(dir.resolve("outro.txt"))).as("o que não é do OmniDude não é tocado").isEqualTo("rw-rw-r--");
    }
}
