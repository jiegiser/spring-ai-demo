package com.jiegiser.llm;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 天气服务（MCP 工具提供者）
 * <p>
 * 功能说明：
 * 本类提供天气查询工具，通过 @Tool 注解标记为 MCP 工具。
 * MCP Server 启动时会扫描此类，将 getWeather 方法暴露给客户端。
 * <p>
 * @Tool 注解的作用：
 * 1. name：工具名称，客户端通过此名称调用工具
 * 2. description：工具描述，帮助大模型理解何时使用此工具
 * 3. 方法参数：自动生成 JSON Schema，客户端据此构造参数
 * <p>
 * 客户端调用流程：
 * 1. 客户端发送 tools/list → 获取工具定义
 * 2. 大模型分析用户问题 → 决定调用 getWeather
 * 3. 客户端发送 tools/call → {"name":"getWeather","arguments":{"city":"北京"}}
 * 4. MCP Server 执行 getWeather("北京") → 返回 "北京：晴，25°C"
 * 5. 客户端收到结果 → 大模型生成最终回答
 */
@Service
public class WeatherService {

    /**
     * 根据城市名称查询天气信息
     * <p>
     * 这是一个 MCP 工具方法，会被暴露给客户端调用。
     * <p>
     * 工具定义（自动生成）：
     * {
     *   "name": "getWeather",
     *   "description": "根据城市名称查询天气信息",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "city": {
     *         "type": "string",
     *         "description": "城市名称"
     *       }
     *     },
     *     "required": ["city"]
     *   }
     * }
     *
     * @param city 城市名称（如：北京、上海、深圳）
     * @return 天气信息字符串（如："北京：晴，25°C"）
     */
    @Tool(name = "getWeather", description = "根据城市名称查询天气信息")
    public String getWeather(String city) {
        if (city == null) {
            return "请提供城市名称";
        }
        return switch (city) {
            case "北京" -> "北京：晴，25°C";
            case "上海" -> "上海：多云，22°C";
            case "深圳" -> "深圳：小雨，28°C";
            default -> city + ": 下雪，-20°C";
        };
    }
}
