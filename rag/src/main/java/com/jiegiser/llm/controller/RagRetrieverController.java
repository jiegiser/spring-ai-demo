package com.jiegiser.llm.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.jiegiser.llm.embedding.EmbeddingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索控制器
 * <p>
 * 提供三种检索方式，演示 RAG（检索增强生成）的不同实现方案
 * </p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>{@code /query} - 纯向量检索，返回原始文档片段</li>
 *   <li>{@code /retrieve} - 手动拼接提示词，调用大模型生成回答</li>
 *   <li>{@code /retrieveAdvisor} - 使用 Spring AI Advisor 自动检索 + 生成</li>
 * </ul>
 *
 * <p>三种方式对比：</p>
 * <table>
 *   <tr><th>方式</th><th>检索</th><th>生成</th><th>灵活性</th></tr>
 *   <tr><td>/query</td><td>✅</td><td>❌</td><td>低（只返回原文）</td></tr>
 *   <tr><td>/retrieve</td><td>✅ 手动</td><td>✅ 手动</td><td>中（自定义提示词）</td></tr>
 *   <tr><td>/retrieveAdvisor</td><td>✅ 自动</td><td>✅ 自动</td><td>高（Advisor 封装）</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/rag/retriever")
public class RagRetrieverController implements InitializingBean {

    /**
     * 向量检索服务
     * <p>提供相似度检索功能，内部调用 Embedding 模型向量化查询文本</p>
     */
    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 纯向量检索接口（只检索，不生成）
     * <p>
     * 流程：
     * <ol>
     *   <li>接收用户查询文本和相似度阈值</li>
     *   <li>调用 Embedding 模型将查询文本向量化</li>
     *   <li>在向量数据库中计算余弦相似度</li>
     *   <li>过滤掉相似度低于阈值的結果</li>
     *   <li>返回匹配的文档片段（JSON 格式）</li>
     * </ol>
     * </p>
     *
     * <p>示例：</p>
     * <pre>
     * GET /rag/retriever/query?query=什么是框架？&threshold=0.5
     *
     * 返回 JSON：
     * {
     *   "query": "什么是框架？",
     *   "count": 2,
     *   "results": [
     *     {
     *       "text": "框架是一种软件结构",
     *       "metadata": { "source": "框架文档.docx", "heading1": "第一章" }
     *     },
     *     {
     *       "text": "框架提供基础功能",
     *       "metadata": { "source": "框架文档.docx", "heading1": "第一章" }
     *     }
     *   ]
     * }
     * </pre>
     *
     * <p>适用场景：</p>
     * <ul>
     *   <li>只需要检索相关文档，不需要大模型生成</li>
     *   <li>测试向量数据库的检索效果</li>
     *   <li>作为其他系统的检索组件</li>
     * </ul>
     *
     * @param query     查询文本（如"什么是框架？"）
     * @param threshold 相似度阈值（0.0-1.0，只返回相似度 ≥ 该值的結果）
     * @return 检索結果的 JSON 对象（包含查询文本、結果数量、文档列表）
     */
    @GetMapping("/query")
    public Map<String, Object> query(String query, double threshold) {
        // 1. 调用向量检索服务，查找相似文档
        List<Document> documents = embeddingService.similaritySearch(SearchRequest
                .builder()
                .query(query)                    // 查询文本
                .similarityThreshold(threshold)  // 相似度阈值
                .build());

        // 2. 构建 JSON 响应
        Map<String, Object> result = new HashMap<>();
        result.put("query", query);                          // 查询文本
        result.put("count", documents.size());               // 結果数量
        result.put("results", documents.stream().map(doc -> {
            Map<String, Object> item = new HashMap<>();
            item.put("text", doc.getText());                 // 文档内容
            item.put("metadata", doc.getMetadata());         // 元数据（来源、标题等）
            return item;
        }).collect(Collectors.toList()));

        // 3. 返回 JSON 格式（比纯文本更易读、更易解析）
        return result;
    }

    /**
     * 大语言模型（通义千问），用于生成回答
     * <p>从 Spring 容器自动注入，由 Spring AI Alibaba 自动配置创建</p>
     */
    @Autowired
    private ChatModel chatModel;

