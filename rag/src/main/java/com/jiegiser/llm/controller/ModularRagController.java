package com.jiegiser.llm.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Modular RAG（模块化 RAG）控制器
 * <p>通过 Spring AI 的 RetrievalAugmentationAdvisor 自由组装 RAG 管道，每个环节都是可替换的独立模块</p>
 *
 * <p>本接口的管道：查询改写 → 查询扩展 → 文档检索 → 上下文增强 → 大模型生成</p>
 *
 * 一句话总结：Modular RAG 就是把 RAG 的每个环节抽象成独立模块，用统一的 Advisor 编排，想加哪个环节就插哪个模块，不需要的就不插。
 * 包括:
 * 1. 查询预处理:    查询重写\查询扩展\查询过滤
 * 2. 文档检索:     从向量数据库做检索
 * 3. 上下文后处理:  如文档合并等
 * 4. 提示增强:     将检索结果与用户问题合并
 */
@RestController
@RequestMapping("/rag/modular")
public class ModularRagController implements InitializingBean {

    @Autowired
    ChatModel chatModel;

    ChatClient chatClient;

    @Autowired
    private VectorStore vectorStore;


    /**
     * 模块化 RAG 检索 + 生成
     *
     * @param query 用户原始问题
     */
    @GetMapping("/retriever")
    public String retriever(String query) {

        // 模块 1：文档检索器（检索阶段）—— 从向量库召回相似文档
        DocumentRetriever retriever = VectorStoreDocumentRetriever.builder().vectorStore(vectorStore)          // 必需：绑定向量存储
                .topK(5)                             // 返回最相似的 5 个文档
                .similarityThreshold(0.6)            // 相似度低于 0.6 的过滤掉
                .build();

        // 模块 2：查询改写器（检索前）—— 修正错别字、去除无关信息，让查询更精准
        RewriteQueryTransformer queryTransformer = RewriteQueryTransformer
                .builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .promptTemplate(
                        new PromptTemplate("""
                Given a user query, rewrite it to provide better results when querying a {target}.
                                        
                Remove any irrelevant information, and ensure the query is concise and specific.
                                        
                如果有表述不清的内容，或者错别字，请修正，如"华子"，修改为"华为"
                                        
                Original query:
                {query}
                                        
                Rewritten query:
                """)).build();


        // 模块 3：查询扩展器（检索前）—— 生成 3 个查询变体 + 保留原查询，提高召回率
        QueryExpander queryExpander = MultiQueryExpander
                .builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();

        // 模块 4：上下文增强器（生成前）—— 把检索到的文档正文填进提示词模板，整段替换掉原始 user message
        //   {context} = 各 Document 的 getText() 用换行拼接（metadata 丢失，需要溯源请自定义 documentFormatter）
        //   {query}   = 用户原始问题
        // allowEmptyContext(true) 表示：检索为空时原样返回原问题，不附加任何"仅依据上下文回答"的约束
        //   注意：此时 emptyContextPromptTemplate 不会被使用（它仅在 allowEmptyContext(false) 时生效），
        //   且该模板是无变量渲染，内部不能写 {query}，否则会抛 IllegalStateException
        QueryAugmenter queryAugmenter = ContextualQueryAugmenter
                .builder()
                // 自定义中文提示词模板：{context} 填检索到的文档正文（换行拼接），{query} 填用户原始问题
                // 构造器会校验模板必须同时含 query 与 context 占位符，缺失直接抛 IllegalArgumentException
                .promptTemplate(new PromptTemplate("""
                        以下是检索到的参考信息。

                        ---------------------
                        {context}
                        ---------------------

                        请仅依据上述参考信息回答用户问题，不要使用参考信息之外的知识。

                        回答规则：

                        1. 如果参考信息中没有相关内容，直接说明未检索到相关信息，不要编造答案。
                        2. 不要使用“根据上下文……”或“根据提供的信息……”这类表述。

                        问题：{query}

                        回答：
                        """))
                .allowEmptyContext(true)
                .build();

        // 组装管道：改写 → 扩展 → 检索 → 增强，由 Advisor 拦截请求自动执行
        RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor
                .builder()
                // 检索阶段：从向量库检索文档（必需）
                .documentRetriever(retriever)
                // 查询预处理：转换查询
                .queryTransformers(queryTransformer)
                // 查询预处理：扩展查询
                .queryExpander(queryExpander)
                // 生成阶段：构建增强提示词
                .queryAugmenter(queryAugmenter)
                // 后处理阶段：文档合并 —— 不配置时 Advisor 默认就用 ConcatenationDocumentJoiner（全部拼接、不去重），
                // 因此下面这行注释与否行为完全相同；想换合并策略（去重、按分数截断）才需要显式提供
                // .documentJoiner()
                .build();

        return chatClient.prompt(query).advisors(advisor).call().content();
    }

    /** 初始化 ChatClient，挂 SimpleLoggerAdvisor 打印请求/响应日志 */
    @Override
    public void afterPropertiesSet() throws Exception {
        chatClient = ChatClient.builder(chatModel).defaultAdvisors(new SimpleLoggerAdvisor()).build();
    }
}
