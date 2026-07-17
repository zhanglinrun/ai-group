package com.linrun.agent.infrastructure.dataquery.jdbc.dialect.mysql;


import com.google.auto.service.AutoService;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.DialectEnum;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.JdbcDialect;
import com.linrun.agent.infrastructure.dataquery.jdbc.dialect.JdbcDialectFactory;

@AutoService(JdbcDialectFactory.class)
public class MySqlDialectFactory implements JdbcDialectFactory {
    @Override
    public boolean acceptsURL(String url) {
        return url.startsWith(DialectEnum.MYSQL.getUrlPrefix());
    }

    @Override
    public JdbcDialect create() {
        return new MysqlDialect();
    }
}

