package com.linrun.agent.infrastructure.dataquery.provider.jdbc;


import lombok.Data;
import com.linrun.agent.infrastructure.dataquery.jdbc.JdbcConnectionConfig;
import com.linrun.agent.domain.agent.reactor.data.provider.DataQueryRequest;

@Data
public class JdbcQueryRequest implements DataQueryRequest {

    private JdbcConnectionConfig jdbcConnectionConfig;
    private String sql;
    private int limit;

    private int pageIndex;
    private int pageSize;
}

