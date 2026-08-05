package com.sportshop.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Configures an isolated, file-backed SQLite database for each integration-test class.
 */
public abstract class DatabaseTestSupport {

    private DatabaseTestSupport() {
    }

    public static void configureDataSource(DynamicPropertyRegistry registry, Class<?> testClass) {
        Path database = Path.of("target", "test-data", testClass.getSimpleName() + ".db")
                .toAbsolutePath();
        try {
            Files.createDirectories(database.getParent());
            Files.deleteIfExists(database);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not prepare SQLite test database: " + database, exception);
        }

        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + database.toString().replace('\\', '/'));
    }
}
