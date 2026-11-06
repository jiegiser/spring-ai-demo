package com.jiegiser.llm.agent.agent;

import com.jiegiser.llm.agent.config.ChatModelConfig;
import com.jiegiser.llm.agent.prompts.PlanExecutePromptsFactory;
import com.jiegiser.llm.agent.tools.SearchService;
import com.jiegiser.llm.agent.tools.WeatherService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;


/**
 * Plan-Execute（计划-执行）模式智能体
 * <p>与 {@link SimpleReactAgent} 的 ReAct 循环不同：ReAct 是"想一步做一步"，本类先让模型一次性产出完整任务计划，
 * 再按计划分批执行，最后批判结果是否达标，不达标则带着批判意见进入下一轮重新规划。</p>
 * <p>单轮流程：</p>
 * <pre>
 * 1. generatePlan    → 模型根据当前上下文输出 JSON 任务列表（每个任务带 id、指令、order）
 * 2. executePlan    → 按 order 分组执行；同 order 的任务并行，不同 order 串行（后一个可依赖前面的结果）
 * 3. critique       → 批判模型判断目标是否达成
 * 4. compressIfNeeded→ 上下文超阈值时压缩，避免 token 膨胀
 * 5. summarize      → 汇总所有上下文产出最终答案
 * </pre>
 * <p>循环终止条件：批判通过、计划为空（无需执行）、或达到 maxRounds。</p>
 *
 * Plan（规划）阶段
 * 模型不做任何实际执行，只负责：
 * 1. 判断是否需要使用工具
 * 2. 拆解任务
 * 3. 明确每一步要调用什么工具
 * 4. 定义步骤之间的依赖关系
 *
 * Execute（执行）阶段
 * 系统按照执行计划：
 * 1. 串行/并行调用工具
 * 2. 收集结果
 * 3. 将结果反馈给模型用于后续判断
 *
 * 这和 ReAct 最大的区别在于：ReAct 是“边想边做”，Plan & Execute 是“先想清楚，再做”
 */
@Slf4j
public class PlanExecuteAgent {

    /** 大模型客户端，用于规划、批判、压缩、总结四个环节 */
    private final ChatModel chatModel;

    /** 可用工具清单，规划阶段会以文字形式告知模型，执行阶段由 SimpleReactAgent 实际调用 */
    private final List<ToolCallback> tools;

    // plan-execute 总轮数
    private final int maxRounds;

    // context 压缩阈值
    private final int contextCharLimit;

    // 控制工具并发调用上限
    private final Semaphore toolSemaphore;

    // 工具重试次数
    private final int maxToolRetries;

    /** 各环节提示词，传入 null 时由 PlanExecutePromptsFactory 回落到默认提示词 */
    private PlanExecutePromptsFactory planExecutePrompts;

    /** 会话记忆，为 null 时不启用多轮对话 */
    private ChatMemory chatMemory;

    /**
     * 构造 Plan-Execute Agent
     *
     * @param chatModel          大模型客户端
     * @param tools              工具清单
     * @param maxRounds          最大迭代轮数，<=0 表示不限制
     * @param contextCharLimit   上下文压缩阈值（字符数）
     * @param maxToolRetries     单个任务的最大重试次数
     * @param planExecutePrompts 各环节提示词，可为 null（使用默认）
     * @param chatMemory         会话记忆，可为 null
     */
    public PlanExecuteAgent(ChatModel chatModel,
                            List<ToolCallback> tools,
                            int maxRounds,
                            int contextCharLimit,
                            int maxToolRetries,
                            PlanExecutePromptsFactory planExecutePrompts,
                            ChatMemory chatMemory) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.maxRounds = maxRounds;
        this.contextCharLimit = contextCharLimit;
        this.maxToolRetries = maxToolRetries;
        // 信号量固定为 3：无论计划里有几个任务，最多同时跑 3 个，避免打爆下游工具服务
        this.toolSemaphore = new Semaphore(3);
        this.planExecutePrompts = planExecutePrompts;
        this.chatMemory = chatMemory;
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
        /** 大模型客户端，必填 */
        private ChatModel chatModel;
        /** 工具清单 */
        private List<ToolCallback> tools = new ArrayList<>();

        // 默认迭代5轮
        private int maxRounds = 5;

        // 默认context压缩阈值50000字符
        private int contextCharLimit = 50000;

        // 默认工具重试次数2次
        private int maxToolRetries = 2;

