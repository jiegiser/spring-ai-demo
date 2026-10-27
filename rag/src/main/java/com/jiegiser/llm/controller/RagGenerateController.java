package com.jiegiser.llm.controller;

import com.jiegiser.llm.generate.SqlQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 生成控制器
 * <p>提供 Text-to-SQL 接口，将自然语言转换为 SQL 语句</p>
 */
@RestController
@RequestMapping("/rag/generate")
public class RagGenerateController {

    @Autowired
    private SqlQueryService sqlQueryService;

    /**
     * Text-to-SQL 接口：将自然语言转换为 SQL
     * <p>例："查询张三的信息" → "SELECT * FROM app.staff_info WHERE name = '张三'"</p>
     *
     * @param query 用户自然语言问题
     * @return 生成的 SQL 语句
     */
    @RequestMapping("/sql")
    public String sql(String query) {
        return sqlQueryService.text2sql(query);
    }
}
