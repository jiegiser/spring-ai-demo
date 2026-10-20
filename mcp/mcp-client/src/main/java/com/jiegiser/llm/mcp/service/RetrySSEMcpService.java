package com.jiegiser.llm.mcp.service;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 断线重连服务（手动管理 MCP 客户端生命周期）
 * <p>
 * 功能说明：
 * 本服务演示如何手动创建和管理 MCP 客户端（区别于 YAML 自动配置），
 * 并实现 SSE 连接的断线检测与自动重连机制。
 * <p>
 * 为什么需要断线重连？
 * - SSE（Server-Sent Events）是长连接，MCP Server 重启或网络抖动会导致连接断开
 * - YAML 自动配置的客户端不会自动重连（Spring AI 的自动配置不包含重连逻辑）
 * - 断线后工具调用会失败，必须重建连接和 ChatClient
 * <p>
 * 重连机制流程：
 * 1. 应用启动 → 创建 SSE 客户端 → 尝试 initialize
 * 2. 初始化失败 → 启动后台重试线程（每 10 秒重试一次）
 * 3. 运行期间 → 定时任务每 5 秒 ping 一次，检测连接健康状态
 * 4. ping 失败 → 启动重试线程：重建客户端 → 重新 initialize → 重建 ChatClient
 * 5. 重试成功 → 关闭重试标志位，恢复正常服务
 * <p>
 * 与 McpClientService（YAML 自动配置）的区别：
 * - McpClientService：YAML 配置 → Spring AI 自动创建客户端 → 注入使用
 * - RetrySSEMcpService：Java 代码手动创建客户端 → 手动管理连接生命周期
 * <p>
 * 线程安全设计：
 * - AtomicBoolean（retrying）：防止多个定时任务同时触发重试（CAS 保证唯一性）
 * - SingleThreadExecutor（retryExecutor）：重试逻辑在独立线程执行，不阻塞定时任务
 * - chatClient/sseClient 直接替换引用：读写虽有竞态，但引用替换是原子操作，可接受
 */
@Service
@Slf4j
public class RetrySSEMcpService {

    /** OpenAI 兼容模型（DashScope 通义千问），用于 ChatClient 构建 */
    @Autowired
    private OpenAiChatModel chatModel;

    /** ChatClient：封装了大模型调用 + MCP 工具注册，断线重连后需要重建 */
    private ChatClient chatClient;

    /** SSE MCP 客户端：与远程 MCP Server 的长连接，断线后需要重建 */
    private McpSyncClient sseClient;

    /**
     * 重试标志位（CAS 原子操作）
     * - false → true：当前线程获得重试权，开始重试
     * - true → false：重试成功，释放重试权
     * 作用：防止 ping 定时任务多次触发时，启动多个重试线程
     */
    // 是否正在重试 initialize（保证唯一性）
    private final AtomicBoolean retrying = new AtomicBoolean(false);

    /**
     * 重试线程池（单线程）
     * - 单线程：保证重试逻辑串行执行，避免并发重建
     * - 独立线程：不阻塞 @Scheduled 定时任务线程
     */
    // initialize 重试线程
    private final ExecutorService retryExecutor = Executors.newSingleThreadExecutor();

    /**
     * 初始化（应用启动时执行一次）
     * <p>
     * 流程：
     * 1. 创建 SSE MCP 客户端
     * 2. 尝试 initialize（与 MCP Server 握手，交换能力信息）
     *    - 成功 → 正常
     *    - 失败（MCP Server 未启动）→ 启动后台重试线程
     * 3. 获取工具列表，构建 ChatClient
     * <p>
     * 注意：即使 initialize 失败，代码也会继续执行到构建 ChatClient。
     * 此时工具列表可能为空，等重试成功后 ChatClient 会被重建。
     */
    @PostConstruct
    public void init() {
        log.info("Initializing SSE MCP Client...");

        // 步骤 1：创建 SSE 客户端（只创建 transport 和 client 对象，还未连接）
        this.sseClient = buildClient();

        try {
            // 步骤 2：与 MCP Server 握手（initialize 是 MCP 协议的第一步）
            this.sseClient.initialize();
            log.info("SSE MCP client initialized.");
        } catch (Exception e) {
            // 握手失败（比如 MCP Server 还没启动），启动重试线程异步处理
            log.error("Initial SSE initialize failed, will rely on retry thread.", e);
            // 启动重试线程
            startRetryInitialize();
        }

        // 步骤 3：获取工具列表，构建 ChatClient
        // 手动构建 SyncMcpToolCallbackProvider（区别于 YAML 自动注入）
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(this.sseClient))  // 传入自定义的客户端
                .build();

        // 从 MCP Server 获取工具回调数组（内部调用 tools/list）
        ToolCallback[] callbacks = provider.getToolCallbacks();