        /** 各环节提示词，为 null 时使用默认提示词 */
        private PlanExecutePromptsFactory planExecutePrompts;

        /** 会话记忆，为 null 时不启用多轮对话 */
        private ChatMemory chatMemory;

        /**
         * 设置会话记忆
         *
         * @param chatMemory 会话记忆
         * @return this
         */
        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
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
         * @param tools 工具回调列表
         * @return this
         */
        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
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
         * 设置最大迭代轮数
         *
         * @param maxRounds 轮数，<=0 表示不限制
         * @return this
         */
        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        /**
         * 设置上下文压缩阈值
         *
         * @param contextCharLimit 字符数
         * @return this
         */
        public Builder contextCharLimit(int contextCharLimit) {
            this.contextCharLimit = contextCharLimit;
            return this;
        }

        /**
         * 设置单个任务的最大重试次数
         *
         * @param maxToolRetries 次数
         * @return this
         */
        public Builder maxToolRetries(int maxToolRetries) {
            this.maxToolRetries = maxToolRetries;
            return this;
        }

        /**
         * 设置各环节提示词
         *
         * @param planExecutePrompts 提示词集合，为 null 时使用默认
         * @return this
         */
        public Builder planExecutePrompts(PlanExecutePromptsFactory planExecutePrompts) {
            this.planExecutePrompts = planExecutePrompts;
            return this;
        }

