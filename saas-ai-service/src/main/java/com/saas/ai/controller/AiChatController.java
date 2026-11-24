package com.saas.ai.controller;

import com.saas.common.context.TenantContext;
import com.saas.common.result.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import org.springframework.http.MediaType;
import java.io.IOException;

import java.util.Collections;
import reactor.core.publisher.Flux;

@RestController
public class AiChatController {

    private final ChatClient chatClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // 加载 Lua 脚本
    private final RedisScript<Long> quotaScript = RedisScript.of(new ClassPathResource("ai_quota.lua"), Long.class);

    public AiChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping("/chat")
    public Result<String> chat(@RequestParam("message") String message) {
        String tenantId = TenantContext.getTenantId();

        // 1. 拼接当前租户的额度 Key
        String quotaKey = "ai:quota:" + tenantId;

        System.out.println("=== 尝试扣减额度，Key: " + quotaKey + " ==="); // 🌟 加这行

        // 2. 执行 Lua 脚本，原子扣减 1 次额度
        Long result = redisTemplate.execute(quotaScript, Collections.singletonList(quotaKey), "1");

        // 3. 判断扣减结果
        if (result == null || result == -1) {
            return Result.fail(403, "抱歉，您今日的 AI 调用额度已耗尽，请联系客服升级套餐。");
        }

        System.out.println("=== 收到 AI 对话请求，当前租户: " + tenantId + "，额度扣减成功 ===");

        String systemPrompt = "你是一个SaaS平台的智能助手。当前与你对话的租户ID是：" + tenantId + "。请用专业、友好的语气回答。";

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .call()
                .content();

        return Result.ok(response);
    }



    // 引入 import reactor.core.publisher.Flux;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam("message") String message) {
        String tenantId = TenantContext.getTenantId();
        String quotaKey = "ai:quota:" + tenantId;

        Long result = redisTemplate.execute(quotaScript, Collections.singletonList(quotaKey), "1");
        if (result == null || result == -1) {
            return Flux.just("❌ 抱歉，您今日的 AI 调用额度已耗尽。");
        }

        String systemPrompt = "你是一个SaaS平台的智能助手。当前与你对话的租户ID是：" + tenantId + "。请用专业、友好的语气回答。";

        // 直接返回 Flux，Spring 会自动处理 SSE 的 data: 和 \n\n 格式
        return chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .stream()
                .content();
    }
}