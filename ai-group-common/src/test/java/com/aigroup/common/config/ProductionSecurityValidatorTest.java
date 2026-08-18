package com.aigroup.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSecurityValidatorTest {

    @Test
    void localProfilesMayUseExplicitLocalConfiguration() {
        assertDoesNotThrow(validator("dev", "short")::validate);
    }

    @Test
    void mixedLocalAndProductionProfilesMustNotBypassValidation() {
        assertThrows(IllegalStateException.class,
                validator(new String[]{"dev", "prod"}, "short")::validate);
    }

    @Test
    void nonLocalProfilesRejectBlankInternalToken() {
        assertThrows(IllegalStateException.class, validator("prod", "")::validate);
    }

    @Test
    void nonLocalProfilesRejectBlankIdentitySigningSecret() {
        ProductionSecurityValidator validator = validator(
                "prod",
                "abcdef0123456789abcdef0123456789"
        );
        ReflectionTestUtils.setField(validator, "identitySigningSecret", "");
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void nonLocalProfilesAllowStrongInternalToken() {
        assertDoesNotThrow(validator(
                "prod",
                "abcdef0123456789abcdef0123456789"
        )::validate);
    }

    @Test
    void nonLocalProfilesRejectShortIdentitySigningSecret() {
        ProductionSecurityValidator validator = validator("prod", "abcdef0123456789abcdef0123456789");
        ReflectionTestUtils.setField(validator, "identitySigningSecret", "short");
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void nonLocalProfilesRejectShortInternalToken() {
        ProductionSecurityValidator validator = validator("prod", "short");
        assertThrows(IllegalStateException.class, validator::validate);
    }

    private ProductionSecurityValidator validator(String profile, String internalToken) {
        return validator(new String[]{profile}, internalToken);
    }

    private ProductionSecurityValidator validator(String[] profiles, String internalToken) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ProductionSecurityValidator validator = new ProductionSecurityValidator(environment);
        ReflectionTestUtils.setField(validator, "internalToken", internalToken);
        ReflectionTestUtils.setField(validator, "identitySigningSecret", "abcdef0123456789abcdef0123456789");
        return validator;
    }
}
