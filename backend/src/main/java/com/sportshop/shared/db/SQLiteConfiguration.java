package com.sportshop.shared.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SQLiteConfiguration {

    @Bean
    DataSource dataSource(@Value("${spring.datasource.url}") String jdbcUrl) {
        createDatabaseDirectory(jdbcUrl);
        HikariConfig configuration = new HikariConfig();
        configuration.setDriverClassName("org.sqlite.JDBC");
        configuration.setJdbcUrl(jdbcUrl);
        configuration.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000");
        return new HikariDataSource(configuration);
    }

    private void createDatabaseDirectory(String jdbcUrl) {
        String databasePath = jdbcUrl.substring("jdbc:sqlite:".length());
        if (databasePath.equals(":memory:") || databasePath.startsWith("file:")) {
            return;
        }
        try {
            Files.createDirectories(Path.of(databasePath).toAbsolutePath().getParent());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not create SQLite database directory", exception);
        }
    }
}
