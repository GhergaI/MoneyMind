package dev.igherga.moneymind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MoneyMindApplication {

    public static void main(String[] args) {
        ensureDataDirectoryExists();
        SpringApplication.run(MoneyMindApplication.class, args);
    }

    /**
     * SQLite creates a missing database <em>file</em> but not a missing parent
     * <em>directory</em>. This has to happen before Spring builds the DataSource
     * for Flyway, so it runs in main() rather than in a bean.
     */
    private static void ensureDataDirectoryExists() {
        Path dir = Paths.get(System.getProperty("user.home"), ".moneymind");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create data directory " + dir, e);
        }
    }
}
