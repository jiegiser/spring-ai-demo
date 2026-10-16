package com.jiegiser.llm.controller;/*
 * @Auther: changjie
 * @Date:2026/10/13
 * @Description:ChatClientController
 * @Modified By:
 */

import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgentOptions;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/chat")
public class ChatClientController implements InitializingBean {
    @Autowired
    private ChatModel dashScopeChatModel;
    private ChatClient chatClient;

    @GetMapping("/simpleCall")
    public String chat(String message) {
        return chatClient.prompt(message).call().content();
    }

    @GetMapping("/callOverwrite")
    public String callOverwrite(String message) {
        // system 会覆盖默认的 system
        return chatClient.prompt(message).system("请用韩文回答问题").call().content();
    }

    @GetMapping("/callUser")
    public String callUser(String message) {
        // user 会追加
        return chatClient.prompt().user(message).call().content();
    }

    @GetMapping("/call")
    public String call(String message) {
        // Prompt 里面的内容相当于追加，不会覆盖
        return chatClient.prompt(new Prompt(new SystemMessage("请用韩文回答问题"), new UserMessage(message))).call().content();
    }

    @GetMapping("/stream")
    public Flux<String> stream(String message) {
        return chatClient.prompt(message).stream().content();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        chatClient = ChatClient.builder(dashScopeChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultSystem("请使用英文回答问题")
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .temperature(0.7)
                                .build()
                )
                .build();
    }
}
