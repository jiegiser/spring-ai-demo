package com.jiegiser.llm.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 商品服务（MCP 工具提供者）
 * <p>
 * 提供两个商品查询工具：
 * 1. goods_getNum：查询商品数量
 * 2. goods_getDesc：查询商品描述
 * <p>
 * 使用场景：
 * - 用户问："iPhone 15 还有货吗？" → 调用 goods_getNum("iPhone 15")
 * - 用户问："iPhone 15 有什么特点？" → 调用 goods_getDesc("iPhone 15")
 */
@Service
public class GoodsService {

    /**
     * 查询商品数量
     * <p>
     * 工具定义：
     * {
     *   "name": "goods_getNum",
     *   "description": "根据商品名称或者数量",
     *   "inputSchema": {
     *     "type": "object",
     *     "properties": {
     *       "name": {"type": "string", "description": "商品名称"}
     *     },
     *     "required": ["name"]
     *   }
     * }
     *
     * @param name 商品名称
     * @return 商品数量信息
     */
    @Tool(name = "goods_getNum", description = "根据商品名称或者数量")
    public String getGoodsNum(String name) {
        return "商品名称：" + name + "，数量：100";
    }

    /**
     * 查询商品描述
     *
     * @param name 商品名称
     * @return 商品描述信息
     */
    @Tool(name = "goods_getDesc", description = "获取商品描述")
    public String getGoodsDesc(String name) {
        return "商品名称：" + name + "，描述：这是一个商品";
    }
}
