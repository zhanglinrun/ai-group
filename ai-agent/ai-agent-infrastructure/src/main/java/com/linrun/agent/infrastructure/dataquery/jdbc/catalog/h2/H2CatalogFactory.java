package com.linrun.agent.infrastructure.dataquery.jdbc.catalog.h2;

import com.google.auto.service.AutoService;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalog;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalogFactory;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.DialectEnum;


@AutoService(JdbcCatalogFactory.class)
public class H2CatalogFactory implements JdbcCatalogFactory {
    @Override
    public DialectEnum jdbcDialect() {
        return DialectEnum.H2;
    }

    @Override
    public JdbcCatalog createCatalog() {
        return new H2SqlCatalog();
    }
}

