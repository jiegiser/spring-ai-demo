package com.jiegiser.llm.mcp.service;

import com.jiegiser.llm.mcp.callback.ReturnDirectMcpToolCallbackProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 手动管理 MCP 客户端服务（演示 toolFilter 工具过滤功能）
 * <p>
 * 功能说明：
 * 本服务演示如何手动创建 MCP 客户端（区别于 YAML 自动配置），
 * 并使用 toolFilter 过滤工具（只暴露部分工具给大模型）。
 * <p>
 * 与 McpClientService 的区别：
 * - McpClientService：YAML 配置 → Spring AI 自动创建客户端 → 注入使用
 * - ManualMcpClientService：Java 代码手动创建客户端 → 手动配置工具过滤
 * <p>
 * toolFilter 的作用：
 * 当 MCP Server 暴露多个工具时，可能只想让大模型使用其中一部分。
 * 例如：MCP Server 有 [getWeather, queryWeather, goods_getNum, goods_getDesc]
 *       使用 toolFilter 后只暴露 [goods_getNum, goods_getDesc]
 * <p>
 * 适用场景：
 * - 多租户隔离：不同用户只能看到自己的工具
 * - 权限控制：普通用户看不到管理员工具
 * - 功能裁剪：只暴露当前业务需要的工具
 */
@Service
public class ManualMcpClientService {

    /** OpenAI 兼容模型（DashScope 通义千问），用于 ChatClient 构建 */
    @Autowired
    private OpenAiChatModel chatModel;

    /** ChatClient：封装了大模型调用 + MCP 工具注册 */
    private ChatClient chatClient;

    /**
     * 初始化（应用启动时执行一次）
     * <p>
     * 流程：
     * 1. 手动创建 MCP 客户端（Streamable HTTP 模式）
     * 2. 初始化客户端（与 MCP Server 握手）
     * 3. 构建 SyncMcpToolCallbackProvider（带 toolFilter 过滤）
     * 4. 获取过滤后的工具回调数组
     * 5. 构建 ChatClient（注册过滤后的工具）
     */
    @PostConstruct
    public void init() {
        //        // STDIO 模式示例（注释掉的代码）
        //        // 1. 创建服务器参数：启动 java 子进程运行 MCP Server JAR
        //        ServerParameters parameters = ServerParameters.builder("java")
        //                .args("-jar", "D:\\LLMentor\\LLMentor\\mcp\\mcp-server-stdio\\target\\mcp-server-stdio-1.0.0-SNAPSHOT.jar")
        //                .build();
        //        // 2. 创建 STDIO 传输层
        //        stdioTransport = new StdioClientTransport(parameters, McpJsonMapper.createDefault());
        //        // 3. 创建同步客户端
        //        McpSyncClient stdioClient = McpClient.sync(stdioTransport)
        //                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("my‑client", "1.0"))
        //                .requestTimeout(Duration.ofSeconds(10))
        //                .build();
        //        // 4. 握手初始化
        //        stdioClient.initialize();
        //
        //        // SSE 模式示例（注释掉的代码）
        //        // 1. 创建 SSE 传输层：连接 http://127.0.0.1:8003/sse
        //        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://127.0.0.1:8003").sseEndpoint("/sse").build();
        //        // 2. 创建同步客户端
        //        McpSyncClient sseClient = McpClient.sync(transport)
        //                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("sse-client", "1.0"))
        //                .requestTimeout(Duration.ofSeconds(10))
        //                .build();
        //        // 3. 握手初始化
        //        sseClient.initialize();

        // STREAMABLE HTTP 模式（当前使用的）
        // 1. 创建 Streamable HTTP 传输层：连接 http://127.0.0.1:8004/stream/test/api/mcp
        HttpClientStreamableHttpTransport streamableTransport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:8004/stream/test/").endpoint("api/mcp").build();
        // 2. 创建同步客户端：客户端标识 "streamable-client" + 10 秒超时
        McpSyncClient streamableClient = McpClient.sync(streamableTransport).clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("streamable-client", "1.0")).requestTimeout(Duration.ofSeconds(10)).build();
        // 3. 握手初始化（与 MCP Server 交换能力信息）
        streamableClient.initialize();

