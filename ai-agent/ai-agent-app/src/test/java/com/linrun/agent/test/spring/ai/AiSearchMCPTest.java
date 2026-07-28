package com.linrun.agent.test.spring.ai;

import com.linrun.agent.types.common.JsonUtils;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.Duration;


//全通过测试
/**
 * mcp；https://sai.baidu.com/zh/
 * 百度搜索；<a href="https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388">https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388</a>
 * key申请；<a href="https://console.bce.baidu.com/iam/?_=1753597622044#/iam/apikey/list">apikey</a>
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class AiSearchMCPTest {

    /** DashScope API Key 外置到环境变量，未配置时跳过本集成测试 */
    private static final String DASHSCOPE_API_KEY = System.getenv("DASHSCOPE_API_KEY");

    @Test
    public void test() {
        Assume.assumeTrue("no api key configured, skip integration test", StringUtils.isNotBlank(DASHSCOPE_API_KEY));
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                        .apiKey(DASHSCOPE_API_KEY)
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-v3.2")
                        .toolCallbacks(SyncMcpToolCallbackProvider.builder().mcpClients(sseMcpClient()).build().getToolCallbacks())
                        .build())
                .build();

        ChatResponse call = chatModel.call(Prompt.builder().messages(new UserMessage("搜索小傅哥技术博客有哪些项目")).build());
        log.info("测试结果:{}", JsonUtils.toJson(call.getResult()));
    }

    public McpSyncClient sseMcpClient() {
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com/v2/ai_search/mcp/")
                .sseEndpoint("sse?api_key=Bearer+" + System.getenv("BAIDU_SEARCH_API_KEY"))
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(360)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

}
