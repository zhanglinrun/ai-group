package com.aigroup.paymall.test.config;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class IdentifierWidthSqlContractTest {

    @Test
    public void paySchemaAndMigrationAllowUuidOrderIds() throws Exception {
        Path root = locateRepositoryRoot();
        String baseline = Files.readString(root.resolve("pay-service/docs/dev-ops/mysql/sql/pay-service.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(root.resolve("pay-service/docs/dev-ops/mysql/sql/V7-order-identifier-width.sql"), StandardCharsets.UTF_8);
        assertTrue(baseline.contains("`order_id` varchar(64)"));
        assertTrue(migration.contains("MODIFY COLUMN `order_id` VARCHAR(64)"));
    }

    private Path locateRepositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null && !Files.isDirectory(cursor.resolve("pay-service"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("repository root not found");
        }
        return cursor;
    }
}
