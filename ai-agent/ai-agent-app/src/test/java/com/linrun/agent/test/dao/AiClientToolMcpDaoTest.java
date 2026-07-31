package com.linrun.agent.test.dao;

import com.linrun.agent.infrastructure.dao.IAiClientToolMcpDao;
import com.linrun.agent.infrastructure.dao.po.AiClientToolMcp;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.jdbc.Sql;
import org.mybatis.spring.annotation.MapperScan;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP客户端配置表 DAO 测试
 * @author linrun
 * @description MCP客户端配置表数据访问对象测试
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(
        classes = AiClientToolMcpDaoTest.McpDaoTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:/mybatis/mapper/*.xml",
        "mybatis-plus.mapper-locations=classpath*:/mybatis/mapper/*.xml"
})
@Sql(
        statements = {
                "DROP TABLE IF EXISTS ai_client_tool_mcp",
                "CREATE TABLE ai_client_tool_mcp ("
                        + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "mcp_id VARCHAR(128) NOT NULL UNIQUE, "
                        + "mcp_name VARCHAR(255) NOT NULL, "
                        + "transport_type VARCHAR(64) NOT NULL, "
                        + "transport_config CLOB NOT NULL, "
                        + "request_timeout INTEGER NOT NULL, "
                        + "protocol_version VARCHAR(64), "
                        + "oauth_audience VARCHAR(255), "
                        + "oauth_scopes_json CLOB, "
                        + "allowed_domains_json CLOB, "
                        + "tool_allowlist_json CLOB, "
                        + "credential_ref VARCHAR(255), "
                        + "version VARCHAR(64), "
                        + "config_hash VARCHAR(128), "
                        + "status INTEGER NOT NULL, "
                        + "create_time TIMESTAMP NOT NULL, "
                        + "update_time TIMESTAMP NOT NULL)"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
public class AiClientToolMcpDaoTest {

    @Resource
    private IAiClientToolMcpDao aiClientToolMcpDao;

    /**
     * 生成唯一 MCP 标识，避免测试重复运行时命中唯一索引。
     */
    private String nextMcpId(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    @Test
    public void test_insert() {
        AiClientToolMcp aiClientToolMcp = AiClientToolMcp.builder()
                .mcpId(nextMcpId("test_5006"))
                .mcpName("测试MCP工具")
                .transportType("sse")
                .transportConfig("{\"baseUri\":\"http://localhost:8080\",\"sseEndpoint\":\"/sse\"}")
                .requestTimeout(180)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientToolMcpDao.insert(aiClientToolMcp);
        log.info("插入结果: {}, 生成ID: {}", result, aiClientToolMcp.getId());
    }

    @Test
    public void test_insert_streamableHttp() {
        AiClientToolMcp aiClientToolMcp = AiClientToolMcp.builder()
                .mcpId(nextMcpId("test_streamable_5007"))
                .mcpName("测试StreamableHttp工具")
                .transportType("streamable_http")
                .transportConfig("{\"baseUri\":\"http://127.0.0.1:8101\",\"endpoint\":\"/mcp\",\"headers\":{\"Authorization\":\"Bearer test-token\"},\"resumableStreams\":false,\"openConnectionOnStartup\":true}")
                .requestTimeout(180)
                .status(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientToolMcpDao.insert(aiClientToolMcp);
        log.info("插入Streamable HTTP结果: {}, 生成ID: {}", result, aiClientToolMcp.getId());
    }

    @Test
    public void test_updateById() {
        AiClientToolMcp aiClientToolMcp = AiClientToolMcp.builder()
                .id(1L)
                .mcpId("test_5006")
                .mcpName("更新后的测试MCP工具")
                .transportType("stdio")
                .transportConfig("{\"command\":\"npx\",\"args\":[\"-y\",\"test-mcp\"]}")
                .requestTimeout(300)
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientToolMcpDao.updateById(aiClientToolMcp);
        log.info("更新结果: {}", result);
    }

    @Test
    public void test_updateByMcpId() {
        AiClientToolMcp aiClientToolMcp = AiClientToolMcp.builder()
                .mcpId("5001")
                .mcpName("根据MCP ID更新的工具")
                .transportType("sse")
                .transportConfig("{\"baseUri\":\"http://updated.example.com\",\"sseEndpoint\":\"/sse\"}")
                .requestTimeout(240)
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientToolMcpDao.updateByMcpId(aiClientToolMcp);
        log.info("根据MCP ID更新结果: {}", result);
    }

    @Test
    public void test_updateByMcpId_streamableHttp() {
        AiClientToolMcp aiClientToolMcp = AiClientToolMcp.builder()
                .mcpId("test_streamable_5007")
                .mcpName("根据MCP ID更新的StreamableHttp工具")
                .transportType("streamable_http")
                .transportConfig("{\"baseUri\":\"http://updated.example.com\",\"endpoint\":\"/mcp\",\"headers\":{\"Authorization\":\"Bearer updated-token\"},\"resumableStreams\":true,\"openConnectionOnStartup\":true}")
                .requestTimeout(240)
                .status(1)
                .updateTime(LocalDateTime.now())
                .build();

        int result = aiClientToolMcpDao.updateByMcpId(aiClientToolMcp);
        log.info("根据MCP ID更新Streamable HTTP结果: {}", result);
    }

    @Test
    public void test_deleteById() {
        int result = aiClientToolMcpDao.deleteById(1L);
        log.info("删除结果: {}", result);
    }

    @Test
    public void test_deleteByMcpId() {
        int result = aiClientToolMcpDao.deleteByMcpId("test_5006");
        log.info("根据MCP ID删除结果: {}", result);
    }

    @Test
    public void test_queryById() {
        AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryById(6L);
        log.info("根据ID查询结果: {}", aiClientToolMcp);
    }

    @Test
    public void test_queryByMcpId() {
        AiClientToolMcp aiClientToolMcp = aiClientToolMcpDao.queryByMcpId("5001");
        log.info("根据MCP ID查询结果: {}", aiClientToolMcp);
    }

    @Test
    public void test_queryAll() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryAll();
        log.info("查询所有MCP工具配置数量: {}", aiClientToolMcpList.size());
        aiClientToolMcpList.forEach(mcp -> log.info("MCP工具配置: {}", mcp));
    }

    @Test
    public void test_queryByStatus() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryByStatus(1);
        log.info("根据状态查询结果数量: {}", aiClientToolMcpList.size());
        aiClientToolMcpList.forEach(mcp -> log.info("启用的MCP工具配置: {}", mcp));
    }

    @Test
    public void test_queryByTransportType() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryByTransportType("sse");
        log.info("根据传输类型查询结果数量: {}", aiClientToolMcpList.size());
        aiClientToolMcpList.forEach(mcp -> log.info("SSE类型MCP工具配置: {}", mcp));
    }

    @Test
    public void test_queryByTransportType_streamableHttp() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryByTransportType("streamable_http");
        log.info("根据Streamable HTTP传输类型查询结果数量: {}", aiClientToolMcpList.size());
        aiClientToolMcpList.forEach(mcp -> log.info("Streamable HTTP类型MCP工具配置: {}", mcp));
    }

    @Test
    public void test_queryEnabledMcps() {
        List<AiClientToolMcp> aiClientToolMcpList = aiClientToolMcpDao.queryEnabledMcps();
        log.info("查询启用的MCP工具配置数量: {}", aiClientToolMcpList.size());
        aiClientToolMcpList.forEach(mcp -> log.info("启用的MCP工具配置: {}", mcp));
    }

    /**
     * The legacy DAO tests historically depended on a developer MySQL instance.
     * Keep them runnable in the P60 contract suite with an isolated H2/MyBatis
     * slice, rather than starting the full Agent runtime or mutating a local DB.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("com.linrun.agent.infrastructure.dao")
    static class McpDaoTestConfiguration {
    }

}
