package com.omnidude.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Acrescenta os tipos de dispositivo CLOUD (nuvem) e NETWORK (rede) ao CHECK de {@code devices.device_type}.
 *
 * <p>O SQLite não altera um CHECK: a tabela precisa ser recriada, e é uma migração em Java porque o procedimento
 * recomendado exige {@code PRAGMA foreign_keys = OFF}, que não tem efeito dentro de uma transação. Com as chaves
 * estrangeiras ligadas, o {@code DROP TABLE} apagaria em cascata tudo que depende de {@code devices} (interfaces,
 * checagens, enlaces, posições no mapa). Por isso a migração roda fora da transação do Flyway, abre a sua, confere
 * {@code foreign_key_check} antes de confirmar e religa as chaves ao final, qualquer que seja o resultado.
 */
@Component
public class V5__DeviceTypes extends BaseJavaMigration {

    private static final String COLUMNS = "id, name, hostname, primary_ip, mac_address, device_type, os_info, sys_descr, "
            + "sys_object_id, last_boot_at, status, status_since, snmp_profile_id, enabled, origin, notes, created_at, "
            + "updated_at, virtual";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            c.setAutoCommit(false);
            try {
                st.execute("CREATE TABLE devices_new ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "name TEXT NOT NULL, "
                        + "hostname TEXT, "
                        + "primary_ip TEXT NOT NULL UNIQUE, "
                        + "mac_address TEXT, "
                        + "device_type TEXT NOT NULL DEFAULT 'OTHER' CHECK (device_type IN ('ROUTER','SWITCH','FIREWALL',"
                        + "'ACCESS_POINT','SERVER','WORKSTATION','PRINTER','STORAGE','CLOUD','NETWORK','OTHER')), "
                        + "os_info TEXT, "
                        + "sys_descr TEXT, "
                        + "sys_object_id TEXT, "
                        + "last_boot_at INTEGER, "
                        + "status TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (status IN ('UNKNOWN','UP','WARNING','DOWN')), "
                        + "status_since INTEGER, "
                        + "snmp_profile_id INTEGER REFERENCES snmp_profiles(id) ON DELETE SET NULL, "
                        + "enabled INTEGER NOT NULL DEFAULT 1, "
                        + "origin TEXT NOT NULL DEFAULT 'MANUAL' CHECK (origin IN ('MANUAL','DISCOVERY')), "
                        + "notes TEXT, "
                        + "created_at INTEGER NOT NULL, "
                        + "updated_at INTEGER NOT NULL, "
                        + "virtual INTEGER NOT NULL DEFAULT 0)");
                st.execute("INSERT INTO devices_new(" + COLUMNS + ") SELECT " + COLUMNS + " FROM devices");
                // o próximo id não pode voltar atrás, mesmo que as últimas linhas tenham sido apagadas
                st.execute("UPDATE sqlite_sequence SET seq = (SELECT seq FROM sqlite_sequence WHERE name = 'devices') "
                        + "WHERE name = 'devices_new' AND EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'devices')");
                st.execute("DROP TABLE devices");
                st.execute("ALTER TABLE devices_new RENAME TO devices");
                st.execute("CREATE INDEX ix_devices_status ON devices(status)");
                st.execute("CREATE INDEX ix_devices_snmp_profile ON devices(snmp_profile_id)");
                st.execute("CREATE INDEX ix_devices_mac ON devices(mac_address)");
                try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) {
                        throw new SQLException("foreign_key_check falhou depois de recriar devices (tabela "
                                + rs.getString(1) + ", linha " + rs.getLong(2) + "); migração desfeita");
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
                st.execute("PRAGMA foreign_keys = ON");
            }
        }
    }
}
