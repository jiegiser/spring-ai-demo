package com.jiegiser.llm.controller;


import com.jiegiser.llm.cleaner.DocumentCleaner;
import com.jiegiser.llm.es.ElasticSearchService;
import com.jiegiser.llm.es.EsDocumentChunk;
import com.jiegiser.llm.reader.DocumentReaderFactory;
import com.jiegiser.llm.splitter.OverlapParagraphTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;

@RestController
@RequestMapping("/rag/es")
public class RagEsController {


    @Autowired
    private DocumentReaderFactory selector;

    @Autowired
    private ElasticSearchService elasticSearchService;

    @RequestMapping("write")
    public String write(String filePath) throws Exception {
        // 1. 加载文档
        List<Document> documents = selector.read(new File(filePath));

        // 2. 文本清洗
        documents = DocumentCleaner.cleanDocuments(documents);

        // 3. 文档分片
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(
                // 每块最大字符数
                200,
                // 块之间重叠 100 字符
                50);
        List<Document> apply = splitter.apply(documents);

        // 4. 存储到 ES
        List<EsDocumentChunk> esDocs = apply.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        elasticSearchService.bulkIndex(esDocs);
        return "success";
    }

    @RequestMapping("search")
    public List<EsDocumentChunk> search(String keyword) throws Exception {
        return elasticSearchService.searchByKeyword(keyword);
    }
}
