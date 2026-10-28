package com.jiegiser.llm.rerank;

import com.jiegiser.llm.es.EsDocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序工具类
 * <p>提供两种融合策略，将向量检索和关键词检索的结果合并排序</p>
 *
 * <ul>
 *   <li>rrfFusion - 纯算法融合（RRF），不依赖模型，基于排名计算</li>
 *   <li>rerankFusion - 模型融合，调用 Rerank API 进行语义精排</li>
 * </ul>
 *
 * <p>核心前提：同一文档分片在向量库和 ES 中必须使用相同的 ID，否则无法正确融合</p>
 */
@Slf4j
public class RerankUtil {

    /**
     * RRF 算法融合（Reciprocal Rank Fusion）
     * <p>纯算法，不依赖模型，基于文档在各检索系统中的排名计算融合分数</p>
     *
     * <p>公式：RRF_score(d) = Σ 1/(K + rank_i)，K 为常数 60，rank_i 为文档在第 i 个系统中的排名</p>
     *
     * <p>ID 的作用：核心匹配依据</p>
     * <ul>
     *   <li>同一文档在向量检索和 ES 检索中必须有相同的 ID</li>
     *   <li>如果同一 ID 在两个系统中都出现，得分会累加（排名越靠前，得分越高）</li>
     *   <li>最终按累加得分降序排序，返回 topK</li>
     * </ul>
     *
     * @param vectorDocs  向量检索结果（已按语义相似度排序）
     * @param keywordDocs ES 关键词检索结果（已按 BM25 排序）
     * @param topK        返回前 K 条
     * @return 融合后的文档内容列表
     */
    public static List<String> rrfFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, int topK) {
        // 常数 K = 60，控制低排名文档的权重衰减
        final int K = 60;
        // 存储每个文档 ID 的 RRF 得分（核心：按 ID 累加）
        Map<String, Double> rrfScores = new HashMap<>();
        // 存储文档 ID 到 chunkId 的映射（用于日志输出）
        Map<String, String> idToChunkId = new HashMap<>();

        // 处理向量检索结果：按排名计算 RRF 分数
        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            String docId = doc.getId();  // ← 关键：用 ID 作为匹配依据
            String chunkId = doc.getMetadata().getOrDefault("chunkId", "unknown").toString();
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;  // 排名从 1 开始
            double score = 1.0 / (K + rank);  // RRF 公式：1/(K + rank)
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);  // ← 累加分数
        }

        // 处理 ES 关键词检索结果：同样按排名计算 RRF 分数
        for (int i = 0; i < keywordDocs.size(); i++) {
            EsDocumentChunk doc = keywordDocs.get(i);
            String docId = doc.getId();  // ← 同一个 ID，如果和向量检索重复，分数会累加
            String chunkId = doc.getMetadata().getOrDefault("chunkId", "unknown").toString();
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);  // ← 累加分数
        }

        // 收集所有文档ID并按RRF得分降序排序，同时限制返回topK条
        List<String> sortedDocIds = rrfScores.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).map(Map.Entry::getKey).limit(topK).collect(Collectors.toList());

        // 打印每个文本块的chunkId和分数
        String scoresLog = sortedDocIds.stream().map(docId -> {
            String chunkId = idToChunkId.getOrDefault(docId, "unknown");
            double score = rrfScores.getOrDefault(docId, 0.0);
            return String.format("chunkId: %s, RRF Score: %.4f", chunkId, score);
        }).collect(Collectors.joining("; "));

        log.info("RRF融合后top{}结果：{}", topK, scoresLog);

        // 构建文档 ID 到内容的映射
        Map<String, String> idToContent = new HashMap<>();
        vectorDocs.forEach(doc -> idToContent.putIfAbsent(doc.getId(), doc.getText()));
        keywordDocs.forEach(doc -> idToContent.putIfAbsent(doc.getId(), doc.getContent()));

        // 按排序后的ID提取文档内容
        return sortedDocIds.stream().map(idToContent::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Rerank 模型融合（调用 DashScope gte-rerank-v2）
     * <p>使用专门的 Rerank 模型进行语义精排，比 RRF 算法更精准</p>
     *
     * <p>ID 的作用：辅助去重和日志追踪</p>
     * <ul>
     *   <li>用 ID 对向量检索和 ES 检索的结果去重（同一文档只保留一份）</li>
     *   <li>发送给 Rerank 模型的是文本内容，不是 ID</li>
     *   <li>模型返回排序后的文本，通过内容字符串反向匹配找到原始 chunkId</li>
     * </ul>
     *
     * <p>与 RRF 的区别：</p>
     * <ul>
     *   <li>RRF：纯算法，基于排名，免费、快速、够用</li>
     *   <li>Rerank：基于模型，基于语义理解，付费、稍慢、更精准</li>
     * </ul>
     *
     * @param vectorDocs  向量检索结果
     * @param keywordDocs ES 关键词检索结果
     * @param query       用户查询（用于计算语义相关性）
     * @param topK        返回前 K 条
     * @return 重排序后的文档内容列表
     */
    public static List<String> rerankFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, String query, int topK) throws Exception {
        // 用 ID 去重：同一文档在两个检索结果中只保留一份
        Map<String, String> idToContent = new LinkedHashMap<>();
        Map<String, String> idToChunkId = new HashMap<>();

        // 收集向量检索结果（用 ID 去重）
        vectorDocs.forEach(doc -> {
            String docId = doc.getId();
            idToContent.putIfAbsent(docId, doc.getText());  // ← putIfAbsent：同一 ID 只保留第一次出现的
            String chunkId = doc.getMetadata().getOrDefault("chunkId", docId).toString();
            idToChunkId.putIfAbsent(docId, chunkId);
        });

        // 收集 ES 检索结果（用 ID 去重）
        keywordDocs.forEach(doc -> {
            String docId = doc.getId();
            idToContent.putIfAbsent(docId, doc.getContent());  // ← 如果 ID 已存在，跳过
            String chunkId = doc.getMetadata().getOrDefault("chunkId", docId).toString();
            idToChunkId.putIfAbsent(docId, chunkId);
        });

        // 提取去重后的文档内容列表（发送给 Rerank 模型的是文本，不是 ID）
        List<String> documents = new ArrayList<>(idToContent.values());
        if (documents.isEmpty()) {
            log.info("没有检索到任何文档，无需重排序");
            return Collections.emptyList();
        }

        // 调用 DashScope Rerank API
        String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        HttpHeaders headers = new HttpHeaders();
        // 补充自己的 apikey
        headers.set("Authorization", "Bearer sk-xxxxxxxxxxxxxxxxxx");
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "gte-rerank-v2");

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);
        requestBody.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("return_documents", true);
        parameters.put("top_n", topK);
        parameters.put("instruct", "Given a web search query, retrieve relevant passages that answer the query.");
        requestBody.put("parameters", parameters);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory() {{
            setConnectTimeout(5000);
            setReadTimeout(10000);
        }});

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序 API 调用失败: " + response.getStatusCode() + "，响应: " + response.getBody());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("output")) {
            throw new RuntimeException("API响应格式异常，缺少 output 字段: " + responseBody);
        }

        Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
        List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) output.get("results");
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            log.warn("重排序返回空结果: {}", output);
            return Collections.emptyList();
        }

        // 解析 API 返回结果
        List<String> result = new ArrayList<>();
        List<String> rankLogs = new ArrayList<>();

        for (int i = 0; i < rerankedResults.size(); i++) {
            Map<String, Object> item = rerankedResults.get(i);
            // API 返回的是原始文本（不做任何修改，只重新排序）
            String text = (String) ((Map<String, Object>) item.get("document")).get("text");
            Double score = null;
            if (item.containsKey("relevance_score")) {
                score = ((Number) item.get("relevance_score")).doubleValue();
            } else if (item.containsKey("score")) {
                score = ((Number) item.get("score")).doubleValue();
            }

            if (text != null) {
                result.add(text);

                // 通过内容字符串反向匹配找到原始 chunkId（因为 API 返回的是文本，不是 ID）
                String matchedChunkId = "unknown";
                for (Map.Entry<String, String> entry : idToContent.entrySet()) {
                    if (entry.getValue().equals(text)) {  // ← 精确匹配文本内容
                        matchedChunkId = idToChunkId.getOrDefault(entry.getKey(), "unknown");
                        break;
                    }
                }

                rankLogs.add(String.format("排名 %d: chunkId=%s, 分数=%.4f", i + 1, matchedChunkId, score != null ? score : 0.0));
            }
        }

        log.info("qwen3-rerank 重排序结果：{}", String.join("; ", rankLogs));
        log.info("重排序后返回{}条文档，原始合并{}条", result.size(), documents.size());

        return result;
    }

}
