package com.jiegiser.llm.service;

import com.jiegiser.llm.entity.WeatherRequest;
import com.jiegiser.llm.entity.WeatherResponse;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 天气服务（MCP 工具提供者）
 * <p>
 * 本类提供两个 MCP 工具方法：
 * 1. getWeather：简单天气查询（按城市名）
 * 2. queryWeather：详细天气查询（按城市 + 日期 + 区县 + 街道）
 * <p>
 * 工具注册机制：
 * - 通过 @Tool 注解标记方法为 MCP 工具
 * - McpServerStreamableApplication 中的 weatherTools Bean 扫描此类
 * - 自动生成工具定义（ToolDefinition），通过 STREAMABLE HTTP 协议暴露给客户端
 * <p>
 * 与 mcp-server-stdio 模块的区别：
 * - stdio 模式：通过标准输入/输出通信，客户端以子进程方式连接
 * - streamable 模式：通过 HTTP 通信，客户端以 HTTP 请求方式连接（更灵活，支持远程调用）
 */
@Service
@Slf4j
public class WeatherService {

    /**
     * 简单天气查询工具
     * <p>
     * 工具定义（自动生成）：
     * {
     *   "name": "getWeather",
     *   "description": "根据城市名称查询天气信息",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "city": {"type": "string", "description": "城市名称"}
     *     },
     *     "required": ["city"]
     *   }
     * }
     * <p>
     * 使用场景：
     * - 用户问："北京天气怎么样？"
     * - 大模型判断需要查询天气 → 调用 getWeather("北京")
     * - 返回简单字符串："北京：晴，25°C"
     *
     * @param city 城市名称（如：北京、上海、深圳）
     * @return 天气信息字符串
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

    /**
     * 详细天气查询工具（支持多参数）
     * <p>
     * 工具定义（自动生成）：
     * {
     *   "name": "query_weather_by_city&date",
     *   "description": "根据城市和日期获取天气信息",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "city": {"type": "string", "description": "城市"},
     *       "date": {"type": "string", "description": "日期"},
     *       "i": {"type": "string", "description": "区县"},
     *       "s": {"type": "string", "description": "街道"}
     *     },
     *     "required": ["city", "date", "i", "s"]
     *   }
     * }
     * <p>
     * 与 getWeather 的区别：
     * - getWeather：简单查询，只传城市名，返回字符串
     * - queryWeather：详细查询，传完整地址信息（城市 + 日期 + 区县 + 街道），返回结构化对象
     * <p>
     * @ToolParam 注解的作用：
     * - 在 WeatherRequest 的字段上使用 @ToolParam(description = "...")
     * - Spring AI 自动将字段描述写入 JSON Schema 的 description
     * - 帮助大模型理解每个参数的含义
     * <p>
     * 使用场景：
     * - 用户问："明天北京市朝阳区三里屯的天气怎么样？"
     * - 大模型提取参数：city="北京", date="明天", i="朝阳区", s="三里屯"
     * - 调用 queryWeather(request) → 返回 WeatherResponse 对象
     * - 大模型根据返回结果生成自然语言回答
     * <p>
     * 模拟延迟：
     * - Thread.sleep(5000) 模拟真实 API 调用的延迟（5 秒）
     * - 实际项目中会调用第三方天气 API（如和风天气、OpenWeatherMap）
     *
     * @param request 天气查询请求（包含城市、日期、区县、街道）
     * @return 天气响应对象（包含温度、天气描述等）
     */
    @Tool(
            name = "query_weather_by_city&date",
            description = "根据城市和日期获取天气信息"
    )
    public WeatherResponse queryWeather(WeatherRequest request) {
        // 记录请求日志（用于调试和监控）
        log.info("query weather for city:{}", JSON.toJSONString(request));
        try {
            // 模拟调用外部 API 的延迟（5 秒）
            // 实际项目中这里是 HTTP 调用第三方天气 API
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 模拟返回数据：随机温度（10~25°C）
        double temp = Math.random() * 15 + 10;

        return new WeatherResponse(
                request.getCity(),      // 城市
                request.getDate(),      // 日期
                request.getI(),         // 区县
                request.getS(),         // 街道
                "晴朗，有微风",           // 天气描述
                temp                    // 温度
        );
    }
}
