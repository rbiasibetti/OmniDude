package com.omnidude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Garante que as restrições do schema valem na conexão real do app (FKs ligadas, WAL, CHECKs). */
class SqliteSchemaTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private long device(String ip) {
        jdbc.update("INSERT INTO devices(name, primary_ip, created_at, updated_at) VALUES (?,?,0,0)", ip, ip);
        return jdbc.queryForObject("SELECT id FROM devices WHERE primary_ip = ?", Long.class, ip);
    }

    @Test
    void connectionPragmasAreApplied() {
        assertThat(jdbc.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualToIgnoringCase("wal");
    }

    @Test
    void flywayCreatedDefaultMapAndAllTables() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM maps WHERE name = 'Principal'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name <> 'flyway_schema_history'", Integer.class)).isEqualTo(23);
    }

    @Test
    void checkConstraintsRejectInvalidEnums() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO devices(name, primary_ip, status, created_at, updated_at) VALUES ('x','198.51.100.1','FOO',0,0)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void linksAreCanonicalAndUnique() {
        long a = device("198.51.100.10");
        long b = device("198.51.100.11");
        jdbc.update("INSERT INTO links(source_device_id, target_device_id, created_at) VALUES (?,?,0)", a, b);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO links(source_device_id, target_device_id, created_at) VALUES (?,?,0)", a, b))
                .isInstanceOf(DataIntegrityViolationException.class);          // duplicata (índice com COALESCE)
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO links(source_device_id, target_device_id, created_at) VALUES (?,?,0)", b, a))
                .isInstanceOf(DataIntegrityViolationException.class);          // ordem não canônica
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO links(source_device_id, target_device_id, created_at) VALUES (?,?,0)", a, a))
                .isInstanceOf(DataIntegrityViolationException.class);          // auto-enlace
    }

    @Test
    void deletingInterfacesDoesNotCollideLinks() {
        long a = device("198.51.100.20");
        long b = device("198.51.100.21");
        jdbc.update("INSERT INTO interfaces(device_id, if_index, name) VALUES (?,1,'e1'),(?,2,'e2'),(?,1,'e1')", a, a, b);
        long i1 = jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id=? AND if_index=1", Long.class, a);
        long i2 = jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id=? AND if_index=2", Long.class, a);
        long ib = jdbc.queryForObject("SELECT id FROM interfaces WHERE device_id=? AND if_index=1", Long.class, b);
        jdbc.update("INSERT INTO links(source_device_id,target_device_id,source_interface_id,target_interface_id,created_at)"
                + " VALUES (?,?,?,?,0),(?,?,?,?,0)", a, b, i1, ib, a, b, i2, ib);
        jdbc.update("DELETE FROM interfaces WHERE device_id = ?", a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM links WHERE source_device_id = ?", Integer.class, a)).isZero();
    }

    @Test
    void deletingDeviceCascades() {
        long d = device("198.51.100.30");
        jdbc.update("INSERT INTO service_checks(device_id, type, name) VALUES (?,'ICMP','Ping')", d);
        long c = jdbc.queryForObject("SELECT id FROM service_checks WHERE device_id=?", Long.class, d);
        jdbc.update("INSERT INTO check_results VALUES (?,1,1,5)", c);
        jdbc.update("INSERT INTO events(ts, severity, type, device_id, message) VALUES (1,'INFO','SYSTEM',?,'m')", d);
        jdbc.update("DELETE FROM devices WHERE id = ?", d);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_checks WHERE device_id=?", Integer.class, d)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_results WHERE check_id=?", Integer.class, c)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM events WHERE message='m' AND device_id IS NULL", Integer.class))
                .isGreaterThanOrEqualTo(1);                                    // evento sobrevive, sem dispositivo
    }
}
