package com.aigroup.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityValidatorTest {

    @Test
    void localProfilesMayUseExplicitLocalConfiguration() {
        ProductionSecurityValidator validator = validator("dev", "short", "short");
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void mixedLocalAndProductionProfilesMustNotBypassValidation() {
        ProductionSecurityValidator validator = validator(new String[]{"dev", "prod"}, "short", "short");
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void nonLocalProfilesRequireStrongSecrets() {
        ProductionSecurityValidator validator = validator(
                "prod",
                "0123456789abcdef0123456789abcdef",
                "abcdef0123456789abcdef0123456789"
        );
        assertDoesNotThrow(validator::validate);
    }

    private ProductionSecurityValidator validator(String profile, String jwtSecret, String internalToken) {
        return validator(new String[]{profile}, jwtSecret, internalToken);
    }

    private ProductionSecurityValidator validator(String[] profiles, String jwtSecret, String internalToken) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment);
        ReflectionTestUtils.setField(validator, "jwtSecret", jwtSecret);
        ReflectionTestUtils.setField(validator, "internalToken", internalToken);
        return validator;
    }
}
