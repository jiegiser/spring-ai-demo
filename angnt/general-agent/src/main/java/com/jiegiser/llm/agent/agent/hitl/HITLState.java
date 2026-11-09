package com.jiegiser.llm.agent.agent.hitl;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工审批（HITL）状态容器
 * <p>记录哪些工具调用已经被人工处理过（consumed），用于 resume 时去重：
 * 同一个 tool_call id 不会被重复审批、重复执行。</p>
 * <p>使用 {@link ConcurrentHashMap#newKeySet()} 而非普通 HashSet，
 * 因为审批可能来自多个线程（并发审批场景）。</p>
 */
public class HITLState {

    /** 已消费（已完成审批）的工具调用 id 集合 */
    private final Set<String> consumedToolCallIds = ConcurrentHashMap.newKeySet();

    public HITLState() {
    }

    /**
     * 判断某个工具调用是否已被审批处理
     *
     * @param toolCallId 工具调用 id
     * @return true 表示已处理过
     */
    public boolean isConsumed(String toolCallId) {
        return consumedToolCallIds.contains(toolCallId);
    }

    /**
     * 标记工具调用已被审批处理
     *
     * @param toolCallId 工具调用 id
     */
    public void markConsumed(String toolCallId) {
        consumedToolCallIds.add(toolCallId);
    }
}
