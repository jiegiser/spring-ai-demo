package com.jiegiser.llm.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 订单服务（MCP 工具提供者）
 * <p>
 * 提供订单查询工具：
 * - order_getInfo：根据订单 ID 获取订单详情
 * <p>
 * 使用场景：
 * - 用户问："订单 12345 的详情是什么？" → 调用 order_getInfo("12345")
 * - 用户问："帮我查一下订单状态" → 大模型追问订单 ID → 调用 order_getInfo
 */
@Service
public class OrderService {

    /**
     * 根据订单 ID 获取订单详情
     * <p>
     * 工具定义：
     * {
     *   "name": "order_getInfo",
     *   "description": "根据订单 id 获取详情",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "id": {"type": "string", "description": "订单 ID"}
     *     },
     *     "required": ["id"]
     *   }
     * }
     *
     * @param id 订单 ID
     * @return 订单详情字符串
     */
    @Tool(name = "order_getInfo", description = "根据订单 id 获取详情")
    public String getOrderInfo(String id) {
        return "订单 id：" + id + "，订单详情：这是一个订单详情";
    }
}
