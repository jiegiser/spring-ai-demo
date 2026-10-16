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
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream().content();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(jdbcChatMemory).build(), new SimpleLoggerAdvisor())
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)
                                .build()
                ).build();
    }
}