    /**
     * 手动 RAG 接口（检索 + 生成）
     * <p>
     * 流程：
     * <ol>
     *   <li>调用向量检索服务，查找相似文档</li>
     *   <li>将文档片段拼接成参考内容</li>
     *   <li>构建提示词模板（包含参考文档 + 用户问题）</li>
     *   <li>调用大模型生成回答</li>
     * </ol>
     * </p>
     *
     * <p>示例：</p>
     * <pre>
     * GET /rag/retriever/retrieve?query=什么是框架？&threshold=0.5
     *
     * 内部流程：
     * 1. 检索到：["框架是一种软件结构", "框架提供基础功能"]
     * 2. 拼接提示词：
     *    "请基于以下提供的参考文档内容，回答用户的问题。
     *     如果参考文档中没有相关信息，请直接说明'没有找到相关信息'，不要编造内容。
     *     参考文档:
     *     框架是一种软件结构
     *     =========文档分隔线===========
     *     框架提供基础功能
     *     用户问题：什么是框架？"
     * 3. 调用大模型生成："框架是一种为软件开发提供基础结构和通用功能的软件结构..."
     * </pre>
     *
     * <p>与 /query 的区别：</p>
     * <ul>
     *   <li>/query：只检索，返回原始文档片段</li>
     *   <li>/retrieve：检索 + 生成，返回大模型的回答</li>
     * </ul>
     *
     * <p>优点：</p>
     * <ul>
     *   <li>完全控制提示词模板</li>
     *   <li>可以自定义检索逻辑</li>
     *   <li>适合需要精细控制的场景</li>
     * </ul>
     *
     * <p>缺点：</p>
     * <ul>
     *   <li>代码冗余（每次都要手动拼接提示词）</li>
     *   <li>不易维护（提示词散落在代码中）</li>
     * </ul>
     *
     * @param query     查询文本（如"什么是框架？"）
     * @param threshold 相似度阈值（0.0-1.0）
     * @return 大模型生成的回答文本
     */
    @GetMapping("/retrieve")
    public String retrieve(String query, double threshold) {
        // 1. 向量检索：查找相似文档
        List<Document> documents = embeddingService.similaritySearch(SearchRequest
                .builder()
                .query(query)
                .similarityThreshold(threshold)
                .build());
    
        // 2. 拼接文档片段为参考内容（用分隔符隔开）
        String documentContent = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n=========文档分隔线===========\n\n"));
    
        // 3. 构建提示词模板
        // {documents} 和 {question} 是占位符，会被实际内容替换
        String promptTemplate = """
                请基于以下提供的参考文档内容，回答用户的问题。
                如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
                    
                参考文档:
                {documents}
                    
                用户问题：{question}
                """;
    
        // 4. 创建提示词对象（替换占位符）
        PromptTemplate prompt = new PromptTemplate(promptTemplate);
        Prompt realPrompt = prompt.create(Map.of(
                "documents", documentContent,  // 替换 {documents}
                "question", query              // 替换 {question}
        ));
    
        // 5. 调用大模型生成回答
        return chatModel.call(realPrompt).getResult().getOutput().getText();
    }


    /**
     * ChatClient（封装了大模型调用 + Advisor 链路）
     * <p>在 {@link #afterPropertiesSet()} 中初始化，配置了 QuestionAnswerAdvisor</p>
     */
    private ChatClient chatClient;

