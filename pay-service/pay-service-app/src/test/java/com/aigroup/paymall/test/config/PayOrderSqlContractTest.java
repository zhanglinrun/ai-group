package com.aigroup.paymall.test.config;

import org.junit.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PayOrderSqlContractTest {

    @Test
    public void mapperPersistsIdempotencyRouteAndUsesDescendingKeyset() throws IOException {
        String mapper = normalized(new String(new ClassPathResource(
                "mybatis/mapper/pay_order_mapper.xml").getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        assertTrue(mapper.contains("client_request_id, request_fingerprint, create_stage, create_owner_token, create_lease_until"));
        assertTrue(mapper.contains("group_activity_id, group_team_id"));
        assertTrue(mapper.contains("where user_id = #{userId} and client_request_id = #{clientRequestId}"));
        assertTrue(mapper.contains("create_stage = 'PROVIDER_STARTED'"));
        assertTrue(mapper.contains("create_stage = 'MANUAL_REVIEW'"));
        assertTrue(mapper.contains("and id &lt; #{lastId}"));
        assertTrue(mapper.contains("order by id desc"));
    }

    @Test
    public void baselineMigrationAndFullStackBootstrapCarryV6Contract() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String baseline = normalized(Files.readString(repositoryRoot.resolve(
                "pay-service/docs/dev-ops/mysql/sql/pay-service.sql"), StandardCharsets.UTF_8));
        String migration = normalized(Files.readString(repositoryRoot.resolve(
                "pay-service/docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql"), StandardCharsets.UTF_8));
        String bootstrap = normalized(Files.readString(repositoryRoot.resolve(
                "dev-ops/compose/docker-compose.full.yml"), StandardCharsets.UTF_8));

        assertTrue(baseline.contains("`client_request_id` varchar(64)"));
        assertTrue(baseline.contains("`request_fingerprint` char(64)"));
        assertTrue(baseline.contains("`create_stage` varchar(32)"));
        assertTrue(baseline.contains("`create_owner_token` varchar(64)"));
        assertTrue(baseline.contains("`create_lease_until` datetime"));
        assertTrue(baseline.contains("`group_activity_id` bigint"));
        assertTrue(baseline.contains("`group_team_id` varchar(64)"));
        assertTrue(baseline.contains("UNIQUE KEY `uq_user_client_request` (`user_id`,`client_request_id`)"));

        assertTrue(migration.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(migration.contains("INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(migration.contains("ADD UNIQUE KEY `uq_user_client_request` (`user_id`, `client_request_id`)"));
        assertTrue(bootstrap.contains("pay-service/docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql"));
        assertTrue(bootstrap.contains("pay-service"));
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pay-service"))
                    && Files.exists(current.resolve("dev-ops/compose/docker-compose.full.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ");
    }
}
