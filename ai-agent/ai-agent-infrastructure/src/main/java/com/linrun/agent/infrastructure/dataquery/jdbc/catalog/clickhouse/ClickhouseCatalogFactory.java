package com.linrun.agent.infrastructure.dataquery.jdbc.catalog.clickhouse;

import com.google.auto.service.AutoService;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalog;
import com.linrun.agent.infrastructure.dataquery.jdbc.catalog.JdbcCatalogFactory;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.DialectEnum;


@AutoService(JdbcCatalogFactory.class)
public class ClickhouseCatalogFactory implements JdbcCatalogFactory {
    @Override
    public DialectEnum jdbcDialect() {
        return DialectEnum.CLICKHOUSE;
    }

    @Override
    public JdbcCatalog createCatalog() {
        return new ClickhouseCatalog();
    }
}

