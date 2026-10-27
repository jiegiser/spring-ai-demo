package com.jiegiser.llm.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;
@Service
public class PdfReaderStrategy implements DocumentReaderStrategy {
    @Override
    public boolean supports(File file) {

        String name = file.getName().toLowerCase();
        return name.endsWith(".pdf");
    }

    @Override
    public List<Document> read(File file) throws IOException {
        Resource resource = new FileSystemResource(file);

        // 读取配置
        PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(50)         // 忽略顶部50个单位的页眉
                .withPageBottomMargin(50)      // 忽略底部50个单位的页脚
                .withPagesPerDocument(1)       // 每一页作为一个 Document
                .withPageExtractedTextFormatter(new ExtractedTextFormatter.Builder()
                        .withNumberOfTopTextLinesToDelete(0) // 每页再额外删掉前0行
                        .build())
                .build();

        // 优先尝试按目录（TOC）分段，失败则回退到按页读取
        try {
            ParagraphPdfDocumentReader paragraphReader = new ParagraphPdfDocumentReader(resource, config);
            return paragraphReader.read();
        } catch (IllegalArgumentException e) {
            // PDF 没有 TOC 大纲，回退到按页读取
            PagePdfDocumentReader pageReader = new PagePdfDocumentReader(resource, config);
            return pageReader.read();
        }
    }
}
