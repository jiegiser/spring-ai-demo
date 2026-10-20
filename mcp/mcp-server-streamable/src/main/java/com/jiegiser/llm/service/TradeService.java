package com.jiegiser.llm.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 交易服务（MCP 工具提供者）
 * <p>
 * 提供交易查询工具：
 * - trade_getInfo：根据交易 ID 获取交易详情
 * <p>
 * 使用场景：
 * - 用户问："交易 T001 的详情是什么？" → 调用 trade_getInfo("T001")
 * - 用户问："帮我查一下交易状态" → 大模型追问交易 ID → 调用 trade_getInfo
 */
@Service
public class TradeService {

    /**
     * 根据交易 ID 获取交易详情
     * <p>
     * 工具定义：
     * {
     *   "name": "trade_getInfo",
     *   "description": "根据交易 id 获取详情",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "id": {"type": "string", "description": "交易 ID"}
     *     },
     *     "required": ["id"]
     *   }
     * }
     *
     * @param id 交易 ID
     * @return 交易详情字符串
     */
    @Tool(name = "trade_getInfo", description = "根据交易 id 获取详情")
    public String getTradeInfo(String id) {
        return "交易 id：" + id + "，交易详情：这是一个交易详情";
    }
}
