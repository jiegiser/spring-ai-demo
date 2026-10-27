package com.jiegiser.llm.router;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 查询路由服务
 * <p>根据用户问题类型，判断应该使用哪种数据源进行检索</p>
 *
 * <p>路由规则：</p>
 * <ul>
 *   <li>VECTOR - 语义相似性搜索、文档检索、内容推荐（向量数据库）</li>
 *   <li>GRAPH - 关系查询、知识图谱、实体关联（图数据库）</li>
 *   <li>RELATIONAL - 结构化数据查询、统计分析、精确匹配（关系型数据库）</li>
 * </ul>
 */
@Service
public class QueryRouteService {

    /** 数据源路由提示词：让大模型判断问题适合哪种数据库检索 */
    private static final String DATASOURCE_ROUTE_PROMPT = """
            你需要判断用户的查询问题适合使用哪种数据库进行检索。
            如果是语义相似性搜索、文档检索、内容推荐类问题，回答'VECTOR'
            如果是关系查询、知识图谱、实体关联类问题，回答'GRAPH'
            如果是结构化数据查询、统计分析、精确匹配类问题，回答'RELATIONAL'
            如果无法确定，请回答'VECTOR'
            只回答VECTOR、GRAPH或RELATIONAL，不要其他内容。
                            
            用户问题：
            {QUESTION}
            """;


    @Autowired
    private ChatModel chatModel;

    /**
     * 路由查询：判断问题适合哪种数据源
     *
     * @param query 用户问题
     * @return 数据源类型：VECTOR / GRAPH / RELATIONAL
     */
    public String route(String query) {
        PromptTemplate promptTemplate = new PromptTemplate(DATASOURCE_ROUTE_PROMPT);
        promptTemplate.add("QUESTION", query);

        // 调用大模型判断路由类型
        return chatModel.call(promptTemplate.create()).getResult().getOutput().getText();
    }

}
