package com.jiegiser.llm.agent.agent.hitl;

/**
 * 待人工审批的工具调用
 * <p>由 {@link HITLAdvisor} 在拦截到敏感工具时创建，交给前端展示，
 * 用户审批后通过 {@link #approve()} / {@link #reject(String)} 产生带审批结果的新实例。</p>
 *
 * @param id          工具调用 id（与模型返回的 tool_call id 对应）
 * @param name        工具名称
 * @param arguments   工具调用参数（JSON 字符串）
 * @param result      审批结果，null 表示尚未审批
 * @param description 说明信息：待审批时是提示语，拒绝时是拒绝原因
 */
public record PendingToolCall(String id, String name, String arguments, FeedbackResult result, String description) {

    /**
     * 人工审批结果
     */
    public enum FeedbackResult {
        /** 同意执行 */
        APPROVED,
        /** 拒绝执行 */
        REJECTED,
        /** 同意但修改了参数 */
        EDIT
    }

    /**
     * 生成「同意」的结果
     *
     * @return 带 APPROVED 结果的新实例
     */
    public PendingToolCall approve() {
        return new PendingToolCall(id, name, arguments, FeedbackResult.APPROVED, description);
    }

    /**
     * 生成「拒绝」的结果
     *
     * @param reason 拒绝原因，会作为工具响应回传给模型
     * @return 带 REJECTED 结果的新实例
     */
    public PendingToolCall reject(String reason) {
        return new PendingToolCall(id, name, arguments, FeedbackResult.REJECTED, reason);
    }
}
