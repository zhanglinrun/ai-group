package com.linrun.agent.test.domain;

import com.linrun.agent.config.DataSourceConfig;
import com.linrun.agent.config.PostgresDataSourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.support.TestPropertySourceUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertSame;

class DataSourceJdbcTemplateBindingTest {

    @Test
    void bindsEachJdbcTemplateToItsNamedDataSource() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.datasource.mysql.driver-class-name=com.mysql.cj.jdbc.Driver",
                    "spring.datasource.mysql.url=jdbc:mysql://127.0.0.1:13306/agent_db",
                    "spring.datasource.mysql.username=root",
                    "spring.datasource.postgres.url=jdbc:postgresql://127.0.0.1:15432/agent_memory",
                    "spring.datasource.postgres.username=agent");
            context.register(DataSourceConfig.class, PostgresDataSourceConfig.class);
            context.refresh();

            assertSame(context.getBean("mysqlDataSource", DataSource.class),
                    context.getBean("mysqlJdbcTemplate", JdbcTemplate.class).getDataSource());
            assertSame(context.getBean("postgresDataSource", DataSource.class),
                    context.getBean("pgJdbcTemplate", JdbcTemplate.class).getDataSource());
        }
    }
}
