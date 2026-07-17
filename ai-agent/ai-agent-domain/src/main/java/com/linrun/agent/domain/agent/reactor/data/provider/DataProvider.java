package com.linrun.agent.domain.agent.reactor.data.provider;


import com.linrun.agent.domain.agent.reactor.data.QueryResult;


public interface DataProvider<T extends DataQueryRequest> {

    QueryResult queryData(T request) throws Exception;

    boolean queryForTest(T request);
}
