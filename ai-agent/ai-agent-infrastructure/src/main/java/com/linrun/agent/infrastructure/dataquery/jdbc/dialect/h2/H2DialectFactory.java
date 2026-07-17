package com.linrun.agent.infrastructure.dataquery.jdbc.dialect.h2;

import com.google.auto.service.AutoService;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.DialectEnum;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.JdbcDialect;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.JdbcDialectFactory;


@AutoService(JdbcDialectFactory.class)
public class H2DialectFactory implements JdbcDialectFactory {
    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith(DialectEnum.H2.getUrlPrefix());
    }

    @Override
    public JdbcDialect create() {
        return new H2Dialect();
    }
}

