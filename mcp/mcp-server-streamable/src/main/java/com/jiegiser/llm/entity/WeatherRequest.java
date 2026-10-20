package com.jiegiser.llm.entity;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 天气查询请求对象
 * <p>
 * 作用：
 * 作为 queryWeather 工具的输入参数，封装用户查询天气所需的完整信息。
 * <p>
 * @ToolParam 注解：
 * - 标记字段为 MCP 工具参数
 * - description 会写入 JSON Schema，帮助大模型理解参数含义
 * - 大模型根据这些描述决定如何填充参数值
 * <p>
 * 生成的 JSON Schema：
 * {
 *   "type": "object",
 *   "properties": {
 *     "city": {"type": "string", "description": "城市"},
 *     "date": {"type": "string", "description": "日期"},
 *     "i": {"type": "string", "description": "区县"},
 *     "s": {"type": "string", "description": "街道"}
 *   },
 *   "required": ["city", "date", "i", "s"]
 * }
 * <p>
 * 字段命名说明：
 * - city、date：语义清晰，直接使用
 * - i：区县（district 的缩写，可能是历史原因或 API 兼容）
 * - s：街道（street 的缩写）
 */
@Data
public class WeatherRequest {

    /** 城市名称（如：北京） */
    @ToolParam(description = "城市")
    private String city;

    /** 查询日期（如：2026-10-20 或 "明天"） */
    @ToolParam(description = "日期")
    private String date;

    /** 区县名称（如：朝阳区） */
    @ToolParam(description = "区县")
    private String i;

    /** 街道名称（如：三里屯） */
    @ToolParam(description = "街道")
    private String s;
}
