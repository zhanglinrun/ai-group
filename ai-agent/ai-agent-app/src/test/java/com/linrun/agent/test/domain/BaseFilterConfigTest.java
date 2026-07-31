package com.linrun.agent.test.domain;

import com.linrun.agent.trigger.config.BaseFilterConfig;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** Management endpoints must use the same authenticated boundary as Agent administration. */
public class BaseFilterConfigTest {

    @Test
    public void shouldProtectActuatorEndpoints() {
        BaseFilterConfig config = new BaseFilterConfig();
        FilterRegistrationBean<?> registration = config.internalApiTokenFilter("internal-token");

        Assert.assertTrue(registration.getUrlPatterns().contains("/actuator/*"));
        Assert.assertTrue(config.adminOperationAuditFilter().getUrlPatterns().contains("/actuator/*"));
    }
}
