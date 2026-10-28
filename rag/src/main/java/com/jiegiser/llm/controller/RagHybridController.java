package com.jiegiser.llm.controller;


import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;
import com.jiegiser.llm.embedding.EmbeddingService;
import com.jiegiser.llm.es.ElasticSearchService;
import com.jiegiser.llm.es.EsDocumentChunk;
import com.jiegiser.llm.reader.DocumentReaderFactory;
import com.jiegiser.llm.rerank.RerankUtil;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * RAG 混合检索控制器
 * <p>同时使用向量检索（PostgreSQL）和关键词检索（ElasticSearch），通过重排序融合结果</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>/write - 文档入库：读取 → 分片 → 同时写入 ES 和向量库</li>
 *   <li>/searchFromEs - 纯 ES 关键词检索</li>
 *   <li>/searchFromVector - 纯向量语义检索</li>
 *   <li>/searchFromHybrid - 混合检索 + 重排序融合</li>
 *   <li>/chatToHybrid - 混合检索 + 重排序 + 大模型生成回答</li>
 * </ul>
 */
@RestController
@RequestMapping("/rag/hybrid")
public class RagHybridController {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private DocumentReaderFactory selector;

    @Autowired
    private ElasticSearchService elasticSearchService;


    /**
     * 文档入库：读取 → 分片 → 同时写入 ES 和向量库
     *
     * @param filePath 文档路径
     */
    @RequestMapping("write")
    public String write(String filePath) throws Exception {
        // 1. 读取文档
        List<Document> documents = selector.read(new File(filePath));

        // 2. 分片（每块最大 100 字符，按句号分割）
        RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(
                100,
                new String[]{"。"});

        List<Document> spllittedDocuments = splitter.apply(documents);

        for (Document doc : spllittedDocuments) {
            System.out.println(doc.getText());
            System.out.println(doc.getMetadata());
            System.out.println("--------------------------------------------------");
        }

        // 3. 写入 ES（关键词检索用）
        List<EsDocumentChunk> esDocs = spllittedDocuments.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        elasticSearchService.bulkIndex(esDocs);

        // 4. 写入向量库（语义检索用）
        embeddingService.embedAndStore(spllittedDocuments);

        return "success";
    }

    /** 纯 ES 关键词检索 */
    @RequestMapping("searchFromEs")
    public List<EsDocumentChunk> searchFromEs(String keyword) throws Exception {
        return elasticSearchService.searchByKeyword(keyword);
    }

    /** 纯向量语义检索 */
    @RequestMapping("searchFromVector")
    public List<Document> searchFromVector(String keyword) throws Exception {
        return embeddingService.similaritySearch(keyword);
    }

    /**
     * 混合检索 + 重排序融合
     * <p>流程：ES 关键词检索 + 向量语义检索 → RerankUtil 融合排序 → 返回 topK</p>
     *
     * @param keyword 查询关键词
     * @return 融合后的结果列表
     */
    @RequestMapping("searchFromHybrid")
    public List<String> searchFromHybrid(String keyword) throws Exception {
        // 1. ES 关键词检索
        List<EsDocumentChunk> esDocs = elasticSearchService.searchByKeyword(keyword);

        System.out.println(esDocs);
        System.out.println("================");

        // 2. 向量语义检索
        List<Document> vectorDocs = embeddingService.similaritySearch(keyword);
        System.out.println(vectorDocs);
        System.out.println("================");

        // 3. 重排序融合，返回 top 5
        List<String> result = RerankUtil.rerankFusion(vectorDocs, esDocs, keyword, 5);
        // List<String> result = RerankUtil.rrfFusion(vectorDocs, esDocs, 5);
        System.out.println(result);
        System.out.println("================");
        return result;
    }

    @Autowired
    private ChatModel chatModel;

    /**
     * 混合检索 + 重排序 + 大模型生成回答
     * <p>流程：问题改写 → 混合检索 → 重排序 → 大模型基于检索结果生成回答</p>
     *
     * @param keyword 用户问题
     * @return 大模型生成的回答
     */
    @RequestMapping("chatToHybrid")
    public String chatToHybrid(String keyword) throws Exception {
        // 1. 问题改写：让大模型优化问题，使其更具体、纠正错别字
        String newQuestion = chatModel.call("""
                你是一个问题改写大师，请改写用户的问题，使其更具体、更详细。
                如果其中有错别字，请你直接做修改。
                用户问题：
                                        
                """ + keyword);

        System.out.println(newQuestion);

        // 2. 混合检索：ES + 向量
        List<EsDocumentChunk> esDocs = elasticSearchService.searchByKeyword(newQuestion);

        List<Document> vectorDocs = embeddingService.similaritySearch(newQuestion);

        // 3. 重排序融合
        List<String> result = RerankUtil.rerankFusion(vectorDocs, esDocs, newQuestion, 5);

        // 4. 大模型基于检索结果生成回答
        String prompt = """
                请根据以下文档内容，回答用户的问题。
                注意，你只能参考文档内容回答，不要自己做推理。
                文档内容：
                {contents}
                                
                用户问题:
                {question}
                """;

        return chatModel.call(new PromptTemplate(prompt).create(Map.of("contents", result, "question", newQuestion))).getResult().getOutput().getText();
    }
}
