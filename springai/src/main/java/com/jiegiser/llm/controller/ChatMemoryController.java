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
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 的对话记忆指的是 AI 在单次对话或者跨对话中，记住并利用之前交互信息的能力，这就像你和一个人聊天，如果他只能记住你刚才说的最后一句话，那对话将无法进行。
 * 因此，记忆是实现连贯、智能对话的基石；记忆能力越强，对话越智能。
 *
 * 记忆主要分为两大类：
 *
 * 1. 短期记忆
 *
 * 短期记忆也称为“上下文记忆”或“对话记忆”。指在单次对话会话中，AI能记住的之前对话内容。这通常受限于一个固定的“上下文窗口”（可以理解为一篇文章的最大字数）。
 * 当你发送一条消息时，系统会将你当前的问题和之前一定轮次的对话历史（都在上下文窗口内）一起送给AI模型处理，这样AI就能根据整个对话上下文来生成回答。
 *
 * 2. 长期记忆
 *
 * 长期记忆也称为“持久性记忆”或“外部记忆”。指AI能够将重要信息存储在对话之外（如数据库、向量数据库），并在未来的对话中根据需要检索和使用的记忆。
 *
 * 比如说，你告诉一个具备长期记忆的AI助手：“我对花生过敏。”一周后，你问：“推荐一些健康的零食。”（两次问答并不是在同一个对话中）AI在回答前，会检索你的长期记忆，发现你对花生过敏，从而避免推荐任何含花生的食品。
 *
 * 相比短期记忆，长期记忆有以下特点：
 * • 持久性：信息被保存在外部，不依赖于单次对话的上下文窗口。
 * • 可扩展性：理论上可以存储海量信息，只受外部存储介质的限制。
 * • 需要主动管理：用户可能需要手动添加、修改或删除记忆。
 * • 丢失完整性：长期记忆通常以摘要形式存储，势必会丢失对话细节。
 * • 适用场景：更适用于保存用户偏好、历史行为，不适合依赖精确上下文的复杂推理或即时决策。
 *
 */
@RestController
@RequestMapping("/memory")
public class ChatMemoryController implements InitializingBean {
    @Autowired
    private ChatModel dashScopeChatModel;

    private ChatClient chatClient;

    @GetMapping("/call")
    public String call() {
        List<Message> messages = new ArrayList<>();

        // 第一轮对话
        messages.add(new SystemMessage("你是一个游戏设计师"));

        messages.add(new UserMessage("我想设计一个回合制游戏"));
        ChatResponse chatResponse = dashScopeChatModel.call(new Prompt(messages));
        String content = chatResponse.getResult().getOutput().getText();
        System.out.println(content);
        System.out.println("======");

        messages.add(new AssistantMessage(content));

        // 第二轮对话
        messages.add(new UserMessage("能帮我结合一些二次元的元素吗?"));
        chatResponse = dashScopeChatModel.call(new Prompt(messages));
        content = chatResponse.getResult().getOutput().getText();
        System.out.println(content);
        System.out.println("======");
        messages.add(new AssistantMessage(content));

        // 第三轮对话
        messages.add(new UserMessage("那如果主要是针对女性玩家的游戏呢?有什么需要改进的？"));

        Prompt prompt = new Prompt(messages);
        return dashScopeChatModel.call(prompt).getResult().getOutput().getText();
    }

    @GetMapping("/call1")
    public String call1() {
        List<Message> messages = new ArrayList<>();
        // 第一轮对话
        messages.add(new SystemMessage("你是一个旅行推荐师"));
        messages.add(new UserMessage("我想去中国旅行"));
        messages.add(new AssistantMessage("中国有很多美丽的自然风光，比如长城、故宫、泰山等等。"));
        messages.add(new UserMessage("我喜欢自然风光"));
        messages.add(new AssistantMessage("那你可以去中国的自然风光名胜，比如张家界、九寨沟、黄山等等。"));

        messages.add(new UserMessage("我喜欢自然风光"));

        Prompt prompt = new Prompt(messages);
        return dashScopeChatModel.call(prompt).getResult().getOutput().getText();
    }

    /**
     * 聊天记忆
     */
    @GetMapping("/callConversation")
    public Flux<String> callConversation(String message, String chatId, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    @Autowired
    private ChatMemory chatMemory;

    @Override
    public void afterPropertiesSet() throws Exception {
        // 记忆功能
        // ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(3).build();
        this.chatClient = ChatClient.builder(dashScopeChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build(), new SimpleLoggerAdvisor())
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.6)
                                .build()
                ).build();
    }

    /**
     * 所以代码中 new 一个 ChatMemory 的地方（或者构造 ChatMemory），就可以直接 @Autowire 一个就行了，
     * 默认注入的是一个基于内存的对话记忆，如果需要持久化的记忆，Spring AI还支持了数据库。如：
     * spring-ai-starter-model-chat-memory-repository-jdbc
     */
}