        // 构建 ChatClient：注册 MCP 工具，后续大模型可自动调用
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(callbacks)
                .defaultTools()
                .build();
    }

    /**
     * 构建 SSE MCP 客户端
     * <p>
     * 组成部分：
     * 1. HttpClientSseClientTransport：SSE 传输层（负责 HTTP 长连接通信）
     *    - baseUri：MCP Server 地址
     *    - sseEndpoint：SSE 端点路径（客户端先 GET 这个端点建立事件流）
     * 2. McpClient.sync(transport)：基于传输层创建同步客户端
     *    - clientInfo：客户端标识（MCP Server 可以看到是谁在连接）
     *    - requestTimeout：单次请求超时时间（10 秒）
     * <p>
     * 注意：build() 只是创建对象，不会真正连接；
     * 真正的连接握手发生在 initialize() 调用时。
     *
     * @return 新的 MCP 同步客户端
     */
    private McpSyncClient buildClient() {
        // 创建 SSE 传输层：连接 http://127.0.0.1:8003/sse
        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                .builder("http://127.0.0.1:8003")
                .sseEndpoint("/sse")
                .build();

        return McpClient.sync(transport)
                // 客户端标识：名称 + 版本（MCP 协议握手时交换）
                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("sse-client", "1.0"))
                // 单次请求超时：10 秒（超时抛异常）
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 定时健康检查：每 5 秒 ping 一次 SSE 连接
     * <p>
     * MCP 协议的 ping 是心跳机制：
     * - 客户端发 ping → MCP Server 回 pong → 连接正常
     * - ping 超时/异常 → 连接已断开 → 触发重连
     * <p>
     * fixedDelay = 5000：上一次执行完成后 5 秒，再执行下一次
     * （区别于 fixedRate：固定频率触发，不管上次是否完成）
     */
    /**
     * 定时任务：每 5 秒 ping 一次 SSE
     * ping 不通则触发 initialize 重试线程
     */
    @Scheduled(fixedDelay = 5000)
    public void pingSse() {
        log.info("SSE MCP ping...");
        // 防御：客户端还没创建（理论上不会发生，init 一定先执行）
        if (sseClient == null) {
            log.warn("SSE client not initialized yet.");
            startRetryInitialize();
            return;
        }

        try {
            // 发送 ping，连接正常时 MCP Server 会返回 pong
            sseClient.ping();
            log.debug("SSE MCP ping OK.");
        } catch (Exception e) {
            // ping 失败 = 连接断开，触发重连
            log.error("SSE MCP ping failed: {}", e.getMessage());
            startRetryInitialize();
        }
    }

    /**
     * 启动 initialize 重试线程（断线重连核心逻辑）
     * <p>
     * 重试流程（在独立线程中循环执行）：
     * 1. 重建 sseClient（旧的连接已失效，必须新建）
     * 2. 重新 initialize（与 MCP Server 握手）
     * 3. 成功 → 重建 ChatClient（工具列表可能已变化）→ 释放重试标志 → 退出循环
     * 4. 失败 → 等 10 秒 → 继续下一轮重试（无限重试直到成功）
     * <p>
     * 为什么要重建 ChatClient？
     * - ChatClient 内部持有 ToolCallback，ToolCallback 绑定了旧的 sseClient
     * - 旧 sseClient 已断线，旧 ToolCallback 无法工作
     * - 必须用新 sseClient 重新获取工具回调，再重建 ChatClient
     */
    /**
     * 启动 initialize 重试线程
     */
    private void startRetryInitialize() {
        // CAS 操作：如果 retrying 已经是 true（有线程在重试），直接返回
        // 保证同一时刻只有一个重试线程在工作
        // 保证只启动一个重试线程
        if (!retrying.compareAndSet(false, true)) {
            return;
        }

        // 提交重试任务到单线程池（不阻塞当前调用线程）
        retryExecutor.submit(() -> {
            log.warn("Start retrying SSE MCP initialize...");

            while (true) {
                try {
                    // 步骤 1：重建客户端（旧连接已失效，不能复用）
                    // 重建 sseClient
                    this.sseClient = buildClient();
                    // 步骤 2：重新握手
                    this.sseClient.initialize();
                    log.info("SSE MCP re-initialized successfully.");

                    // 步骤 3：重建 ChatClient
                    // chatclient 也同样需要重建
                    SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                            .mcpClients(List.of(this.sseClient))
                            .build();

                    ToolCallback[] callbacks = provider.getToolCallbacks();

                    this.chatClient = ChatClient.builder(chatModel)
                            .defaultToolCallbacks(callbacks)
                            .defaultTools()
                            .build();

                    // 步骤 4：重试成功，释放标志位，退出重试循环
                    retrying.set(false);
                    return;
                } catch (Exception e) {
                    // 重试失败（MCP Server 还没恢复），打日志后继续下一轮
                    log.warn("Retry initialize failed, will retry in 10s. Reason: {}", e.getMessage());
                }

                // 等 10 秒后再重试（避免疯狂重试压垮 MCP Server）
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });

    }

    /**
     * 智能体对话（使用注册了 MCP 工具的 ChatClient）
     * <p>
     * 流程：
     * 1. 用户提问（如"北京天气怎么样"）
     * 2. ChatClient 把问题 + 工具定义发给大模型
     * 3. 大模型判断需要查天气 → 返回 tool_calls
     * 4. Spring AI 拦截 → 通过 MCP 协议调用远程工具 → 结果回灌大模型
     * 5. 大模型生成最终回答
     *
     * @param userMessage 用户消息
     * @return 大模型回答
     */
    public String chat(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}