        // 4. 打包成客户端列表（这里只有一个客户端）
        List<McpSyncClient> clients = List.of(streamableClient);

        // 5. 构建工具回调提供者（带 toolFilter 过滤）
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider
                .builder()
                .mcpClients(clients)  // 注册客户端列表
                // 过滤工具：只保留名称以 "goods" 开头的工具
                // 例如：MCP Server 有 [getWeather, queryWeather, goods_getNum, goods_getDesc]
                //       过滤后只暴露 [goods_getNum, goods_getDesc]
                // 过滤逻辑：(conn, tool) -> boolean
                //   - conn：MCP 连接对象（可用于区分不同 Server）
                //   - tool：工具定义对象（包含 name、description、parameters）
                //   - 返回 true：保留该工具
                //   - 返回 false：过滤掉该工具
                .toolFilter((conn, tool) -> tool.name().startsWith("goods"))
                // 添加 MCP 客户端
                .build();

        // 6. 获取过滤后的工具回调数组
        //    内部流程：
        //    1. 遍历所有客户端
        //    2. 调用 client.listTools() 获取工具列表
        //    3. 对每个工具应用 toolFilter
        //    4. 只保留返回 true 的工具
        //    5. 创建 ToolCallback 并返回
        ToolCallback[] callbacks = provider.getToolCallbacks();

        // 使用自定义的 ReturnDirectMcpToolCallbackProvider，实现直接返回 MCP 工具回调
        // ReturnDirectMcpToolCallbackProvider returnDirectMcpToolCallbackProvider = new ReturnDirectMcpToolCallbackProvider(clients, true);
        // ToolCallback[] callbacks = returnDirectMcpToolCallbackProvider.getToolCallbacks();

        // 7. 构建 ChatClient：注册过滤后的工具
        //    大模型只能看到 goods_getNum 和 goods_getDesc 两个工具
        this.chatClient = ChatClient.builder(chatModel).defaultToolCallbacks(callbacks).build();
    }

    /**
     * 智能体调用（使用过滤后的工具）
     * <p>
     * 流程：
     * 1. 用户提问（如"商品 iPhone 15 有多少库存？"）
     * 2. ChatClient 把问题 + 工具定义（只有 goods 开头的工具）发给大模型
     * 3. 大模型判断需要查询商品 → 调用 goods_getNum("iPhone 15")
     * 4. Spring AI 拦截 → 通过 MCP 协议调用远程工具 → 结果回灌大模型
     * 5. 大模型生成最终回答
     *
     * @param userMessage 用户消息
     * @return 大模型回答
     */
    public String chat(String userMessage) {
        return chatClient.prompt().user(userMessage).call().content();
    }

    /**
     * 测试入口（手动创建 SSE 客户端）
     * <p>
     * 注意：如果 MCP Server 配置了 context-path（如 /test），
     * 这里的 baseUrl 需要包含 context-path，sseEndpoint 不包含
     * <p>
     * 示例：
     * - Server 配置：context-path: /test, sse-endpoint: sse
     * - 客户端配置：baseUrl = "http://127.0.0.1:8003/test/", sseEndpoint = "sse"
     * - 完整路径：http://127.0.0.1:8003/test/sse
     *
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        // 如果 context-path: /test 设置了，这里的 builder 里面的地址与 sseEndpoint 传参需要注意
        HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://127.0.0.1:8003/test/").sseEndpoint("sse").build();
        McpSyncClient sseClient = McpClient.sync(transport).clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("sse-client", "1.0")).requestTimeout(Duration.ofSeconds(10)).build();
        sseClient.initialize();
    }
}
