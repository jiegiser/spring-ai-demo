package com.jiegiser.llm.controller;/*
 * @Auther: changjie
 * @Date:2026/10/16
 * @Description:
 * @Modified By:
 */

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 持久化记忆
 * 在 Spring AI 中，我们最熟悉的就是 MessageWindowChatMemory ，它默认是一个基于内存的短期记忆实现，它维护最多指定最大大小（默认值：20 条消息）的消息窗口。
 *
 * 当消息数量超过此限制时，较旧的消息会被踢出。这确保对话始终可以使用最新的上下文，同时保持内存使用有界。应用一旦重启，内存中的记忆就会丢失。
 *
 * Spring AI 中的记忆存储是基于 ChatMemoryRepository 这个接口扩展的，他提供了一些列实现用来做基于存储，
 * 包括 MessageWindowChatMemory 所使用到的 InMemoryChatMemoryRepository，这就是一种基于 ConcurrentHashMap 的本地内存的记忆方式。
 *
 * 数据库需要自己创建 SPRING_AI_CHAT_MEMORY 表
 * CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
 *     `conversation_id` VARCHAR(36) NOT NULL,
 *     `content` TEXT NOT NULL,
 *     `type` ENUM('USER', 'ASSISTANT', 'SYSTEM', 'TOOL') NOT NULL,
 *     `timestamp` TIMESTAMP NOT NULL,
 *
 *     INDEX `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX` (`conversation_id`, `timestamp`)
 * );
 */
@RestController
@RequestMapping("/jdbc/memory")
public class JdbcChatMemoryController implements InitializingBean {
    @Autowired
    private ChatModel chatModel;
    private ChatClient chatClient;

    @Autowired
    private ChatMemory jdbcChatMemory;

    @GetMapping("/callDb")
    public Flux<String> callDb(String message, String chatId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        return chatClient
                .prompt()
                .user(message)
                // 在这次请求上打一个标记，key 叫 chat_memory_conversation_id，值是 chatId
                /**
                 * 它跟 defaultAdvisors 里的 MessageChatMemoryAdvisor 怎么配合
                 * GET /jdbc/memory/callDb?message=你好&chatId=abc123
                 *         │
                 *         ▼
                 * 请求参数容器里塞入：{ "chat_memory_conversation_id" → "abc123" }
                 *         │
                 *         ▼
                 * 请求穿过 Advisor 链，到达 MessageChatMemoryAdvisor：
                 *         │
                 *         ├─ 读取参数：conversationId = "abc123"
                 *         ├─ 用它查库：SELECT * FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = 'abc123'
                 *         ├─ 把查到的历史消息拼进 Prompt
                 *         ▼
                 * 调大模型 → 拿到回复
                 *         │
                 *         └─ MessageChatMemoryAdvisor 把本轮问答也存进库（conversation_id = 'abc123'）
                 */
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.chatClient = ChatClient.builder(chatModel)
                // 注册默认的 Advisor（拦截器链），对通过本 ChatClient 发出的每一次请求都生效：
                // 1. MessageChatMemoryAdvisor：记忆拦截器，请求发送前从 ChatMemory 读取会话历史注入到 Prompt 中，
                //    AI 回复后又会把新的问答写回 ChatMemory，从而实现多轮对话记忆；
                //    会话隔离通过 CONVERSATION_ID 参数指定（见 callDb 里的 advisors(...)）。
                // 2. SimpleLoggerAdvisor：日志拦截器，把最终发给模型的 Prompt 和模型的响应打印到日志，方便调试。
                // Advisor 是责任链模式（类似 Servlet Filter / Spring AOP）：
                // 请求依次穿过每个 Advisor，每个 Advisor 可修改请求（如注入历史消息）、
                // 修改响应（如日志、脱敏），或直接短路返回（如 RAG 命中缓存）。
                // defaultAdvisors = 全局默认；单个请求里也可用 .advisors(...) 追加或传参数。
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(jdbcChatMemory).build(), new SimpleLoggerAdvisor())
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .topP(0.7)
                                .build()
                ).build();
    }
}
