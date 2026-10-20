package com.jiegiser.llm;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MCP Server 主应用（stdio 模式）
 * <p>
 * 功能说明：
 * 本应用作为 MCP（Model Context Protocol）服务器运行，通过标准输入/输出（stdio）
 * 与 MCP 客户端（如 Cline、Spring AI ChatClient）通信，提供工具调用能力。
 * <p>
 * 工作流程：
 * 1. 应用启动 → Spring Boot 自动配置 MCP Server
 * 2. 扫描 @Tool 注解 → 生成工具定义（ToolDefinition）
 * 3. MCP Server 监听 stdio → 等待客户端连接
 * 4. 客户端发送 tools/list → 获取可用工具列表
 * 5. 客户端发送 tools/call → 调用具体工具方法
 * 6. MCP Server 执行 Java 方法 → 返回结果给客户端
 * <p>
 * 通信协议：
 * - 传输层：stdio（标准输入/输出），不是 HTTP
 * - 消息格式：JSON-RPC 2.0
 * - 工具发现：tools/list 端点
 * - 工具调用：tools/call 端点
 * <p>
 * 配置说明：
 * - application.yml 中 spring.ai.mcp.server.stdio=true
 * - web-application-type=none（不启动 Tomcat）
 */
@SpringBootApplication
public class McpServerStdioApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerStdioApplication.class, args);
    }

    /**
     * 注册工具提供者 Bean
     * <p>
     * 作用：
     * 1. MethodToolCallbackProvider 会扫描 weatherService 对象的所有方法
     * 2. 找到带 @Tool 注解的方法（如 WeatherService.getWeather）
     * 3. 生成工具定义（ToolDefinition）：
     *    {
     *      "name": "getWeather",
     *      "description": "根据城市名称查询天气信息",
     *      "parameters": {
     *        "type": "object",
     *        "properties": {
     *          "city": {"type": "string"}
     *        },
     *        "required": ["city"]
     *      }
     *    }
     * 4. 创建工具回调（ToolCallback），封装方法调用逻辑
     * <p>
     * MCP Server 自动收集所有 ToolCallbackProvider Bean，
     * 通过 tools/list 端点暴露给客户端。
     *
     * @param weatherService 天气服务（Spring Bean，包含 @Tool 方法）
     * @return 工具提供者，提供 getWeather 工具
     */
    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        // 自动扫描 WeatherService 中带有 @Tool 注解的方法
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }
}
