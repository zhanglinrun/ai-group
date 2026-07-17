package com.linrun.agent.infrastructure.dataquery.jdbc.catalog.mysql;

import com.google.auto.service.AutoService;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalog;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalogFactory;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.DialectEnum;

@AutoService(JdbcCatalogFactory.class)
public class MySqlCatalogFactory implements JdbcCatalogFactory {
    @Override
    public DialectEnum jdbcDialect() {
        return DialectEnum.MYSQL;
    }

    @Override
    public JdbcCatalog createCatalog() {
        return new MySqlCatalog();
    }
}

