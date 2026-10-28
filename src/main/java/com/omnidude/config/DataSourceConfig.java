package com.omnidude.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite em modo WAL. Os PRAGMAs são aplicados em cada conexão via parâmetros da URL (ADR-1):
 * FKs ligadas, busy_timeout e transações IMMEDIATE, que evitam SQLITE_BUSY_SNAPSHOT ao promover
 * uma leitura a escrita dentro da mesma transação.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(OmniDudeProperties props) throws IOException {
        Path dir = Path.of(props.dataDir()).toAbsolutePath();
        Files.createDirectories(dir);
        FilePermissions.restrict(dir);                  // banco, chave e backups: só o dono
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dir.resolve("omnidude.db")
                + "?foreign_keys=true&journal_mode=WAL&synchronous=NORMAL"
                + "&busy_timeout=10000&transaction_mode=IMMEDIATE");
        cfg.setPoolName("omnidude-sqlite");
        cfg.setMaximumPoolSize(4);
        return new HikariDataSource(cfg);
    }
}
