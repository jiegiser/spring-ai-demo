package com.jiegiser.llm.agent.agent;

import com.jiegiser.llm.agent.advisor.ReflectionAdvisor;
import com.jiegiser.llm.agent.config.ChatModelConfig;
import com.jiegiser.llm.agent.tools.SearchService;
import com.jiegiser.llm.agent.tools.WeatherService;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 带反思能力的 ReAct Agent
 * <p>本类不自己实现推理循环，而是采用装饰器模式：内部持有一个 {@link SimpleReactAgent}，
 * 构建时自动注入 {@link ReflectionAdvisor}，由它在每轮回答后评估质量并给出改进反馈。</p>
 * <p>反思链路：</p>
 * <pre>
 * 用户问题 → SimpleReactAgent 跑 ReAct 循环 → 产出本轮答案
 *    → ReflectionAdvisor 用同一个 ChatModel 以"审稿人"角色评估
 *    → 未通过则往 response.context() 写入 reflection.required / reflection.feedback
 *    → SimpleReactAgent 检测到该标记，把反馈作为 AssistantMessage 注入上下文并重新规划
 *    → 重新调用工具 / 重新作答，直到通过或达到 maxReflectionRounds
 * </pre>
 * <p>注意：当前仅封装了非流式调用。SimpleReactAgent 的流式分支未实现反思处理，
 * 因此流式场景下 ReflectionAdvisor 不会生效。</p>
 */
public class ReflectionAgent {

    /** 被装饰的 ReAct Agent，实际推理循环由它完成 */
    private final SimpleReactAgent delegate;

    /**
     * 构造包装器
     *
     * @param delegate 内部执行的 ReAct Agent
     */
    private ReflectionAgent(SimpleReactAgent delegate) {
        this.delegate = delegate;
    }

    /**
     * 非流式问答（不带会话记忆）
     *
     * @param question 用户问题
     * @return 最终答案文本
     */
    public String call(String question) {
        return delegate.call(question);
    }

    /**
     * 非流式问答（带会话记忆）
     *
     * @param conversationId 会话 ID，用于读取/写入历史
     * @param question       用户问题
     * @return 最终答案文本
     */
    public String call(String conversationId, String question) {
        return delegate.call(conversationId, question);
    }

    /**
     * 创建 Builder
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Agent 构造器
     */
    public static class Builder {

        /** Agent 名称 */
        private String name = "reflection-react-agent";
        /** 大模型客户端，必填；既用于推理也用于反思评估 */
        private ChatModel chatModel;
        /** 工具清单 */
        private List<ToolCallback> tools = new ArrayList<>();
        /** 最大推理轮数，<=0 表示不限制（与 SimpleReactAgent 语义一致） */
        private int maxRounds;
        /** 业务角色提示词 */
        private String systemPrompt = "";
        /** 额外 Advisor，会与内置的 ReflectionAdvisor 合并 */
        private List<Advisor> advisors = new ArrayList<>();
        /** 最大反思轮数，默认 1（即最多反思一次） */
        private int maxReflectionRounds = 1;

        /**
         * 设置 Agent 名称
         *
         * @param name 名称
         * @return this
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置大模型客户端
         *
         * @param chatModel 大模型客户端
         * @return this
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * 设置工具清单
         *
         * @param tools 工具回调数组
         * @return this
         */
        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);
            return this;
        }

        /**
         * 设置工具清单
         *
         * @param tools 工具回调列表
         * @return this
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * 追加额外的 Advisor（可多次调用，多次追加）
         *
         * @param advisors 拦截器
         * @return this
         */
        public Builder advisors(Advisor... advisors) {
            this.advisors.addAll(Arrays.asList(advisors));
            return this;
        }

        /**
         * 设置业务角色提示词
         *
         * @param systemPrompt 提示词
         * @return this
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 设置最大反思轮数
         *
         * @param maxReflectionRounds 轮数，<=0 表示不启用反思
         * @return this
         */
        public Builder maxReflectionRounds(int maxReflectionRounds) {
            this.maxReflectionRounds = maxReflectionRounds;
            return this;
        }

        /**
         * 设置最大推理轮数
         *
         * @param maxRounds 轮数，<=0 表示不限制
         * @return this
         */
        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        /**
         * 构建 Agent
         * <p>组装顺序：外部 Advisor + 内置 ReflectionAdvisor → 交给 SimpleReactAgent 作为 defaultAdvisors。</p>
         *
         * @return ReflectionAgent 实例
         */
        public ReflectionAgent build() {

            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空");
            }

            // 反思评估复用同一个 ChatModel：同一个模型既负责回答，也负责给自己打分
            ReflectionAdvisor reflectionAdvisor = new ReflectionAdvisor(chatModel);

            // 拷贝一份再追加，避免直接修改调用方传入的 advisors 列表
            List<Advisor> finalAdvisors = new ArrayList<>(advisors);
            finalAdvisors.add(reflectionAdvisor);

            // 滑动窗口记忆，最多保留 20 条消息
            ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

            // 真正干活的是 SimpleReactAgent，反思能力通过 advisors + maxReflectionRounds 两个参数注入
            SimpleReactAgent reactAgent = SimpleReactAgent.builder()
                    .name(name)
                    .chatModel(chatModel)
                    .tools(tools)
                    .maxRounds(maxRounds)
                    .systemPrompt(systemPrompt)
                    .chatMemory(chatMemory)
                    .maxReflectionRounds(maxReflectionRounds)
                    .advisors(finalAdvisors)
                    .build();

            return new ReflectionAgent(reactAgent);
        }
    }

    /**
     * 演示入口：构建一个带反思能力的 Agent 并执行一个多工具任务
     */
    public static void main(String[] args) {
        ChatModel chatModel = ChatModelConfig.getChatModel();

        // 把带 @Tool 注解的方法包装成工具回调
        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());

        ReflectionAgent agent = ReflectionAgent.builder()
                .name("ReflectionAgent")
                .chatModel(chatModel)
                // 最多反思两轮
                .maxReflectionRounds(2)
                // -1 表示不限制推理轮数
                .maxRounds(-1)
                .tools(toolCallbacks)
                .systemPrompt("你是专业的研究分析助手！")
                .build();

        // 任务需要串联天气查询与搜索多个工具，正好演示 ReAct 多轮循环 + 反思重规划
        String question = """
                请你根据北京今天的天气、未来七天的天气趋势、以及上海今天的天气，并搜索北京天气的预警情况，生成一份不少于 200 字的综合分析报告。
                """;

        System.out.println(agent.call(question));
    }
}
