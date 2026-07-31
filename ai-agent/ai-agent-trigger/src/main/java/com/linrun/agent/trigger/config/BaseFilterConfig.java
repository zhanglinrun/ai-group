package com.linrun.agent.trigger.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import com.linrun.agent.trigger.http.auth.GatewayUserContextFilter;
import com.linrun.agent.trigger.http.auth.InternalApiTokenFilter;
import com.linrun.agent.trigger.http.auth.AdminOperationAuditFilter;
import com.linrun.agent.types.agent.config.AgentExecutorProperties;

/**
 * @author bjwangjuntao
 */
@Configuration
@EnableConfigurationProperties(AgentExecutorProperties.class)
public class BaseFilterConfig {
	private static final String[] MANAGEMENT_PATHS = {
			"/api/v1/admin/*", "/data/*", "/armory_agent", "/armory_api", "/query_available_agents", "/actuator/*"
	};

	public BaseFilterConfig() {
	}

	@Bean
	@ConditionalOnProperty(prefix = "autobots.execution.cors", name = "allowed-origins[0]")
	public FilterRegistrationBean<CorsFilter> corsFilter(AgentExecutorProperties properties) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowCredentials(true);
		config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
		config.addAllowedHeader("*");
		config.addAllowedMethod("*");
		source.registerCorsConfiguration("/**", config);
		CorsFilter corsFilter = new CorsFilter(source);
		return this.creatAllFilter(corsFilter, 1);
	}

	@Bean
	public FilterRegistrationBean<GatewayUserContextFilter> gatewayUserContextFilter(
			@Value("${ai-group.internal.token:}") String token) {
		return this.creatAllFilter(new GatewayUserContextFilter(token), 2);
	}

	/**
	 * 管理接口、数据接口与装配触发接口（armory/query_available_agents）不经 Gateway 路由，
	 * 直连即可访问，这里用内部令牌收口。
	 * 令牌缺失或校验失败都返回 403；本地开发使用 dev profile 中的显式本地令牌。
	 */
	@Bean
	public FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilter(
			@Value("${ai-group.internal.token:}") String token) {
		return this.createFilter(new InternalApiTokenFilter(token), 0, MANAGEMENT_PATHS);
	}

	@Bean
	public FilterRegistrationBean<AdminOperationAuditFilter> adminOperationAuditFilter() {
		return this.createFilter(new AdminOperationAuditFilter(), 3, MANAGEMENT_PATHS);
	}

	<T extends Filter> FilterRegistrationBean<T> creatAllFilter(T filter, int order) {
		return this.createFilter(filter, order, "/*");
	}

	<T extends Filter> FilterRegistrationBean<T> createFilter(T filter, int order, String... urlPatterns) {
		FilterRegistrationBean<T> bean = new FilterRegistrationBean<>();
		bean.setFilter(filter);
		bean.setOrder(order);
		bean.addUrlPatterns(urlPatterns);
		bean.setDispatcherTypes(DispatcherType.REQUEST, new DispatcherType[0]);
		return bean;
	}
}
