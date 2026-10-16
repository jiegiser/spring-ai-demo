package com.jiegiser.llm.controller;/*
 * @Auther: changjie
 * @Date:2026/10/16
 * @Description:
 * @Modified By:
 */

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ollama")
public class OllamaController {
    @Autowired
    private OllamaChatModel ollamaChatModel;

    @GetMapping("/stream")
    public Flux<String> stream(String message, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        return ollamaChatModel.stream(message);
    }
}
