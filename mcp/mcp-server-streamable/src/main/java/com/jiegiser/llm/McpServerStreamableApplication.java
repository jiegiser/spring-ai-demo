package com.jiegiser.llm;

import com.jiegiser.llm.service.GoodsService;
import com.jiegiser.llm.service.OrderService;
import com.jiegiser.llm.service.TradeService;
import com.jiegiser.llm.service.WeatherService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MCP Server 主应用（Streamable HTTP 模式）
 * <p>
 * 功能说明：
 * 本应用作为 MCP（Model Context Protocol）服务器运行，通过 Streamable HTTP 协议
 * 与 MCP 客户端通信，提供工具调用能力。
 * <p>
 * 与 mcp-server-stdio 模块的区别：
 * - stdio 模式：通过标准输入/输出通信，客户端以子进程方式连接（适合本地工具）
 * - streamable 模式：通过 HTTP 通信，客户端以 HTTP 请求方式连接（适合远程服务）
 * <p>
 * 工作流程：
 * 1. 应用启动 → Spring Boot 自动配置 MCP Server（HTTP 模式，端口 8004）
 * 2. 扫描 @Tool 注解 → 生成工具定义（ToolDefinition）
 * 3. MCP Server 监听 HTTP 端点 → /stream/test/api/mcp
 * 4. 客户端发送 HTTP 请求 → 获取工具列表 / 调用工具
 * 5. MCP Server 执行 Java 方法 → 返回结果给客户端
 * <p>
 * 通信协议：
 * - 传输层：HTTP（Streamable HTTP）
 * - 消息格式：JSON-RPC 2.0
 * - 工具发现：POST /stream/test/api/mcp → {"method": "tools/list"}
 * - 工具调用：POST /stream/test/api/mcp → {"method": "tools/call", ...}
 * <p>
 * 配置说明：
 * - application.yml 中 spring.ai.mcp.server.protocol=STREAMABLE
 * - server.port=8004（HTTP 端口）
 * - server.servlet.context-path=/stream/test（上下文路径）
 * - spring.ai.mcp.server.streamable-http.mcp-endpoint=/api/mcp（MCP 端点）
 */
@SpringBootApplication
public class McpServerStreamableApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerStreamableApplication.class, args);
    }

    /**
     * 注册工具提供者 Bean
     * <p>
     * 作用：
     * 1. MethodToolCallbackProvider 会扫描 weatherService 对象的所有方法
     * 2. 找到带 @Tool 注解的方法（如 getWeather、queryWeather）
     * 3. 生成工具定义（ToolDefinition）
     * 4. 创建工具回调（ToolCallback），封装方法调用逻辑
     * <p>
     * MCP Server 自动收集所有 ToolCallbackProvider Bean，
     * 通过 HTTP 端点暴露给客户端。
     *
     * @param weatherService 天气服务（Spring Bean，包含 @Tool 方法）
     * @return 工具提供者，提供 getWeather 和 queryWeather 工具
     */
    // @Bean
    // public ToolCallbackProvider weatherTools(WeatherService weatherService) {
    //     return MethodToolCallbackProvider.builder()
    //             .toolObjects(weatherService)
    //             .build();
    // }

    @Bean
    public ToolCallbackProvider weatherTools(GoodsService goodsService, TradeService tradeService, OrderService orderService) {
        // 自动扫描 goodsService、tradeService、orderService 对象的所有方法，带有 @Tool 注解的方法
        return MethodToolCallbackProvider.builder()
                .toolObjects(goodsService, tradeService, orderService)
                .build();
    }
}