    /**
     * Advisor 模式 RAG 接口（自动检索 + 生成）
     * <p>
     * 流程（全部由 Advisor 自动完成）：
     * <ol>
     *   <li>接收用户查询文本</li>
     *   <li>QuestionAnswerAdvisor 自动调用向量检索服务</li>
     *   <li>自动将检索結果注入提示词</li>
     *   <li>自动调用大模型生成回答</li>
     *   <li>返回最终回答</li>
     * </ol>
     * </p>
     *
     * <p>示例：</p>
     * <pre>
     * GET /rag/retriever/retrieveAdvisor?query=什么是框架？
     *
     * 内部流程（对用户透明）：
     * 1. Advisor 拦截请求
     * 2. 自动检索：embeddingService.similaritySearch("什么是框架？")
     * 3. 自动拼接提示词：参考文档 + 用户问题
     * 4. 自动调用大模型生成回答
     * 5. 返回："框架是一种为软件开发提供基础结构..."
     * </pre>
     *
     * <p>与 /retrieve 的区别：</p>
     * <ul>
     *   <li>/retrieve：手动检索 + 手动拼接提示词 + 手动调用大模型</li>
     *   <li>/retrieveAdvisor：Advisor 自动完成所有步骤（代码更简洁）</li>
     * </ul>
     *
     * <p>优点：</p>
     * <ul>
     *   <li>代码简洁（只需一行 {@code chatClient.prompt(query).call().content()}）</li>
     *   <li>易于维护（提示词模板集中在配置中）</li>
     *   <li>可复用（Advisor 可以应用到多个 ChatClient）</li>
     * </ul>
     *
     * <p>缺点：</p>
     * <ul>
     *   <li>灵活性较低（提示词模板在初始化时固定）</li>
     *   <li>不易调试（Advisor 内部逻辑不透明）</li>
     * </ul>
     *
     * @param query 查询文本（如"什么是框架？"）
     * @return 大模型生成的回答文本
     */
    @GetMapping("/retrieveAdvisor")
    public String retrieveAdvisor(String query) {
        // 一行代码完成：检索 + 生成
        // 内部由 QuestionAnswerAdvisor 自动处理
        return chatClient.prompt(query).call().content();
    }

    /**
     * 向量数据库（PostgreSQL + pgvector）
     * <p>用于存储和检索向量，由 QuestionAnswerAdvisor 内部调用</p>
     */
    @Autowired
    private PgVectorStore vectorStore;

    /**
     * 初始化 ChatClient（应用启动时执行一次）
     * <p>
     * 配置内容：
     * <ol>
     *   <li>创建自定义提示词模板（用于 QuestionAnswerAdvisor）</li>
     *   <li>创建 QuestionAnswerAdvisor（自动检索 + 注入上下文）</li>
     *   <li>构建 ChatClient（注册 Advisor + 配置模型参数）</li>
     * </ol>
     * </p>
     *
     * <p>为什么用 {@link InitializingBean}？</p>
     * <ul>
     *   <li>确保所有依赖（chatModel、vectorStore）已注入</li>
     *   <li>在 Bean 初始化完成后执行配置逻辑</li>
     *   <li>避免在构造器中访问未注入的依赖</li>
     * </ul>
     *
     * <p>QuestionAnswerAdvisor 工作原理：</p>
     * <pre>
     * 用户请求 → Advisor 拦截
     *   ↓
     * 自动检索：vectorStore.similaritySearch(query)
     *   ↓
     * 自动拼接提示词：
     *   "请基于以下提供的参考文档内容，回答用户的问题。
     *    如果参考文档中没有相关信息，请直接说明'没有找到相关信息'，不要编造内容。
     *    参考文档内容:
     *    {检索到的文档片段}
     *    用户问题：{用户查询}"
     *   ↓
     * 自动调用大模型 → 返回回答
     * </pre>
     *
     * @throws Exception 初始化失败时抛出异常
     */
    @Override
    public void afterPropertiesSet() throws Exception {
    
        // 1. 自定义 Prompt 模板（Advisor 会自动填充 {question_answer_context} 和 {query}）
        PromptTemplate promptTemplate = new PromptTemplate("""
                请基于以下提供的参考文档内容，回答用户的问题。
                如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
                    
                参考文档内容:
                {question_answer_context}
                    
                用户问题：{query}
                """);
    
        // 2. 创建 QuestionAnswerAdvisor
        //    - 自动调用 vectorStore 进行向量检索
        //    - 自动将检索結果注入提示词的 {question_answer_context} 占位符
        // 从向量数据库中检索
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .similarityThreshold(0.1)  // 相似度阈值 ≥ 0.5
                        .topK(5)                   // 返回前 5 个最相似的文档
                        .build())
                .promptTemplate(promptTemplate)    // 使用自定义提示词模板
                .build();
    
        // 3. 构建 ChatClient
        this.chatClient = ChatClient.builder(chatModel)
                // 注册 Advisor（自动检索 + 注入上下文）
                .defaultAdvisors(questionAnswerAdvisor)
                // 设置 ChatModel 的 Options 参数
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)  // Top-p 采样参数（0.7 表示从概率前 70% 的候选词中采样）
                                .build()
                ).build();
    }
}
