package com.jiegiser.llm.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.jiegiser.llm.embedding.EmbeddingService;
import com.jiegiser.llm.reader.DocumentReaderFactory;
import com.jiegiser.llm.splitter.OverlapParagraphTextSplitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * RAG 元数据检索控制器
 * <p>演示如何通过元数据（如 fileName）过滤检索结果</p>
 *
 * <p>三个接口：</p>
 * <ul>
 *   <li>/embedding - 读取文档并添加 fileName 元数据后入库</li>
 *   <li>/retrieveMetadata - 按 fileName 过滤的纯向量检索</li>
 *   <li>/retrieveAdvisorWithMetadata - 按 fileName 过滤的 RAG 检索 + 生成</li>
 * </ul>
 */
@RestController
@RequestMapping("/rag/metadata")
public class RagMetadataController implements InitializingBean {

    @Autowired
    private DocumentReaderFactory documentReaderFactory;

    @Autowired
    private EmbeddingService embeddingService;

    /**
     * 读取文档并添加 fileName 元数据后入库
     * <p>与 RagEmbeddingController 的区别：每个 Document 都会带上 fileName 元数据，后续可按文件名过滤检索</p>
     *
     * @param filePath 文档本地路径（如 D:\docs\框架.docx）
     * @param fileName 文件名标识（如 "框架文档"，用于后续过滤）
     * http://localhost:8001/rag/metadata/embedding?filePath=xxxx&fileName=xxx
     */
    @GetMapping("/embedding")
    public String embedding(String filePath, String fileName) {
        // 1. 读取文档（支持 .doc/.docx/.pdf 等）
        List<Document> documents;
        try {
            documents = documentReaderFactory.read(new File(filePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 2. 分段（每段最多 1000 字符，相邻段重叠 50 字符），避免单段文本超过 Embedding 模型的 token 限制
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(1000, 50);
        List<Document> chunkedDocuments = documents.stream()
                .flatMap(doc -> splitter.split(doc).stream())
                .collect(java.util.stream.Collectors.toList());

        // 3. 为每个分段添加 fileName 元数据（后续检索时可按此过滤）
        for (Document document : chunkedDocuments) {
            document.getMetadata().put("fileName", fileName);
        }

        // 4. 向量化并存储到向量数据库
        embeddingService.embedAndStore(chunkedDocuments);

        return "success";
    }

    /**
     * 按 fileName 过滤的纯向量检索（只检索，不生成）
     * <p>通过 filterExpression 限定只搜索指定文件名的文档片段</p>
     *
     * @param query    查询文本
     * @param fileName 文件名标识（与 /embedding 时写入的一致）
     */
    @GetMapping("/retrieveMetadata")
    public String retrieveMetadata(String query, String fileName) {
        // filterExpression 语法：元数据键 == '值'，只返回匹配的文件
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .filterExpression("fileName == '" + fileName + "'")
                .build();

        return embeddingService.similaritySearch(searchRequest).toString();
    }

    @Autowired
    private PgVectorStore vectorStore;

    @Autowired
    private ChatModel chatModel;

    private ChatClient chatClient;
    /**
     * 按 fileName 过滤的 RAG 检索 + 生成
     * <p>通过 Advisor 参数 qa_filter_expression 动态传入过滤条件，只检索指定文件的内容来生成回答</p>
     *
     * @param query    查询文本
     * @param fileName 文件名标识
     */
    @GetMapping("/retrieveAdvisorWithMetadata")
    public String retrieveAdvisorWithMetadata(String query, String fileName) {
        // 通过 advisors 参数动态传入过滤表达式，覆盖 Advisor 的默认 searchRequest
        return chatClient.prompt(query)
                .advisors(advisorSpec -> advisorSpec.param("qa_filter_expression", "fileName == '" + fileName + "'"))
                .call().content();
    }


    /**
     * 初始化 ChatClient，配置 QuestionAnswerAdvisor
     */
    @Override
    public void afterPropertiesSet() throws Exception {
    
        // 自定义 Prompt 模板
        PromptTemplate promptTemplate = new PromptTemplate("""
                请基于以下提供的参考文档内容，回答用户的问题。
                如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
                    
                参考文档内容:
                {question_answer_context}
                    
                用户问题：{query}
                """);
    
        // 创建 Advisor：默认检索参数（相似度 0.5，topK 5），无过滤条件
        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.1).topK(5).build())
                .promptTemplate(promptTemplate).build();
    
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(questionAnswerAdvisor)
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .withTopP(0.7)
                                .build()
                ).build();
    }
}
