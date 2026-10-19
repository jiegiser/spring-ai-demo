package com.jiegiser.llm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * @Auther: changjie
 * @Date:2026/10/19
 * @Modified By:
 */
@SpringBootApplication
@EnableScheduling
public class FunctionCallApplication {

    public static void main(String[] args) {
        SpringApplication.run(FunctionCallApplication.class, args);
    }

}