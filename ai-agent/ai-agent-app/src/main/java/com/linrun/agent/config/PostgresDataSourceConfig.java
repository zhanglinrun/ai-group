package com.linrun.agent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * PostgreSQL + pgvector 数据源：承载 AI 记忆与向量检索。
 *
 * <p>与 {@link DataSourceConfig} 的 MySQL 主库职责分离：
 * <ul>
 *   <li>MySQL —— 业务事务（额度账本、对话账本、拼团/支付集成）</li>
 *   <li>PostgreSQL + pgvector —— AI 语义记忆、向量召回、文档 RAG</li>
 * </ul>
 * 两库互不争抢 @Primary；MySQL 仍是事务主库，PostgreSQL 只服务 AI 子系统。
 *
 * <p>仅在显式配置 postgres 数据源时装配，test profile 与无库探针场景不会因缺失占位符而失败。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class PostgresDataSourceConfig {

    @Bean("postgresDataSource")
    public DataSource postgresDataSource(
            @Value("${spring.datasource.postgres.driver-class-name:org.postgresql.Driver}") String driverClassName,
            @Value("${spring.datasource.postgres.url}") String url,
            @Value("${spring.datasource.postgres.username}") String username,
            @Value("${spring.datasource.postgres.password:}") String password,
            @Value("${spring.datasource.postgres.hikari.maximum-pool-size:10}") int maximumPoolSize,
            @Value("${spring.datasource.postgres.hikari.minimum-idle:2}") int minimumIdle,
            @Value("${spring.datasource.postgres.hikari.idle-timeout:30000}") long idleTimeout,
            @Value("${spring.datasource.postgres.hikari.connection-timeout:30000}") long connectionTimeout,
            @Value("${spring.datasource.postgres.hikari.max-lifetime:1800000}") long maxLifetime) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(maximumPoolSize);
        dataSource.setMinimumIdle(minimumIdle);
        dataSource.setIdleTimeout(idleTimeout);
        dataSource.setConnectionTimeout(connectionTimeout);
        dataSource.setMaxLifetime(maxLifetime);
        dataSource.setPoolName("PostgresHikariPool");
        return dataSource;
    }

    @Bean("pgJdbcTemplate")
    public JdbcTemplate pgJdbcTemplate(@Qualifier("postgresDataSource") DataSource postgresDataSource) {
        JdbcTemplate template = new JdbcTemplate(postgresDataSource);
        template.setFetchSize(200);
        return template;
    }

    @Bean("postgresTransactionManager")
    public PlatformTransactionManager postgresTransactionManager(
            @Qualifier("postgresDataSource") DataSource postgresDataSource) {
        return new DataSourceTransactionManager(postgresDataSource);
    }
}