        /**
         * 构建 Agent
         *
         * @return PlanExecuteAgent 实例
         */
        public PlanExecuteAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");
            return new PlanExecuteAgent(chatModel, tools, maxRounds, contextCharLimit, maxToolRetries, planExecutePrompts, chatMemory);
        }
    }

    /**
     * 非流式问答（不带会话记忆）
     *
     * @param question 用户问题
     * @return 最终答案
     */
    public String call(String question) {
        return callInternal(null, question);
    }

    /**
     * 非流式问答（带会话记忆）
     *
     * @param conversationId 会话 ID
     * @param question       用户问题
     * @return 最终答案
     */
    public String call(String conversationId, String question) {
        return callInternal(conversationId, question);
    }

    /**
     * Plan-Execute 主循环
     *
     * @param conversationId 会话 ID，为 null 时不启用记忆
     * @param question       用户问题
     * @return 最终答案
     */
    public String callInternal(String conversationId, String question) {

        // 只有同时具备会话 ID 和记忆实现才启用多轮记忆
        boolean useMemory = conversationId != null && chatMemory != null;

        // 全局状态：承载本轮对话的全部消息与轮次计数
        OverAllState state = new OverAllState(conversationId, question);

        // 加载历史记忆到上下文messages中
        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (!CollectionUtils.isEmpty(history)) {
                history.forEach(state::add);
            }
        }

        // 当前用户问题
        state.add(new UserMessage(question));

        // 当前问题存入memory
        if (useMemory) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        // maxRounds <= 0 表示不限制轮数
        while (maxRounds <= 0 || state.getRound() < maxRounds) {
            state.nextRound();
            log.info("===== Plan-Execute Round {} =====", state.getRound());

            // 1.生成计划
            List<PlanTask> plan = generatePlan(state);
            log.info("【Execution Plan】\n\n" + plan);
            // 计划也写入上下文，供下一轮规划与最终总结参考
            state.add(new AssistantMessage("【Execution Plan】\n" + plan));

            // 计划为空或所有任务都没有 id → 模型认为无需执行，直接进入总结
            if (plan.isEmpty() || plan.stream().allMatch(t -> t.id() == null)) {
                log.info("===== No execution needed, direct answer =====");
                break;
            }

            // 2.执行
            Map<String, TaskResult> results = executePlan(plan, state);

            // 3.批判
            CritiqueResult critique = critique(state);

//            state.addRound(new PlanRoundState(
//                    state.getRound(), plan, results, critique
//            ));

            if (critique.passed()) {
                log.info("===== Goal satisfied, finish =====");
                break;
            }
            log.info("===== critique Goal not satisfied, continue round =====,\n reason is {} ", critique.feedback);
            // 批判不通过：把意见写入上下文，下一轮规划时模型能带着意见重新拆解任务
            state.add(new AssistantMessage("""
                    【Critique Feedback】
                    %s
                    """.formatted(critique.feedback())));
            // 4. 压缩context
            compressIfNeeded(state);
        }
        // 轮次刚好用尽（未提前通过批判）→ 强制收尾
        if (state.round == maxRounds)
            log.info("===== Max rounds reached, force finish =====");

        // 5.总结输出
        return summarize(state);
    }

    /**
     * 生成执行计划
     * <p>把当前上下文、工具清单、输出格式告知模型，要求其输出 JSON 格式的任务列表。</p>
     *
     * @param state 全局状态
     * @return 任务列表
     */
    private List<PlanTask> generatePlan(OverAllState state) {

        // 工具说明仅供规划参考，不直接调用
        String toolDesc = renderToolDescriptions();
        // 结构化输出转换器：把模型输出的 JSON 解析成 List&lt;PlanTask&gt;
        BeanOutputConverter<List<PlanTask>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                // 系统提示词 = 固定头部（时间、轮次、工具、格式）+ 可配置的规划提示词
                new SystemMessage("""
                            当前时间是：%s。

                            当前是迭代的第 %s 轮次。

                            ## 可用工具说明（仅用于规划参考）
                            %s

                            ## 输出format
                            %s

                        """.formatted(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), state.round, toolDesc, converter.getFormat())
                        + PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getPlanPrompt()),
                // 用户消息放对话历史，让模型知道已经做过什么、还差什么
                new UserMessage("【对话历史】\n\n" + renderMessages(state.getMessages()))
        ));

        String json = chatModel.call(prompt).getResult().getOutput().getText();

        // 解析为任务列表；模型未按 JSON 输出时这里会抛转换异常
        List<PlanTask> planTasks = converter.convert(json);
        return planTasks;
    }

    /**
     * 渲染工具清单描述
     * <p>把 ToolCallback 的名称与描述拼成 "- name: description" 列表，供规划提示词使用。</p>
     *
     * @return 工具描述文本
     */
    private String renderToolDescriptions() {
        if (tools == null || tools.isEmpty()) {
            return "（当前无可用工具）";
        }

        StringBuilder sb = new StringBuilder();
        for (ToolCallback tool : tools) {
            sb.append("- ")
                    .append(tool.getToolDefinition().name())
                    .append(": ")
                    .append(tool.getToolDefinition().description())
                    .append("\n");
        }
        return sb.toString();
    }


    /**
     * 按计划执行任务
     * <p>并发模型：任务先按 {@code order} 分组，同 order 的任务并行执行（CompletableFuture 异步），
     * 不同 order 之间串行；进入后一个 order 前会把前序成功任务的产出渲染成快照，
     * 作为「已有结果」传给任务，避免重复调用工具。</p>
     * <p>并发上限由 {@code toolSemaphore}（许可数 3）控制，防止同时打爆下游工具服务。</p>
     *
     * @param plan  任务列表
     * @param state 全局状态，任务结果会追加到其中
     * @return taskId → 执行结果
     */
    private Map<String, TaskResult> executePlan(List<PlanTask> plan, OverAllState state) {

        // 多线程写入，用 ConcurrentHashMap 保证线程安全
        Map<String, TaskResult> results = new ConcurrentHashMap<>();

        // 按 order 分组：order 相同的 task 可并行
        Map<Integer, List<PlanTask>> grouped =
                plan.stream().collect(Collectors.groupingBy(PlanTask::order));

        // 累积成功任务的输出，供后续 order 的任务作为依赖
        Map<String, String> accumulatedResults = new ConcurrentHashMap<>();

        // 按 order 顺序执行（不同 order 串行）
        for (Integer order : new TreeSet<>(grouped.keySet())) {

            // 保存当前工具执行快照
            String dependencySnapshot = renderDependencySnapshot(accumulatedResults);

            List<PlanTask> tasks = grouped.get(order);

            List<CompletableFuture<Void>> futures = tasks.stream()
                    .map(task -> CompletableFuture.runAsync(() -> {

                        try {
                            // 获取执行许可
                            toolSemaphore.acquire();
                            if (task == null || StringUtils.isBlank(task.id())) {
                                return;
                            }
                            TaskResult result = executeWithRetry(task, dependencySnapshot);
                            results.put(task.id(), result);

                            if (result.success() && result.output() != null) {
                                accumulatedResults.put(task.id(), result.output());
                            }

                            // 任务结果回写上下文，供批判与总结环节读取
                            // 注意：state.messages 是 ArrayList，此处为多线程并发写入，存在线程安全隐患
                            state.add(new AssistantMessage("""
                                    【Completed Task Result】
                                    taskId: %s
                                    success: %s
                                    result:
                                    %s
                                    error:
                                    %s
                                    【End Task Result】
                                    """.formatted(
                                    task.id(),
                                    result.success(),
                                    result.output(),
                                    result.error()
                            )));

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();

                            results.put(task.id(),
                                    new TaskResult(
                                            task.id(),
                                            false,
                                            null,
                                            "Task execution interrupted"
                                    ));
                        } finally {
                            // 释放许可
                            toolSemaphore.release();
                        }

                    }))
                    .toList();

            // 等待当前order组全部完成
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            ).join();
        }

        return results;
    }


    /**
     * 执行单个任务，失败自动重试
     * <p>每次尝试都会新建一个 {@link SimpleReactAgent}，由它在 maxRounds=5 的 ReAct 循环内
     * 自行决定调用哪些工具；{@code dependencySnapshot} 作为「已有结果」一并传入，
     * 让模型不必重复执行前置任务。</p>
     *
     * @param task               待执行任务
     * @param dependencySnapshot 前序任务的产出快照
     * @return 执行结果；全部尝试失败时 success=false，error 为最后一次异常信息
     */
    private TaskResult executeWithRetry(PlanTask task, String dependencySnapshot) {

        int attempt = 0;
        Throwable lastError = null;

        // 最多尝试 maxToolRetries 次
        while (attempt < maxToolRetries) {
            attempt++;
            try {
                // 每个任务独立跑一个 ReAct Agent，把「可用结果 + 当前任务」交给它
                SimpleReactAgent agent = SimpleReactAgent.builder()
                        .chatModel(chatModel)
                        .tools(tools)
                        .maxRounds(5)
                        .systemPrompt(PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getExecutePrompt())
                        .build();

                String result = agent.call("""
                        【Available Results】
                        %s
                        
                        【Current Task】
                        %s
                        """.formatted(
                        dependencySnapshot.isBlank() ? "NONE" : dependencySnapshot,
                        task.instruction
                ));

                return new TaskResult(task.id(), true, result, null);
            } catch (Exception e) {
                lastError = e;
                log.warn("Task {} failed attempt {}/{}", task.id(), attempt, maxToolRetries, e);
            }
        }

        return new TaskResult(
                task.id(),
                false,
                null,
                lastError == null ? "unknown error" : lastError.getMessage()
        );
    }

    /**
     * 把已完成任务的产出渲染成文本快照
     * <p>用于给后续任务提供「前面查到了什么」，避免重复调用工具。</p>
     *
     * @param results taskId → 输出
     * @return 形如 "- taskId: xxx\n  output:\n..." 的文本；无结果时返回空串
     */
    private String renderDependencySnapshot(Map<String, String> results) {

        if (results.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        results.forEach((taskId, output) -> {
            sb.append("- taskId: ")
                    .append(taskId)
                    .append("\n")
                    .append("  output:\n")
                    .append(output)
                    .append("\n\n");
        });

        return sb.toString();
    }


    /**
     * 批判环节：判断当前结果是否已达成目标
     *
     * @param state 全局状态
     * @return 批判结论；passed=true 表示目标达成，可结束循环
     */
    private CritiqueResult critique(OverAllState state) {

        BeanOutputConverter<CritiqueResult> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getCritiquePrompt()),
                new UserMessage(renderMessages(state.getMessages()))
        ));
        String raw = chatModel.call(prompt).getResult().getOutput().getText();

        return converter.convert(raw);
    }

    /**
     * 上下文压缩
     * <p>当消息总字符数超过阈值时，让模型把现有上下文总结成一份快照，
     * 并用该快照替换全部消息，避免 token 随轮次不断膨胀。</p>
     *
     * @param state 全局状态
     */
    private void compressIfNeeded(OverAllState state) {

        // 未超过阈值则无需压缩
        if (state.currentChars() < contextCharLimit) {
            return;
        }

        log.warn("===== Context too large, compressing ,size is {} =====", state.currentChars());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("""                             
                             ## 最大压缩限制（必须遵守）
                             - 你输出的最终内容【总字符数（包含所有标签、空格、换行）】
                                不得超过：%s
                             - 这是硬性上限，不是建议
                             - 如超过该限制，视为压缩失败
                        
                        """.formatted(contextCharLimit) + PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getCompressPrompt()),

                new UserMessage(renderMessages(state.getMessages()))
        ));

        String snapshot = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getText();

        // 用压缩快照替换全部历史消息
        state.clearMessages();
        state.add(new SystemMessage("【Compressed Agent State】\n" + snapshot));
        log.warn("===== Context compress has completed, size is {} =====", state.currentChars());
    }


    /**
     * 总结输出：基于原始问题 + 全部执行上下文产出最终答案
     *
     * @param state 全局状态
     * @return 最终答案
     */
    private String summarize(OverAllState state) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(PlanExecutePromptsFactory.buildPrompts(planExecutePrompts).getSummarizePrompt()),
                new UserMessage("""
                        【用户原始问题】
                        %s
                        
                        【执行上下文（含工具结果）】
                        %s
                        """.formatted(
                        state.getQuestion(),
                        renderMessages(state.getMessages())
                ))
        ));

        String answer = chatModel.call(prompt).getResult().getOutput().getText();
        // 追加记忆
        if (state.conversationId != null && chatMemory != null) {
            chatMemory.add(state.conversationId, new AssistantMessage(answer));
        }
        return answer;
    }

    /**
     * 把消息列表渲染成带类型标记的纯文本
     *
     * @param messages 消息列表
     * @return 形如 "[USER]\n内容" 的文本
     */
    private String renderMessages(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append("\n\n[").append(m.getMessageType()).append("]\n\n")
                    .append(m.getText());
        }
        return sb.toString();
    }

    // 内部对象实体
    /**
     * 计划中的一个任务
     *
     * @param id          任务标识，用于在执行结果中回填
     * @param instruction 任务指令（自然语言）
     * @param order       执行顺序，相同 order 的任务可并行
     */
    public record PlanTask(String id, String instruction, int order) {
    }

    /**
     * 批判结论
     *
     * @param passed  是否达标
     * @param feedback 未达标时给出的改进建议
     */
    public record CritiqueResult(boolean passed, String feedback) {
    }

    /**
     * 全局状态：承载一次问答的全部上下文与轮次
     */
    @Getter
    public static class OverAllState {

        /** 会话 ID，为 null 表示不启用记忆 */
        private final String conversationId;
        /** 用户原始问题 */
        private final String question;
        /** 上下文消息：历史 + 计划 + 工具结果 + 批判意见 */
        private final List<Message> messages = new ArrayList<>();
        /** 当前迭代轮次 */
        private int round = 0;

        public OverAllState(String conversationId, String question) {
            this.question = question;
            this.conversationId = conversationId;
        }

        /** 轮次 +1 */
        public void nextRound() {
            round++;
        }

        /** 追加一条上下文消息 */
        public void add(Message m) {
            messages.add(m);
        }

        /** 当前上下文总字符数，用于判断是否需要压缩 */
        public int currentChars() {
            return messages.stream()
                    .mapToInt(m -> m.getText() == null ? 0 : m.getText().length())
                    .sum();
        }

        /** 清空上下文（压缩时用） */
        public void clearMessages() {
            messages.clear();
        }
    }

