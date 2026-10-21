package com.jiegiser.llm.mcp.callback;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 自定义 MCP 工具回调（支持 returnDirect 特性）
 * <p>
 * 继承关系：
 * ReturnDirectSyncMcpToolCallback → SyncMcpToolCallback（Spring AI 提供的默认实现）
 * <p>
 * 核心功能：
 * 重写 getToolMetadata() 方法，返回自定义的 ToolMetadata（携带 returnDirect 标志）
 * <p>
 * returnDirect 的作用：
 * 控制工具执行后的结果流向：
 * - returnDirect = false（默认）：
 *   工具执行 → 结果回灌大模型 → 大模型组织语言 → 返回用户
 *   示例：查天气工具 → 结果 "北京: 晴, 25°C" → 大模型说 "北京今天天气晴朗，温度 25°C，适合出行"
 * <p>
 * - returnDirect = true：
 *   工具执行 → 结果直接返回用户（跳过"回灌大模型"这一步）
 *   示例：下载文件工具 → 结果直接返回文件内容（不需要大模型再加工）
 * <p>
 * 适用场景：
 * - returnDirect = false：需要大模型"翻译"或"总结"工具结果时（如天气查询、订单详情）
 * - returnDirect = true：工具结果已经是最终答案，不需要大模型参与时（如文件下载、原始数据导出）
 * <p>
 * 实现原理：
 * 1. 构造时接收 returnDirect 参数
 * 2. 重写 getToolMetadata()：返回携带 returnDirect 标志的 ToolMetadata
 * 3. Spring AI 的 ToolCallingManager 执行工具后，检查 ToolMetadata.returnDirect
 *    - true：直接返回工具结果
 *    - false：结果回灌大模型（默认行为）
 */
public class ReturnDirectSyncMcpToolCallback extends SyncMcpToolCallback {

    /** 是否直接返回工具结果（跳过回灌大模型） */
    private final boolean returnDirect;

    /**
     * 构造方法
     *
     * @param client       MCP 客户端（用于调用远程工具）
     * @param tool         工具定义（name、description、parameters）
     * @param returnDirect 是否直接返回工具结果
     */
    public ReturnDirectSyncMcpToolCallback(McpSyncClient client, McpSchema.Tool tool, boolean returnDirect) {
        // 调用父类构造器：注册客户端和工具定义
        super(client, tool);
        this.returnDirect = returnDirect;
    }

    /**
     * 获取工具元数据（携带 returnDirect 标志）
     * <p>
     * Spring AI 的 ToolCallingManager 执行工具后：
     * 1. 调用 getToolMetadata() 获取元数据
     * 2. 检查 metadata.returnDirect
     * 3. 根据标志决定结果流向
     *
     * @return 工具元数据（包含 returnDirect 配置）
     */
    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder()
                .returnDirect(returnDirect)  // 设置是否直接返回
                .build();
    }
}
