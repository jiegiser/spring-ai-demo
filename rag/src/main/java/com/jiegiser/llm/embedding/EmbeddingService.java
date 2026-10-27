package com.jiegiser.llm.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 向量存储服务
 * <p>
 * 提供文本向量化、存储和相似度检索功能
 * </p>
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>读取文档 → 清洗 → 分段</li>
 *   <li>调用 Embedding 模型将文本转为向量</li>
 *   <li>存储到向量数据库（PostgreSQL + pgvector）</li>
 *   <li>检索时：问题向量化 → 在数据库中找最相似的向量 → 返回原文</li>
 * </ol>
 */
@Service
public class EmbeddingService {

    /**
     * Embedding 模型（DashScope text-embedding-v4）
     * <p>负责将文本转换为 768 维向量</p>
     */
    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 向量数据库（PostgreSQL + pgvector）
     * <p>负责存储和检索向量</p>
     */
    @Autowired
    private VectorStore vectorStore;

    /**
     * 批量向量化（不调用数据库，仅转换）
     * <p>
     * 将文档列表转换为向量数组，每个文档对应一个 float[]（768 维）
     * </p>
     *
     * <p>示例：</p>
     * <pre>
     * 输入：["苹果是水果", "汽车是交通工具"]
     * 输出：[[0.82, -0.15, ...], [0.45, 0.67, ...]]  （2 个 768 维向量）
     * </pre>
     *
     * @param documents 待向量化的文档列表
     * @return 向量数组列表，每个元素是一个 768 维的 float 数组
     */
    public List<float[]> embed(List<Document> documents) {
        return documents.stream()
                .map(document -> embeddingModel.embed(document.getText()))
                .collect(Collectors.toList());
    }

    /**
     * 向量化并存储到向量数据库
     * <p>
     * 流程：
     * <ol>
     *   <li>将文档列表分批（每批 9 个，避免单次请求过大）</li>
     *   <li>调用 Embedding 模型向量化</li>
     *   <li>批量插入向量数据库</li>
     * </ol>
     * </p>
     *
     * <p>为什么分批？</p>
     * <ul>
     *   <li>API 限制：DashScope 单次最多处理 9 个文档</li>
     *   <li>性能优化：避免内存溢出</li>
     *   <li>错误隔离：一批失败不影响其他批次</li>
     * </ul>
     *
     * @param documents 待处理的文档列表
     */
    public void embedAndStore(List<Document> documents) {
        // 每批最多 9 个文档（与 max-document-batch-size 配置一致）
        for (int i = 0; i < documents.size(); i += 9) {
            // 截取当前批次（最后一批可能不足 9 个）
            List<Document> batches = documents.subList(i, Math.min(i + 9, documents.size()));
            // 向量化 + 存储（vectorStore 内部会调用 embeddingModel）
            vectorStore.add(batches);
        }
    }

    /** 默认返回最相似的前 K 个结果 */
    private static final int DEFAULT_TOP_K = 5;

    /** 默认相似度阈值（余弦相似度 0.5 以上才返回） */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

    /**
     * 相似度检索（简化版，使用默认参数）
     * <p>
     * 检索流程：
     * <ol>
     *   <li>将查询文本向量化（调用 Embedding 模型）</li>
     *   <li>在向量数据库中计算与所有向量的余弦相似度</li>
     *   <li>按相似度降序排序</li>
     *   <li>过滤掉相似度低于阈值的結果</li>
     *   <li>返回前 K 个最相似的文档</li>
     * </ol>
     * </p>
     *
     * <p>示例：</p>
     * <pre>
     * 查询："什么水果是红色的？"
     *   ↓ 向量化
     * 查询向量：[0.79, -0.12, 0.41, ..., 0.65]
     *   ↓ 计算相似度
     * 数据库中的向量：
     *   - "苹果是红色的水果" → 相似度 0.95 ✅
     *   - "香蕉是黄色的" → 相似度 0.32 ❌（低于阈值）
     *   - "草莓也是红色的" → 相似度 0.88 ✅
     *   ↓ 返回前 5 个
     * 结果：["苹果是红色的水果", "草莓也是红色的", ...]
     * </pre>
     *
     * @param query 查询文本（如"什么水果是红色的？"）
     * @return 最相似的文档列表（最多 5 个，相似度 ≥ 0.5）
     */
    public List<Document> similaritySearch(String query) {
        return vectorStore.similaritySearch(SearchRequest
                .builder()
                .query(query)                          // 查询文本
                .topK(DEFAULT_TOP_K)                   // 返回前 5 个
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)  // 相似度 ≥ 0.5
                .build());
    }

    /**
     * 相似度检索（自定义参数版）
     * <p>
     * 与 {@link #similaritySearch(String)} 相同，但允许自定义检索参数
     * </p>
     *
     * <p>使用场景：</p>
     * <ul>
     *   <li>需要调整返回数量（topK）</li>
     *   <li>需要调整相似度阈值</li>
     *   <li>需要添加元数据过滤条件</li>
     * </ul>
     *
     * <p>示例：</p>
     * <pre>
     * SearchRequest request = SearchRequest.builder()
     *     .query("框架设计")
     *     .topK(10)              // 返回前 10 个
     *     .similarityThreshold(0.7)  // 相似度 ≥ 0.7（更严格）
     *     .filterExpression("metadata['category'] == 'technical'")  // 只检索技术类文档
     *     .build();
     * List&lt;Document&gt; results = similaritySearch(request);
     * </pre>
     *
     * @param searchRequest 自定义检索请求（包含查询文本、topK、阈值、过滤条件等）
     * @return 符合检索条件的文档列表
     */
    public List<Document> similaritySearch(SearchRequest searchRequest) {
        return vectorStore.similaritySearch(searchRequest);
    }
}
