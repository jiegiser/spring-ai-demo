package com.jiegiser.llm.tools;/*
 * @Auther: changjie
 * @Date:2026/10/20
 * @Description:
 * @Modified By:
 */

import com.jiegiser.llm.service.OrderManageService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {
    @Autowired
    private OrderManageService orderManageService;

    @Tool(name = "apply_refund", description = "根据用户传入的订单信息发起退款")
    public String refund(
            @ToolParam(description = "订单编号，为数字类型") String orderId,
            @ToolParam(description = "商品名称") String productName,
            @ToolParam(description = "退款原因") String reason)
    {
        System.out.println("已为商品:" + productName + ",订单号:" + orderId + "申请退款 , 退款原因： " + reason);
        orderManageService.refundOrder(orderId, reason);
        return "已为商品:" + productName + ",订单号:" + orderId + "申请退款 , 退款原因： " + reason;
    }
}
