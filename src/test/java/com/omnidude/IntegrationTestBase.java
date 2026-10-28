package com.omnidude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Contexto único e banco SQLite temporário compartilhados pelos testes de integração. */
@SpringBootTest(properties = {
        "omnidude.monitoring.scheduler-enabled=false",
        "omnidude.traffic.enabled=false",
        "omnidude.bootstrap-admin-password=Test-Pass-12345"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("omnidude-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("omnidude.data-dir", DATA_DIR::toString);
        registry.add("omnidude.secret-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
}
