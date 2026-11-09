package com.jiegiser.llm.agent.agent.hitl;

/**
 * Agent 正常结束的结果
 *
 * @param content 最终答案文本
 */
public record AgentFinished(String content) implements AgentResult {
}
