package com.jiegiser.llm.agent.agent.hitl;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 人工审批拦截器（Human-in-the-Loop Advisor）
 * <p>在模型给出工具调用后检查工具名：命中拦截名单时，
 * 不执行工具，而是把待审批信息写入 response 的 context，
 * 由 {@link HITLReactAgent} 读取后中断并返回给用户审批。</p>
 * <p>只拦截名单内的工具；其他工具照常执行。</p>
 */
public class HITLAdvisor implements CallAdvisor {

    /** context 键：标记本次响应需要人工介入 */
    public static final String HITL_REQUIRED = "hitl.required";
    /** context 键：存放待审批的工具调用列表 */
    public static final String HITL_PENDING_TOOLS = "hitl.pending.tools";
    /** context 键：存放 HITL 审批状态容器 */
    public static final String HITL_STATE_KEY = "hitl.state";

    /** 需要拦截（需人工审批）的工具名集合 */
    private final Set<String> interceptToolNames;

    /**
     * 构造拦截器
     *
     * @param interceptToolNames 需要人工审批的工具名集合
     */
    public HITLAdvisor(Set<String> interceptToolNames) {
        this.interceptToolNames = interceptToolNames;
    }

    /**
     * 拦截回调
     * <p>模型调用完成后才执行（事后拦截）：检查 tool_calls 是否命中名单，
     * 命中则写入 HITL_REQUIRED 与 HITL_PENDING_TOOLS 两个 context 标记。</p>
     *
     * @param chatClientRequest 本次请求
     * @param callAdvisorChain  拦截链
     * @return 原响应（context 中可能已带上待审批标记）
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        // 没有工具调用说明本轮就是最终答案，无需审批
        if (!response.chatResponse().hasToolCalls()) {
            return response;
        }

        List<PendingToolCall> pending = new ArrayList<>();

        for (AssistantMessage.ToolCall tc : response.chatResponse().getResult().getOutput().getToolCalls()) {

            // 不在拦截名单内的工具直接放行
            if (!interceptToolNames.contains(tc.name())) {
                continue;
            }

            // result 为 null 表示尚未审批
            pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments(), null, "该工具需要用户手动确认"));
        }

        // 没有命中任何需要审批的工具，正常返回
        if (pending.isEmpty()) {
            return response;
        }

        // 写入标记：HITLReactAgent 据此判断是否中断
        response.context().put(HITL_REQUIRED, true);
        response.context().put(HITL_PENDING_TOOLS, pending);

        return response;
    }

    /**
     * 拦截器名称
     *
     * @return 名称
     */
    @Override
    public String getName() {
        return "HITLAdvisor";
    }

    /**
     * 拦截器顺序
     * <p>值越小越先执行。</p>
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