//    public record PlanRoundState(
//            int round,
//            List<PlanTask> plan,
//            Map<String, TaskResult> results,
//            CritiqueResult critique
//    ) {
//    }

    /**
     * 任务执行结果
     *
     * @param taskId  任务标识
     * @param success 是否成功
     * @param output  成功时的输出
     * @param error   失败时的错误信息
     */
    public record TaskResult(
            String taskId,
            boolean success,
            String output,
            String error
    ) {
    }

    public static void main(String[] args) {
        // 手动构建模型客户端
        ChatModel chatModel = ChatModelConfig.getChatModel();

        // 把工具对象包装成 Spring AI 的 ToolCallback
        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());

        // 滑动窗口记忆，最多保留 20 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        PlanExecuteAgent agent = PlanExecuteAgent.builder()
                .chatModel(chatModel)
                .tools(toolCallbacks)
                .maxRounds(3)
                .maxToolRetries(2)
                .chatMemory(chatMemory)
                .contextCharLimit(1000).build();

        String result = agent.call("""
                请你先查询北京今天的天气，再搜索本周末北京天气的预警情况，并基于本周末北京的天气预警情况，搜索北京本周末适合旅游打卡的景点有哪些，最终生成一份不少于 500 字的综合天气分析报告。
                """);

        System.out.println("\n===== FINAL ANSWER =====");
        System.out.println(result);

//        System.out.println(agent.call("123", "我的名字叫bigchui"));
//        System.out.println(agent.call("123", "我的名字是什么？"));
    }
}
