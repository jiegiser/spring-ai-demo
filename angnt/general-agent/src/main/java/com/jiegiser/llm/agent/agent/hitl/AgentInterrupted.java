package com.jiegiser.llm.agent.agent.hitl;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * Agent 被人工审批中断的结果
 * <p>中断时保留了两份可恢复现场：完整消息列表（检查点）与上下文 Map，
 * 审批完成后交给 {@code HITLReactAgent#resume} 继续执行，无需从头重跑。</p>
 *
 * @param pendingToolCalls  待审批的工具调用列表
 * @param checkpointMessages 中断时刻的完整消息上下文（快照）
 * @param context           中断时刻的上下文数据，resume 时原样带回
 */
public record AgentInterrupted(List<PendingToolCall> pendingToolCalls,
                               List<Message> checkpointMessages,
                               Map<String, Object> context) implements AgentResult {
}
