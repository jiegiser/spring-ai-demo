package com.jiegiser.llm.agent.agent;

import com.jiegiser.llm.agent.config.ChatModelConfig;
import com.jiegiser.llm.agent.tools.SearchService;
import com.jiegiser.llm.agent.tools.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class SimpleReactAgent {

    /**
     * ReAct 模式系统提示词
     * <p>约束模型：需要外部信息时必须通过工具调用字段输出，工具调用消息要是原子性的纯结构化内容，
     * 不允许把调用意图写进 content；上下文齐备时直接输出自然语言最终答案。</p>
     * <p>它与构造参数中的 systemPrompt 叠加生效：前者是 ReAct 通用协议，后者是业务角色设定。</p>
     */
    public static final String REACT_AGENT_SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct 模式的智能 AI 助手，会通过 Reasoning → Act(ToolCall) → Observation 的反复循环来逐步解决任务。

            ## 工具调用规则（极其重要）
            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，并且 **只能通过工具调用字段输出**。
            2. 工具调用时：**禁止在 content 中出现任何形式的工具调用文本**（包括 JSON、<tool_call>、函数名、参数、思考、推理或描述）。
            3. 工具调用消息必须是一次性、原子性输出，不得混杂任何解释或内容。
            4. 工具调用前后不得输出任何多余文字、标签、换行、推理轨迹或说明。
            5. 调用工具时：
               -工具参数必须是有效的JSON
               -参数必须简洁，不超过500个字符
               -切勿包含以前的工具结果、原始内容、HTML或长文本
               -仅包括工具所需的最小控制参数

            ## 工具执行结果
            系统会自动将工具执行结果作为 ToolResponseMessage 注入上下文，你只需读取并决定下一步动作。

            ## 最终答案规则
            1. 如果上下文已经拥有了完成任务的全部信息，则不要再调用任何工具。
            2. 在这种情况下，你必须输出最终自然语言答案，且 **禁止包含任何工具调用格式**。
            3. 最终答案只允许是自然语言，不能包含 JSON、思考过程、reasoning、ToolCall 或伪代码。

            ## 强制要求（必须遵守）
            1. 工具调用消息必须只通过 ToolCall 字段输出，不允许在 content 字段体现工具调用迹象。
            2. 如果本轮没有工具调用，则视为任务完成，你必须输出最终答案。
            3. 不允许重复调用同一个工具（名称 + 参数完全一致），除非工具调用失败。
            4. 禁止输出会干扰工具系统解析的任何结构（如 <reason>、<ToolCall>、函数 JSON、或模型内部思考）。
            5. 如果上下文已经包含了完成任务的全部信息，则不要再调用任何工具。
            """;

    /** Agent 名称，便于日志与多实例区分 */
    private final String name;
    /** 底层大模型客户端，由外部注入 */
    private final ChatModel chatModel;
    /** 可调用的工具清单（天气、搜索等），ReAct 的 Act 环节从这里取 */
    private final List<ToolCallback> tools;
    /** 业务角色提示词，与 REACT_AGENT_SYSTEM_PROMPT 一起作为 SystemMessage 下发 */
    private final String systemPrompt;
    /** 装配好的 ChatClient，由 initChatClient() 在构造期创建 */
    private ChatClient chatClient;
    /** 最大推理轮数，<=0 表示不限制；超过后强制让模型基于现有上下文给出最终答案 */
    private int maxRounds;
    /** 会话记忆，为 null 时不启用多轮对话 */
    private ChatMemory chatMemory;

    /**
     * 功能增强拦截器（如 Reflection 反思 Advisor）
     * <p>由外部传入并挂到 ChatClient 的 defaultAdvisors 上，Advisor 可在调用前后改写请求/响应，
     * 例如把"本轮回答质量不足"的判断结果写入 chatResponse.context()。</p>
     */
    private List<Advisor> advisors;

    /**
     * 最大反思轮数
     * <p>当 Advisor 在 context 中标记 reflection.required=true 时，Agent 会把反思反馈注入上下文重新规划，
     * 超过该轮数则直接输出当前结论。</p>
     */
    private int maxReflectionRounds;

    /**
     * 构造 Agent
     *
     * @param name               Agent 名称
     * @param chatModel          大模型客户端
     * @param tools              工具清单
     * @param systemPrompt       业务角色提示词
     * @param maxRounds          最大推理轮数，<=0 不限制
     * @param chatMemory         会话记忆，可为 null
     * @param advisors           功能增强拦截器，可为 null
     * @param maxReflectionRounds 最大反思轮数，<=0 表示不启用反思
     */
    public SimpleReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools, String systemPrompt, int maxRounds, ChatMemory chatMemory, List<Advisor> advisors, int maxReflectionRounds) {
        this.name = name;
        this.chatModel = chatModel;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;

        this.maxReflectionRounds = maxReflectionRounds;
        this.advisors = advisors;
        initChatClient();

        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败！");
        }
    }

    /**
     * 初始化 ChatClient
     * <p>关键点：internalToolExecutionEnabled(false) 关闭框架自带的工具自动执行，
     * 工具必须由本类在 ReAct 循环里手动调用——否则模型一次只能拿到工具结果、无法多轮串联多个工具。</p>
     */
    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    // 关闭内部自动执行：工具执行时机由 Agent 自己控制
                    .internalToolExecutionEnabled(false)
                    .build();

            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (!CollectionUtils.isEmpty(advisors)) {
                builder.defaultAdvisors(advisors);
            }
            // 既通过 defaultOptions 注册，又通过 defaultToolCallbacks 注册，保证工具对模型可见
            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 非流式输出（不带会话记忆）
     *
     * @param question 用户问题
     * @return 最终答案文本
     */
    public String call(String question) {
        return callInternal(null, question);
    }

    /**
     * 非流式输出（带会话记忆）
     *
     * @param conversationId 会话 ID，用于从 chatMemory 读取/写入历史
     * @param question       用户问题
     * @return 最终答案文本
     */
    public String call(String conversationId, String question) {
        return callInternal(conversationId, question);
    }

    /**
     * ReAct 循环主体（非流式）
     * <p>循环每轮做三件事：把完整消息列表发给模型 → 判断返回是否含工具调用 → 有则执行工具并把结果写回消息列表，无则作为最终答案返回。</p>
     *
     * @param conversationId 会话 ID，为 null 时不启用记忆
     * @param question       用户问题
     * @return 最终答案文本
     */
    public String callInternal(String conversationId, String question) {
        // 消息列表会被工具执行线程读写，故使用同步包装
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        // 只有同时具备会话 ID 和记忆实现才启用多轮记忆
        boolean useMemory = conversationId != null && chatMemory != null;

        // 先下 ReAct 通用协议，再下业务角色提示词，两条 SystemMessage 均保留
        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        // ===== 加载历史记忆 =====
        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
        }

        // 用 <question> 标签包裹，明确用户问题的边界，避免与系统提示词混淆
        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            // 记忆中存原始问题，不存带标签的包装形式
            chatMemory.add(conversationId, new UserMessage(question));
        }

        // 已执行的推理轮数
        int round = 0;

        // 已执行的反思轮数
        int reflectionRound = 0;

        while (true) {
            round++;
            // 超过轮次上限：不再给模型工具机会，强制基于现有上下文收尾
            if (maxRounds > 0 && round > maxRounds) {
                log.warn("=== 达到 maxRounds（{}），强制生成最终答案 ===", maxRounds);
                messages.add(new UserMessage("""
                        你已达到最大推理轮次限制。
                        请基于当前已有的上下文信息，
                        直接给出最终答案。
                        禁止再调用任何工具。
                        如果信息不完整，请合理总结和说明。
                        """));

                String finalText = chatClient.prompt().messages(messages).call().content();
                if (useMemory) {
                    chatMemory.add(conversationId, new AssistantMessage(finalText));
                }
                return finalText;
            }

            // 每轮都把完整消息列表（含历史、工具调用、工具结果）重新发给模型
            ChatClientResponse chatResponse = chatClient
                    .prompt()
                    .messages(messages)
                    .call()
                    .chatClientResponse();

            // 本轮返回的文本内容
            String aiText = chatResponse.chatResponse().getResult().getOutput().getText();

            // 后续要把本轮的文本和工具调用合并成一条 AssistantMessage
            AssistantMessage.Builder builder = AssistantMessage.builder().content(aiText);

            // ===== 没有工具调用，视为最终答案 =====
            if (!chatResponse.chatResponse().hasToolCalls()) {
                // Advisor 判断本轮质量不足、需要反思时，走反思分支而不是直接返回
                if (maxReflectionRounds > 0 && Boolean.TRUE.equals(chatResponse.context().get("reflection.required"))) {
                    // 反思轮数用完，直接以当前文本收尾
                    if (reflectionRound >= maxReflectionRounds) {
                        log.warn("======= Reflection 最大轮次已达，直接输出结论 =======");
                        if (useMemory) {
                            chatMemory.add(conversationId, new AssistantMessage(aiText));
                        }
                        return aiText;
                    }
                    reflectionRound++;
                    log.info("===== 当前反思机制，第 {} 轮次 =====", reflectionRound);

                    // 反思意见由 Advisor 写入 context
                    String feedback = (String) chatResponse.context().get("reflection.feedback");

                    // 注入反思反馈，引导模型重新规划
                    messages.add(new AssistantMessage("""
                            【Reflection Feedback】
                            %s

                            请你根据以上反思意见重新规划任务，
                            必要时可以重新调用工具，
                            然后再给出最终答案。
                            """.formatted(feedback)));

                    // 重新走一轮推理（注意：轮数 round 不会重置）
                    continue;
                }

                // 正常终态：写入记忆后返回
                if (useMemory) {
                    chatMemory.add(conversationId, new AssistantMessage(aiText));
                }

                return aiText;
            }

            // ===== 有工具调用：执行工具 =====
            // 先把本轮的助手消息（含 tool_calls）写回上下文，保证后续消息的顺序合法
            messages.add(builder.toolCalls(chatResponse.chatResponse().getResult().getOutput().getToolCalls()).build());

            // 逐个执行工具，每个工具结果都作为 ToolResponseMessage 追加到上下文
            chatResponse.chatResponse()
                    .getResult()
                    .getOutput()
                    .getToolCalls()
                    .forEach(toolCall -> {
                        // 模型请求调用的工具名与参数 JSON
                        String toolName = toolCall.name();
                        String argsJson = toolCall.arguments();

                        // 按名称从工具清单中匹配回调
                        ToolCallback callback = findTool(toolName);
                        if (callback == null) {
                            // 工具不存在也要回一条 ToolResponse，否则模型会一直等待工具结果
                            addErrorToolResponse(messages, toolCall, "工具未找到：" + toolName);
                            return;
                        }

                        Object result;
                        try {
                            // 手动执行工具（框架内部自动执行已关闭）
                            result = callback.call(argsJson);
                            ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, result.toString());

                            // 工具结果回填上下文，模型下一轮即可看到 Observation
                            messages.add(ToolResponseMessage.builder().responses(List.of(tr)).build());
                        } catch (Exception ex) {
                            // 工具异常同样以 ToolResponse 形式回填，让模型能感知失败并重试
                            addErrorToolResponse(messages, toolCall, "工具执行失败：" + ex.getMessage());
                        }
                    });
        }
    }

    /******************************************* 下面是流式处理相关代码 ******************************************************/
    /**
     * 运行模式：未知、最终答案、工具调用
     * <p>实际流转只会出现 UNKNOWN → TOOL_CALL 或 UNKNOWN → FINAL_ANSWER 两种路径。</p>
     */
    private enum RoundMode {
        UNKNOWN,
        FINAL_ANSWER,
        TOOL_CALL
    }

    /**
     * 单轮流式响应的状态容器
     * <p>mode 记录本轮模式；textBuffer 累积本轮已下发的文本；toolCalls 收集本轮合并后的工具调用。
     * 流式下模型可能把工具名和参数分片下发，所以 toolCalls 需要按 id 合并。</p>
     */
    private static class RoundState {
        /** 本轮模式 */
        RoundMode mode = RoundMode.UNKNOWN;

        /** 本轮已下发给客户端的文本 */
        StringBuilder textBuffer = new StringBuilder();
        /** 本轮合并后的工具调用列表 */
        List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
    }


    /**
     * 流式输出（不带会话记忆）
     *
     * @param question 用户问题
     * @return 文本分片流
     */
    public Flux<String> stream(String question) {
        return streamInternal(null, question);
    }

    /**
     * 流式输出（带会话记忆）
     *
     * @param conversationId 会话 ID，用于从 chatMemory 读取/写入历史
     * @param question       用户问题
     * @return 文本分片流
     */
    public Flux<String> stream(String conversationId, String question) {
        return streamInternal(conversationId, question);
    }


    /**
     * 流式 ReAct 循环入口
     * <p>与 callInternal 的区别：单轮响应不阻塞等待，而是通过 Sink 边收边下发；
     * 工具调用在流式下需要额外合并分片、并行执行。</p>
     *
     * @param conversationId 会话 ID，为 null 时不启用记忆
     * @param question       用户问题
     * @return 文本分片流
     */
    public Flux<String> streamInternal(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;

        messages.add(new SystemMessage(REACT_AGENT_SYSTEM_PROMPT));
        messages.add(new SystemMessage(systemPrompt));

        // ===== 加载历史记忆 =====
        if (useMemory) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
        }

        messages.add(new UserMessage("<question>" + question + "</question>"));

        // 添加记忆
        if (useMemory) {
            chatMemory.add(conversationId, new UserMessage(question));
        }

        // 单消费者 Sink：整个流只允许一个订阅者，用于向客户端推送文本分片
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 迭代轮次
        AtomicLong roundCounter = new AtomicLong(0);
        // 是否发送最终结果标记位
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false);

        hasSentFinalResult.set(false);
        roundCounter.set(0);

        // 收集最终答案，存储 memory
        StringBuilder finalAnswerBuffer = new StringBuilder();

        // 启动第一轮，后续轮次由 finishRound 递归调度
        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId);

        return sink.asFlux()
                // 收集最终答案
                .doOnNext(finalAnswerBuffer::append)
                // 客户端取消订阅时置位，避免后台继续调度下一轮
                .doOnCancel(() -> hasSentFinalResult.set(true))
                .doFinally(signalType -> {
                    log.info("最终答案: {}", finalAnswerBuffer);
                });
    }

    /**
     * 调度一轮流式推理
     * <p>发起一次流式调用，逐块处理响应；流结束后由 finishRound 判断是最终答案还是需要执行工具。</p>
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter, AtomicBoolean hasSentFinalResult,
                               StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId) {
        // 轮次 +1
        roundCounter.incrementAndGet();
        // 本轮独立的状态容器
        RoundState state = new RoundState();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                // 切换到 boundedElastic 线程执行回调，避免阻塞 Reactor 事件循环线程
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter, hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId))
                .doOnError(err -> {
                    // 出错时直接终止流，且保证只终止一次
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();
    }

    /**
     * 处理单个流式响应分片
     * <p>分片可能只带文本、也可能只带工具调用（或两者混合）；一旦出现工具调用就切到 TOOL_CALL 模式，
     * 此后的文本不再下发（避免把模型的工具调用说明泄露给用户）。</p>
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {

        // 防御性判空：部分模型会返回空分片
        if (chunk == null || chunk.getResult() == null ||
                chunk.getResult().getOutput() == null) return;

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        // 一旦发现 tool_call，立即进入 TOOL_CALL 模式
        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;

            // 工具调用可能分片下发，按 id 合并
            for (AssistantMessage.ToolCall incoming : tc) {
                mergeToolCall(state, incoming);
            }
            return;
        }

        // 还没出现 tool_call，发送并缓存文本
        if (text != null) {
            sink.tryEmitNext(text);
            state.textBuffer.append(text);
        }
    }

    /**
     * 合并分片到达的工具调用
     * <p>同一个工具 id 的多次分片需要把参数拼接起来；id 不同则是新的工具调用，直接追加。</p>
     */
    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {

        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);

            // id 相同：累加参数片段
            if (existing.id().equals(incoming.id())) {

                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");

                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs)
                );
                return;
            }
        }

        // 新 tool call
        state.toolCalls.add(incoming);
    }


    /**
     * 单轮流式响应结束后的收尾处理
     * <p>两种出口：本轮没有工具调用 → 作为最终答案终止流；本轮有工具调用 → 执行工具后调度下一轮。</p>
     */
    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state, AtomicLong roundCounter,
                             AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer, boolean useMemory, String conversationId) {

        // 如果整轮都没有 tool_call，才是最终答案
        if (state.mode != RoundMode.TOOL_CALL) {
            String finalText = state.textBuffer.toString();
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);

            if (useMemory) {
                chatMemory.add(conversationId, new AssistantMessage(finalText));
            }
            return;
        }

        // 达到轮次上限：强制收尾（注意：此处用 >=，与非流式的 > 口径略有差异）
        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(conversationId, useMemory, messages, sink, hasSentFinalResult);
            return;
        }

        // TOOL_CALL
        // 把本轮的助手消息（含 tool_calls）写回上下文，保证后续消息顺序合法
        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();

        messages.add(assistantMsg);

        // 并行执行本轮所有工具，全部完成后回调调度下一轮
        executeToolCalls(state.toolCalls, messages, hasSentFinalResult, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter,
                        hasSentFinalResult, finalAnswerBuffer,
                        useMemory, conversationId);
            }
        });
    }


    /**
     * 达到最大轮次后的强制收尾（流式）
     * <p>追加一条禁止调用工具的指令，再走一次流式调用把已有上下文总结成最终答案。</p>
     */
    private void forceFinalStream(String conversationId, boolean useMemory, List<Message> messages, Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult) {
        messages.add(new UserMessage("""
                你已达到最大推理轮次限制。
                请基于当前已有的上下文信息，
                直接给出最终答案。
                禁止再调用任何工具。
                如果信息不完整，请合理总结和说明。
                """));

        StringBuilder stringBuilder = new StringBuilder();

        chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }

                    String text = chunk.getResult()
                            .getOutput()
                            .getText();

                    if (text != null && !hasSentFinalResult.get()) {
                        sink.tryEmitNext(text);
                        stringBuilder.append(text);
                    }
                })
                .doOnComplete(() -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                    if (useMemory) {
                        chatMemory.add(conversationId, new AssistantMessage(stringBuilder.toString()));
                    }
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();
    }

    /**
     * 并行执行本轮工具调用
     * <p>每个工具在 boundedElastic 线程上执行（工具可能是 IO 密集的 HTTP 调用），
     * 全部完成后触发 onComplete 回调调度下一轮。</p>
     */
    private void executeToolCalls(List<AssistantMessage.ToolCall> toolCalls, List<Message> messages, AtomicBoolean hasSentFinalResult, Runnable onComplete) {
        // 已完成的工具计数，用于判断是否全部结束
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalToolCalls = toolCalls.size();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Schedulers.boundedElastic().schedule(() -> {
                // 流已终止（取消订阅或已发最终结果）时不再执行工具，直接计数收尾
                if (hasSentFinalResult.get()) {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                String toolName = tc.name();
                String argsJson = tc.arguments();

                ToolCallback callback = findTool(toolName);
                if (callback == null) {
                    addErrorToolResponse(messages, tc, "工具未找到：" + toolName);
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                    return;
                }

                try {
                    Object result = callback.call(argsJson);
                    String resultStr = Objects.toString(result, "");
                    ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                            tc.id(), toolName, resultStr);
                    // 工具结果回填上下文
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(tr))
                            .build());
                } catch (Exception ex) {
                    // 异常也以 ToolResponse 形式回填，保证 tool_call 与 tool_response 一一对应
                    addErrorToolResponse(messages, tc, "工具执行失败：" + ex.getMessage());
                } finally {
                    completeToolCall(completedCount, totalToolCalls, onComplete);
                }
            });
        }
    }

    /**
     * 工具执行完成计数
     * <p>全部工具都完成后触发 onComplete。</p>
     */
    private void completeToolCall(AtomicInteger completedCount, int total, Runnable onComplete) {
        int current = completedCount.incrementAndGet();
        if (current >= total) {
            onComplete.run();
        }
    }

    /**
     * 构造错误形式的工具响应
     * <p>工具失败也必须回一条 ToolResponse（而不是直接跳过），否则上下文里 tool_call 没有对应结果，
     * 模型会一直等待甚至报错。</p>
     */
    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );

        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    /**
     * 按工具名查找对应的回调
     *
     * @param name 工具名
     * @return 匹配的回调，找不到返回 null
     */
    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
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
        private String name;
        /** 大模型客户端，必填 */
        private ChatModel chatModel;
        /** 工具清单 */
        private List<ToolCallback> tools;
        /** 业务角色提示词，默认空串（此时仍会下发 ReAct 协议提示词） */
        private String systemPrompt = "";

        /** 最大反思轮数，<=0 表示不启用反思 */
        private int maxReflectionRounds;

        /** 最大推理轮数，<=0 表示不限制 */
        private int maxRounds;

        /** 功能增强拦截器 */
        private List<Advisor> advisors;

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
         * 设置功能增强拦截器
         *
         * @param advisors 拦截器列表
         * @return this
         */
        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors;
            return this;
        }

        /**
         * 设置功能增强拦截器
         *
         * @param advisors 拦截器
         * @return this
         */
        public Builder advisors(Advisor... advisors) {
            this.advisors = Arrays.asList(advisors);
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
         * @param maxReflectionRounds 轮数，<=0 表示不启用
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
         *
         * @return Agent 实例
         */
        public SimpleReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空！");
            }
            return new SimpleReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, advisors, maxReflectionRounds);
        }
    }

    /**
     * 演示入口：构造 Agent 并以流式方式执行一个多工具任务
     */
    public static void main(String[] args) {
        ChatModel chatModel = ChatModelConfig.getChatModel();

        // 把带 @Tool 注解的方法包装成工具回调
        ToolCallback[] toolCallbacks = ToolCallbacks.from(new WeatherService(), new SearchService());

        // 滑动窗口记忆，最多保留 20 条消息
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        SimpleReactAgent agent = SimpleReactAgent.builder()
                .name("simple-agent")
                .chatModel(chatModel)
                .tools(toolCallbacks)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();

        // 任务需要串联天气查询与搜索多个工具，正好演示 ReAct 多轮循环
        String question = """
                请你根据北京今天的天气、未来七天的天气趋势、以及上海今天的天气，并搜索北京天气的预警情况，生成一份不少于 600 字的综合分析报告。
                """;

//        System.out.println(agent.call(question));

        // 流式输出：逐块打印
        agent.stream(question).doOnNext(chuck -> {
            System.out.print(chuck);
        }).blockLast();
    }
}
