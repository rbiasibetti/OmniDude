package com.omnidude.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Trava o isolamento da unidade systemd: quem afrouxar uma diretiva (ou ligar uma que quebra a JVM) falha o build,
 * em vez de descobrir em produção.
 */
class SystemdUnitTest {

    private static final Path UNIT = Path.of("packaging", "systemd", "omnidude.service");

    /** Diretiva=valor da seção [Service]; diretivas repetidas (Environment=) ficam com a última. */
    private static Map<String, String> service() throws IOException {
        boolean inService = false;
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String raw : Files.readAllLines(UNIT)) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inService = line.equals("[Service]");
            } else if (inService && !line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                int eq = line.indexOf('=');
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return out;
    }

    @Test
    void roda_sem_privilegios_com_dados_privados() throws IOException {
        Map<String, String> s = service();
        assertThat(s).containsEntry("User", "omnidude").containsEntry("Group", "omnidude");
        assertThat(s).containsEntry("NoNewPrivileges", "yes")
                .containsEntry("StateDirectory", "omnidude")
                .containsEntry("StateDirectoryMode", "0700")
                .containsEntry("UMask", "0077");
    }

    @Test
    void sistema_de_arquivos_somente_leitura_exceto_o_estado() throws IOException {
        Map<String, String> s = service();
        assertThat(s).containsEntry("ProtectSystem", "strict")
                .containsEntry("ProtectHome", "yes")
                .containsEntry("PrivateTmp", "yes")
                .containsEntry("PrivateDevices", "yes");
        assertThat(s).doesNotContainKey("ReadWritePaths");
    }

    @Test
    void so_a_capacidade_do_ping() throws IOException {
        Map<String, String> s = service();
        assertThat(s).containsEntry("CapabilityBoundingSet", "CAP_NET_RAW").containsEntry("AmbientCapabilities", "CAP_NET_RAW");
    }

    @Test
    void kernel_namespaces_e_chamadas_de_sistema_restritos() throws IOException {
        Map<String, String> s = service();
        for (String k : List.of("ProtectKernelTunables", "ProtectKernelModules", "ProtectKernelLogs", "ProtectControlGroups",
                "RestrictNamespaces", "RestrictRealtime", "RestrictSUIDSGID", "LockPersonality")) {
            assertThat(s).as(k).containsEntry(k, "yes");
        }
        assertThat(s).containsEntry("SystemCallFilter", "@system-service");
        List<String> families = List.of(s.get("RestrictAddressFamilies").split("\\s+"));
        assertThat(families).containsExactlyInAnyOrder("AF_INET", "AF_INET6", "AF_UNIX", "AF_NETLINK");
    }

    @Test
    void nao_liga_o_que_quebra_a_jvm() throws IOException {
        // O JIT precisa de memória gravável e executável.
        assertThat(service().get("MemoryDenyWriteExecute")).isNotEqualTo("yes");
        assertThat(Files.readString(UNIT)).doesNotContain("User=root");
    }

    @Test
    void reinicia_e_encerra_com_graca_e_le_a_configuracao_do_env() throws IOException {
        Map<String, String> s = service();
        assertThat(s).containsEntry("Restart", "on-failure").containsEntry("SuccessExitStatus", "143");
        assertThat(s.get("EnvironmentFile")).contains("/etc/omnidude/omnidude.env");
        assertThat(s.get("ExecStart")).startsWith("/usr/bin/java ").contains("-jar /opt/omnidude/omnidude.jar");
        assertThat(Files.readString(UNIT)).contains("-XX:+ExitOnOutOfMemoryError");
    }

    @Test
    void env_de_exemplo_nao_traz_segredos_ativos() throws IOException {
        Path env = Path.of("packaging", "systemd", "omnidude.env.example");
        String active = Files.readAllLines(env).stream()
                .filter(l -> !l.isBlank() && !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));
        assertThat(active).as("o exemplo deve vir todo comentado").isEmpty();
    }

    @Test
    void scripts_de_instalacao_existem_e_sao_executaveis() {
        for (String f : List.of("install.sh", "uninstall.sh")) {
            Path p = Path.of("packaging", f);
            assertThat(p).exists();
            assertThat(Files.isExecutable(p)).as(f).isTrue();
        }
    }
}
