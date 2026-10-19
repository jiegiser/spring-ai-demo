package com.jiegiser.llm.controller;
/*
 * @Auther: changjie
 * @Date:2026/10/20
 * @Description: 拼多多退款售后咨询控制器
 * @Modified By:
 */

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.jiegiser.llm.model.ChatStatus;
import com.jiegiser.llm.model.OrderChat;
import com.jiegiser.llm.tools.OrderTools;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * 模拟 PDD 退款售后咨询
 * <p>
 * 功能说明：
 * 1. 支持用户发起售后咨询（newChat）：创建对话会话，返回初始订单信息
 * 2. 支持多轮对话问答（ask）：基于对话记忆，流式返回回答，并可调用工具查询订单信息
 * <p>
 * 核心特性：
 * - 使用 DashScope 大模型（通义千问）
 * - 集成 ChatMemory 实现多轮对话记忆
 * - 集成 OrderTools 实现 Function Call（工具调用）
 * - 支持流式输出（Flux）
 */
@RestController
@RequestMapping("/pdd/refund")
public class PddRefundController {

    /**
     * DashScope 大模型实例（通义千问）
     * 直接注入具体实现类，避免多 ChatModel Bean 冲突
     */
    @Autowired
    private DashScopeChatModel chatModel;

    /**
     * ChatClient 客户端
     * 封装了模型调用、Advisor 拦截器链、系统提示词等配置
     * 在 @PostConstruct 中初始化
     */
    private ChatClient chatClient;

    /**
     * 订单工具类
     * 包含可被大模型调用的方法（如查询订单详情、退款状态等）
     * 通过 .tools(orderTools) 注册到 ChatClient，实现 Function Call
     */
    @Autowired
    private OrderTools orderTools;

    /**
     * 系统提示词模板文件
     * 从 classpath:templates/pdd_refund_system_prompt.pt 加载
     * 定义 AI 助手的角色、行为规范、回答风格等
     */
    @Value("classpath:templates/pdd_refund_system_prompt.pt")
    private Resource systemText;

    /**
     * 发起新的售后咨询对话
     * <p>
     * 流程：
     * 1. 生成唯一对话 ID（chatId）
     * 2. 将用户 ID、订单号、对话 ID 等信息发送给大模型
     * 3. 大模型根据系统提示词和工具，返回初始订单信息（OrderChat 对象）
     * 4. 对话记忆会自动保存本次交互，后续可通过 chatId 继续对话
     *
     * @param userId   用户 ID
     * @param orderId  订单号
     * @param response HTTP 响应对象（设置编码）
     * @return 订单聊天信息（OrderChat 结构化输出）
     */
    @GetMapping("/newChat")
    public OrderChat newChat(String userId, String orderId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        // 生成唯一对话 ID，用于关联同一轮对话的所有消息
        String chatId = UUID.randomUUID().toString();
        return chatClient
                .prompt()
                // 构造用户消息，包含用户 ID、订单号、对话 ID、对话状态
                .user(String.format("我要咨询订单相关的售后问题，我的用户id是%s,我的订单号是: %s ,本地的对话Id是 %s，当前状态是 %s", userId, orderId, chatId, ChatStatus.CHAT_START.name()))
                // 注册 Advisor 参数：
                // - CONVERSATION_ID: 对话唯一标识，用于隔离不同对话的记忆
                // - chat_memory_retrieve_size: 从记忆中检索的历史消息条数（100 条）
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 100))
                // 同步调用，并将返回结果转换为 OrderChat 对象（结构化输出）
                .call().entity(OrderChat.class);
    }

    /**
     * 多轮对话问答（流式输出）
     * <p>
     * 流程：
     * 1. 接收用户问题和对话 ID
     * 2. 从 ChatMemory 中加载该对话的历史消息
     * 3. 大模型根据历史上下文 + 当前问题生成回答
     * 4. 如果大模型判断需要查询订单信息，会自动调用 OrderTools 中的方法
     * 5. 流式返回回答内容（Flux<String>）
     *
     * @param question 用户问题
     * @param chatId   对话 ID（关联 newChat 创建的会话）
     * @param response HTTP 响应对象（设置编码）
     * @return 流式回答（Flux<String>），前端可通过 SSE 接收
     */
    @GetMapping("/ask")
    public Flux<String> ask(String question, String chatId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatClient.prompt()
                .user(question)  // 用户当前问题
                .tools(orderTools)  // 注册工具，允许大模型调用 OrderTools 中的方法查询订单
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId).param("chat_memory_retrieve_size", 100))
                // 流式调用，返回内容流（Flux<String>）
                .stream().content();
    }

    /**
     * 对话记忆组件
     * 自动注入 Spring AI 提供的 ChatMemory Bean
     * 用于存储和检索多轮对话的历史消息
     */
    @Autowired
    private ChatMemory chatMemory;

    /**
     * 初始化 ChatClient
     * 在 Bean 创建完成后执行（@PostConstruct）
     * <p>
     * 配置项：
     * - defaultAdvisors: 注册默认 Advisor 拦截器链
     *   1. MessageChatMemoryAdvisor: 自动注入历史消息到 Prompt
     *   2. SimpleLoggerAdvisor: 记录请求/响应日志
     * - defaultSystem: 设置系统提示词（定义 AI 角色和行为规范）
     */
    @PostConstruct
    public void init() {
        chatClient = ChatClient.builder(chatModel)
                // 注册默认 Advisor（拦截器链），对所有请求生效
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                // 设置系统提示词（从 pdd_refund_system_prompt.pt 加载）
                .defaultSystem(systemText)
                .build();
    }
}
