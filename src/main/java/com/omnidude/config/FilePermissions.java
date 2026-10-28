package com.omnidude.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Permissões POSIX do diretório de dados, do banco e da chave. Em sistemas sem POSIX (Windows) não faz nada. */
public final class FilePermissions {

    private static final Logger log = LoggerFactory.getLogger(FilePermissions.class);
    private static final Set<PosixFilePermission> OTHERS_OR_GROUP = Set.of(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

    private FilePermissions() {
    }

    /** Atributo para criar um arquivo já com {@code rw-------}, sem a janela entre criar e restringir. */
    public static java.nio.file.attribute.FileAttribute<Set<PosixFilePermission>> ownerOnlyFile() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    }

    public static boolean isPosix(Path p) {
        return p.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Deixa só o dono acessar ({@code rwx------} para diretório, {@code rw-------} para arquivo). Devolve se mudou algo. */
    public static boolean restrict(Path p) {
        try {
            if (!Files.exists(p) || !isPosix(p)) {
                return false;
            }
            Set<PosixFilePermission> now = Files.getPosixFilePermissions(p);
            if (now.stream().noneMatch(OTHERS_OR_GROUP::contains)) {
                return false;
            }
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
            log.info("Permissões de {} restringidas ao dono (eram {})", p, PosixFilePermissions.toString(now));
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Não foi possível restringir as permissões de {}: {}", p, e.toString());
            return false;
        }
    }
}
