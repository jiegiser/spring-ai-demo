package com.jiegiser.llm.mcp.callback;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.support.ToolUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自定义 MCP 工具回调提供者（支持 returnDirect 特性）
 * <p>
 * 继承关系：
 * ReturnDirectMcpToolCallbackProvider → SyncMcpToolCallbackProvider（Spring AI 提供的默认实现）
 * <p>
 * 为什么要自定义？
 * Spring AI 的 SyncMcpToolCallbackProvider 获取工具后，默认行为是：
 *   工具执行 → 结果回灌大模型 → 大模型生成最终回答 → 返回用户
 * <p>
 * 但某些场景下，工具结果就是最终答案，不需要大模型再加工：
 *   工具执行 → 直接返回结果给用户（跳过"回灌大模型"这一步）
 * <p>
 * 这就是 returnDirect 的作用：
 * - returnDirect = false（默认）：工具结果回灌大模型，由大模型组织语言回答
 * - returnDirect = true：工具结果直接返回给用户，大模型不参与
 * <p>
 * 适用场景举例：
 * - returnDirect = false：查天气工具 → 结果回灌大模型 → "北京今天晴，25°C，适合出行"
 * - returnDirect = true：下载文件工具 → 结果直接返回文件内容（不需要大模型再加工）
 * <p>
 * 实现原理：
 * 1. 重写 getToolCallbacks()：遍历所有 MCP 客户端，获取工具列表
 * 2. 为每个工具创建 ReturnDirectSyncMcpToolCallback（携带 returnDirect 标志）
 * 3. 校验工具名是否重复（多个 MCP Server 可能暴露同名工具）
 */
@Slf4j
public class ReturnDirectMcpToolCallbackProvider extends SyncMcpToolCallbackProvider {

    /** 所有已连接的 MCP 客户端（每个客户端对应一个 MCP Server） */
    private final List<McpSyncClient> mcpClients;

    /**
     * 是否直接返回工具结果（跳过回灌大模型）
     * - true：工具结果直接返回用户
     * - false：工具结果回灌大模型，由大模型生成最终回答
     */
    private boolean returnDirect;

    /**
     * 构造方法
     *
     * @param mcpClients   MCP 客户端列表（来自 Spring 容器自动注入）
     * @param returnDirect 是否直接返回工具结果
     */
    public ReturnDirectMcpToolCallbackProvider(List<McpSyncClient> mcpClients, boolean returnDirect) {
        // 调用父类构造器：注册客户端列表（父类内部会维护这些引用）
        super(mcpClients);
        this.mcpClients = mcpClients;
        this.returnDirect = returnDirect;
    }

    /**
     * 获取所有 MCP 工具的回调数组
     * <p>
     * 流程：
     * 1. 遍历所有 MCP 客户端（每个客户端对应一个 MCP Server）
     * 2. 对每个客户端，调用 listTools() 获取工具列表
     * 3. 为每个工具创建 ReturnDirectSyncMcpToolCallback（携带 returnDirect 标志）
     * 4. 校验工具名是否重复（多个 Server 可能暴露同名工具，会导致调用歧义）
     * 5. 返回工具回调数组
     * <p>
     * 容错处理：
     * 如果某个 MCP Server 不可用（listTools 抛异常），跳过该 Server，继续处理其他 Server
     * 这样不会因为一个 Server 故障导致整个工具列表获取失败
     *
     * @return 所有 MCP 工具的回调数组
     */
    @Override
    public ToolCallback[] getToolCallbacks() {
        var toolCallbacks = new ArrayList<>();

        // 遍历所有 MCP 客户端（每个客户端对应一个 MCP Server）
        for (McpSyncClient mcpClient : mcpClients) {
            // 默认空列表（如果 listTools 失败，保持空列表）
            List<McpSchema.Tool> toolList = Collections.emptyList();

            try {
                // 向 MCP Server 发送 tools/list 请求，获取该 Server 暴露的工具列表
                toolList = mcpClient.listTools().tools();
            } catch (Exception e) {
                // 跳过该 MCP，继续处理其它的
                // 例如：MCP Server 未启动、网络超时、握手失败等
                continue;
            }

            // 为每个工具创建回调对象（携带 returnDirect 标志）
            for (var tool : toolList) {
                toolCallbacks.add(new ReturnDirectSyncMcpToolCallback(mcpClient, tool, returnDirect));
            }
        }

        // 转换为数组
        var array = toolCallbacks.toArray(new ToolCallback[0]);

        // 校验工具名是否重复（多个 MCP Server 可能暴露同名工具）
        validateToolCallbacks(array);

        return array;
    }

    /**
     * 校验工具名是否重复
     * <p>
     * 为什么要校验？
     * 当配置了多个 MCP Server 时，不同 Server 可能暴露同名工具（如都叫 "getWeather"）
     * 如果工具名重复，大模型调用时无法确定调用哪个 Server 的工具，会产生歧义
     * <p>
     * 校验逻辑：
     * 1. 使用 ToolUtils.getDuplicateToolNames() 找出重复的工具名
     * 2. 打印重复的工具名（日志级别：info）
     * 3. 如果有重复，抛出 IllegalStateException（启动时就能发现配置问题）
     *
     * @param toolCallbacks 工具回调数组
     */
    private void validateToolCallbacks(ToolCallback[] toolCallbacks) {
        // 获取重复的工具名列表
        List<String> duplicateToolNames = ToolUtils.getDuplicateToolNames(toolCallbacks);

        // 打印重复的工具名（方便排查是哪个工具冲突）
        duplicateToolNames.forEach(s -> log.info("tool name found: {}", s));

        // 如果有重复，抛出异常（启动失败，强制修复配置）
        if (!duplicateToolNames.isEmpty()) {
            throw new IllegalStateException(
                    "Multiple tools with the same name (%s)".formatted(String.join(", ", duplicateToolNames)));
        }
    }
}
