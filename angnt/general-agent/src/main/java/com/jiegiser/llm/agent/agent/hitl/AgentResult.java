package com.jiegiser.llm.agent.agent.hitl;

/**
 * Agent 执行结果的密封接口
 * <p>使用 sealed 接口保证结果只有两种终态，调用方可用模式匹配穷尽分支：</p>
 * <ul>
 *   <li>{@link AgentFinished}：正常结束，携带最终答案文本；</li>
 *   <li>{@link AgentInterrupted}：被人工审批拦截，携带待审批工具与上下文快照。</li>
 * </ul>
 */
public sealed interface AgentResult permits AgentFinished, AgentInterrupted {
}
