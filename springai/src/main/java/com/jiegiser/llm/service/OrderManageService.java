package com.jiegiser.llm.service;/*
 * @Auther: changjie
 * @Date:2026/10/20
 * @Description:
 * @Modified By:
 */

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OrderManageService {
    public String getOrderById(String orderId) {
        return "订单号：" + orderId;
    }

    public String refundOrder(String orderId, String reason) {
        System.out.println("退款订单号：" + orderId + "，原因：" + reason);
        return UUID.randomUUID().toString();
    }
}
