package com.omnidude.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A V8 transforma o MAC que cada dispositivo já tinha em identidade (device_macs), marcando os aleatórios. */
class IdentityMigrationTest {

    @Test
    void existingMacsBecomeIdentityAndRandomOnesAreFlagged(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("omnidude.db") + "?foreign_keys=true";
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .javaMigrations(new V5__DeviceTypes()).target("7").load().migrate();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("INSERT INTO devices(id, name, primary_ip, mac_address, created_at, updated_at, virtual) VALUES "
                    + "(1, 'estavel', '10.0.0.1', 'e4:8d:8c:00:00:01', 1, 1, 0), "
                    + "(2, 'aleatorio', '10.0.0.2', 'da:a1:19:00:00:02', 1, 1, 0), "
                    + "(3, 'qemu', '10.0.0.3', '52:54:00:12:34:56', 1, 1, 0), "
                    + "(4, 'sem-mac', '10.0.0.4', NULL, 1, 1, 0), "
                    + "(5, 'nuvem', 'logical:x', '00:00:00:00:00:05', 1, 1, 1), "
                    + "(6, 'maiuscula', '10.0.0.6', 'AA:BB:CC:00:00:06', 1, 1, 0)");
        }
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .javaMigrations(new V5__DeviceTypes()).load().migrate();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT device_id, mac, source, random FROM device_macs ORDER BY device_id")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(1);
            assertThat(rs.getString(2)).isEqualTo("e4:8d:8c:00:00:01");
            assertThat(rs.getString(3)).isEqualTo("DEVICE");
            assertThat(rs.getInt(4)).as("OUI de fabricante: estável").isZero();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(2);
            assertThat(rs.getInt(4)).as("administrado localmente: aleatório").isEqualTo(1);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(3);
            assertThat(rs.getInt(4)).as("52:54:00 (QEMU) também tem o bit ligado").isEqualTo(1);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isEqualTo(6);
            assertThat(rs.getString(2)).as("normalizado para minúsculas").isEqualTo("aa:bb:cc:00:00:06");
            assertThat(rs.next()).as("sem MAC e nó virtual ficam de fora").isFalse();
        }
    }
}
