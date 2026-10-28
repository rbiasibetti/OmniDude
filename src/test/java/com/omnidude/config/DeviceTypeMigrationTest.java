package com.omnidude.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A V5 recria {@code devices}. O risco real é o DROP TABLE disparar a cascata das chaves estrangeiras e apagar interfaces,
 * checagens, enlaces e posições: o teste migra um banco V4 já povoado, com as mesmas opções de conexão da aplicação.
 */
class DeviceTypeMigrationTest {

    private static String url(Path dir) {
        return "jdbc:sqlite:" + dir.resolve("omnidude.db") + "?foreign_keys=true&journal_mode=WAL&synchronous=NORMAL"
                + "&busy_timeout=10000&transaction_mode=IMMEDIATE";
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                .javaMigrations(new V5__DeviceTypes()).target(target).load();
    }

    @Test
    void existingDataSurvivesAndTheNewTypesBecomeValid(@TempDir Path dir) throws Exception {
        String url = url(dir);
        flyway(url, "4").migrate();
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("INSERT INTO devices(id, name, primary_ip, device_type, created_at, updated_at, mac_address, virtual) "
                    + "VALUES (1, 'sw', '10.0.0.1', 'SWITCH', 1, 1, 'aa:bb:cc:00:00:01', 0), "
                    + "(2, 'pc', '10.0.0.2', 'WORKSTATION', 1, 1, NULL, 0), (7, 'nuvem', 'logical:x', 'OTHER', 1, 1, NULL, 1)");
            st.execute("DELETE FROM devices WHERE id = 7");                       // o próximo id deve continuar em 8
            st.execute("INSERT INTO interfaces(device_id, if_index, name, monitored) VALUES (1, 1, 'Gi0/1', 1)");
            st.execute("INSERT INTO service_checks(device_id, type, name, interval_s, timeout_ms) VALUES (1, 'ICMP', 'Ping', 30, 2000)");
            st.execute("INSERT INTO links(source_device_id, target_device_id, origin, state, created_at) VALUES (1, 2, 'MANUAL', 'CONFIRMED', 1)");
            st.execute("INSERT OR IGNORE INTO maps(id, name, created_at) VALUES (1, 'Principal', 1)");
            st.execute("INSERT INTO map_nodes(map_id, device_id, x, y) VALUES (1, 1, 10, 20)");
            assertThatThrownBy(() -> st.execute("INSERT INTO devices(name, primary_ip, device_type, created_at, updated_at) "
                    + "VALUES ('x', '10.0.0.9', 'CLOUD', 1, 1)")).as("antes da V5, CLOUD é recusado").isInstanceOf(SQLException.class);
        }

        flyway(url, "5").migrate();

        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            assertThat(count(st, "devices")).isEqualTo(2);
            assertThat(count(st, "interfaces")).as("interfaces não caíram em cascata").isEqualTo(1);
            assertThat(count(st, "service_checks")).isEqualTo(1);
            assertThat(count(st, "links")).isEqualTo(1);
            assertThat(count(st, "map_nodes")).isEqualTo(1);
            try (ResultSet rs = st.executeQuery("SELECT mac_address, virtual, device_type FROM devices WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("aa:bb:cc:00:00:01");
                assertThat(rs.getInt(2)).isZero();
                assertThat(rs.getString(3)).isEqualTo("SWITCH");
            }
            st.execute("INSERT INTO devices(name, primary_ip, device_type, created_at, updated_at, virtual) "
                    + "VALUES ('Internet', 'logical:y', 'CLOUD', 1, 1, 1), ('LAN', 'logical:z', 'NETWORK', 1, 1, 1)");
            try (ResultSet rs = st.executeQuery("SELECT min(id) FROM devices WHERE primary_ip = 'logical:y'")) {
                rs.next();
                assertThat(rs.getLong(1)).as("o contador de ids não voltou atrás").isEqualTo(8);
            }
            assertThatThrownBy(() -> st.execute("INSERT INTO devices(name, primary_ip, device_type, created_at, updated_at) "
                    + "VALUES ('x', '10.0.0.8', 'ESTRANHO', 1, 1)")).as("o CHECK continua valendo").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute("INSERT INTO devices(name, primary_ip, device_type, created_at, updated_at) "
                    + "VALUES ('dup', '10.0.0.1', 'SWITCH', 1, 1)")).as("primary_ip segue único").isInstanceOf(SQLException.class);

            // as chaves estrangeiras seguem ligadas e a cascata continua funcionando no dispositivo novo
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                rs.next();
                assertThat(rs.getInt(1)).as("foreign_keys religado").isEqualTo(1);
            }
            st.execute("DELETE FROM devices WHERE id = 1");
            assertThat(count(st, "interfaces")).as("ON DELETE CASCADE segue valendo").isZero();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'devices' "
                    + "AND name LIKE 'ix_devices_%' ORDER BY name")) {
                int n = 0;
                while (rs.next()) {
                    n++;
                }
                assertThat(n).isEqualTo(3);
            }
        }
    }

    private static int count(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void emptyDatabaseMigratesToo(@TempDir Path dir) throws Exception {
        flyway(url(dir), "5").migrate();
        assertThat(Files.exists(dir.resolve("omnidude.db"))).isTrue();
    }
}
