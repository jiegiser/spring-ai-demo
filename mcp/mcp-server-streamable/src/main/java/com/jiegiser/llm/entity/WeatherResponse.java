package com.jiegiser.llm.entity;

import lombok.Data;

/**
 * 天气查询响应对象
 * <p>
 * 作用：
 * 作为 queryWeather 工具的返回值，封装天气查询结果。
 * <p>
 * 字段说明：
 * - city：查询的城市
 * - date：查询的日期
 * - i：区县
 * - s：街道
 * - description：天气描述（如："晴朗，有微风"）
 * - temperature：温度（摄氏度）
 * <p>
 * 返回给大模型后，大模型会根据这些信息生成自然语言回答，例如：
 * "明天北京市朝阳区三里屯的天气是晴朗，有微风，温度约 18°C。"
 */
@Data
public class WeatherResponse {

    /** 城市名称 */
    private String city;

    /** 查询日期 */
    private String date;

    /** 区县 */
    private String i;

    /** 街道 */
    private String s;

    /** 天气描述（如："晴朗，有微风"） */
    private String description;

    /** 温度（摄氏度） */
    private double temperature;

    /**
     * 全参数构造函数
     *
     * @param city        城市
     * @param date        日期
     * @param i           区县
     * @param s           街道
     * @param description 天气描述
     * @param temperature 温度
     */
    public WeatherResponse(String city, String date, String i, String s, String description, double temperature) {
        this.city = city;
        this.date = date;
        this.i = i;
        this.s = s;
        this.description = description;
        this.temperature = temperature;
    }
}
