package com.jiegiser.llm;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    /**
     * 根据城市名称查询天气信息
     * @param city 城市名称
     * @return 天气信息
     * returnDirect 是否直接返回结果,
     * 为什么会 returnDirect 有这种直接返回的需求：也就是有的 模型直接返回了总结的结果，或者有的模型不需要你来总结，他会有一个子 agent 去总结；
     * 1. 直接返回结果：某些工具可能希望直接返回结果，而不需要进行额外的处理或逻辑判断。
     * 2. 避免重复处理：某些工具可能希望避免重复处理结果，例如在多个工具中使用相同的结果时，可以设置为直接返回结果，避免重复处理。
     * 3. 提高效率：某些工具可能希望提高效率，例如在多个工具中使用相同的结果时，可以设置为直接返回结果，避免重复处理。
     */
    @Tool(name = "getWeather", description = "根据城市名称查询天气信息", returnDirect = true)
    public String getWeather(String city) {
        if (city == null) {
            return "请提供城市名称";
        }
        return switch (city) {
            case "北京" -> "北京: 晴, 25°C";
            case "上海" -> "上海: 多云, 22°C";
            case "深圳" -> "深圳: 小雨, 28°C";
            default -> city + ": 下雪, -20°C";
        };
    }
}
