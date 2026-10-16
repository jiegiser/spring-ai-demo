package com.jiegiser.llm.controller;/*
 * @Auther: changjie
 * @Date:2026/10/16
 * @Description:
 * @Modified By:
 */

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持久化记忆
 * 在 Spring AI 中，我们最熟悉的就是 MessageWindowChatMemory ，它默认是一个基于内存的短期记忆实现，它维护最多指定最大大小（默认值：20 条消息）的消息窗口。
 *
 * 当消息数量超过此限制时，较旧的消息会被踢出。这确保对话始终可以使用最新的上下文，同时保持内存使用有界。应用一旦重启，内存中的记忆就会丢失。
 *
 * Spring AI 中的记忆存储是基于 ChatMemoryRepository 这个接口扩展的，他提供了一些列实现用来做基于存储，
 * 包括 MessageWindowChatMemory 所使用到的 InMemoryChatMemoryRepository，这就是一种基于 ConcurrentHashMap 的本地内存的记忆方式。
 */
@RestController
@RequestMapping("/jdbc/memory")
public class JdbcChatMemoryController {
}
