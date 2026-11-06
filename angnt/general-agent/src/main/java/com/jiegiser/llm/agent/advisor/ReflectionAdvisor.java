package com.jiegiser.llm.agent.advisor;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;

/**
 * 反思评估 Advisor
 * <p>实现 {@link CallAdvisor}，在模型给出答案之后做一次"审稿"：</p>
 * <ol>
 *   <li>先让 {@code chain.nextCall(request)} 正常执行，拿到本轮响应；</li>
 *   <li>若响应里含工具调用，说明这一轮还没产出最终答案，直接放过不评估；</li>
 *   <li>若已产出答案，则用另一个模型（反思模型）以审稿人视角判断答案是否合格；</li>
 *   <li>不合格时把结论写入 response 的 context：{@code reflection.required=true} 与 {@code reflection.feedback}，
 *       由 SimpleReactAgent 读取后重新规划。</li>
 * </ol>
 * <p>它是"事后"拦截器（Post-Processing），不改写请求，也不拦截模型输出，只附加判断结果。</p>
 */
@Slf4j
public class ReflectionAdvisor implements CallAdvisor {

    /**
     * 反思评估提示词
     * <p>强制模型只输出一个 JSON 对象，字段为 passed（是否通过）与 feedback（改进建议）。</p>
     */
    private static final String REFLECTION_PROMPT = """
            你是一个严格的智能体反思评估专家。

            请判断【当前回答】是否已经充分、准确地满足【用户问题】。

            评估标准：
            1. 信息是否完整
            2. 逻辑是否清晰
            3. 结论是否可靠、与上下文一致
            4. 表达是否符合最终交付质量

            【你必须且只能输出一个 JSON 对象，格式如下】

            {
              "passed": true | false,
              "feedback": "如果 passed=false，给出明确、可执行的改进建议，但是必须不要过长，控制在100字以内；如果 passed=true，值为 null"
            }

            禁止输出任何额外文本。
            """;

    /** 反思模型：专门用来给答案打分，可以与推理模型不同（当前实现传入的是同一个 ChatModel） */
    private final ChatModel reflectionModel;

    /** 结构化输出转换器：把模型输出的 JSON 解析成 ReflectionJudgement，同时提供格式说明用于拼提示词 */
    private final BeanOutputConverter<ReflectionJudgement> outputConverter = new BeanOutputConverter<>(ReflectionJudgement.class);

    /**
     * 构造反思 Advisor
     *
     * @param reflectionModel 用于评估答案质量的模型
     */
    public ReflectionAdvisor(ChatModel reflectionModel) {
        this.reflectionModel = reflectionModel;
    }

    /**
     * Advisor 拦截入口
     *
     * @param request 本次请求
     * @param chain   Advisor 链，调用 nextCall 继续向下执行
     * @return 原响应；若反思未通过，则附加 reflection.* 上下文标记
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {

        // 先执行后续链路（含真正调模型的那一步），拿到本轮响应
        ChatClientResponse response = chain.nextCall(request);

        // 含工具调用 = 这一轮只是中间步骤，还没有最终答案，不评估
        if (response.chatResponse() != null && response.chatResponse().hasToolCalls()) {
            return response;
        }

        // 防御性判空：部分模型会返回空结果
        if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
            return response;
        }

        // 本轮的答案文本
        String answer = response.chatResponse().getResult().getOutput().getText();

        // 从原始请求里取回用户问题，作为评估依据
        String question = extractQuestion(request.prompt());

        // 调反思模型评估这份答案
        ReflectionJudgement judgement = reflect(question, answer);

        // 评估通过：原样返回，Agent 直接把答案交付给用户
        if (judgement.passed()) {
            log.debug("=======Reflection 反思通过=======");
            return response;
        }

        log.info("=======Reflection 反思未通过, 需要agent重新执行=======reason: {}", judgement.feedback());

        // 评估不通过：写入 context 标记，由 SimpleReactAgent 检测后重新规划
        return response.mutate()
                .context("reflection.required", true)
                .context("reflection.feedback", judgement.feedback())
                .build();
    }

    /**
     * Advisor 名称
     *
     * @return 名称
     */
    @Override
    public String getName() {
        return "ReflectionAdvisor";
    }

    /**
     * Advisor 执行顺序
     * <p>用于多个 Advisor 时的排序，值越小越靠外层先执行。</p>
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 50;
    }

    /**
     * 调用反思模型评估答案
     *
     * @param question 用户问题
     * @param answer   待评估的回答
     * @return 评估结论
     */
    private ReflectionJudgement reflect(String question, String answer) {

        Prompt prompt = new Prompt(List.of(
                // 系统提示词定义角色与输出格式
                new SystemMessage(REFLECTION_PROMPT),
                // 用户消息把待评估的问题与答案拼进去，并附上 converter 生成的 JSON Schema
                new UserMessage("""
                        ## 用户问题：
                        %s

                        ## 当前回答：
                        %s
                                                
                        ## 输出format
                        %s
                                                
                        """.formatted(question, answer, outputConverter.getFormat()))
        ));

        // 拿到反思模型的原始文本输出
        String raw = reflectionModel.call(prompt).getResult().getOutput().getText();

        // 解析为结构化结论；若模型未按 JSON 输出，这里会抛出转换异常
        return outputConverter.convert(raw);
    }

    /**
     * 从提示词中提取用户问题
     * <p>取第一条 UserMessage 的文本。注意：启用会话记忆时，消息列表里可能存在更早的 UserMessage，
     * 此时取到的是历史消息而非本轮问题。</p>
     *
     * @param prompt 原始提示词
     * @return 用户问题文本，取不到时返回空串
     */
    private String extractQuestion(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).getText())
                .findFirst()
                .orElse("");
    }


    /**
     * 反思评估结论
     *
     * @param passed  是否通过评估
     * @param feedback 未通过时的改进建议（100 字以内）
     */
    public record ReflectionJudgement(@JsonProperty("passed") boolean passed,
                                      @JsonProperty("feedback") String feedback) {

    }
}
