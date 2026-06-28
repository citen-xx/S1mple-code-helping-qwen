package com.simpleaioj.service.impl;

import com.simpleaioj.dto.AiHelpRequest;
import com.simpleaioj.service.AiHelpService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@Service
public class AiHelpServiceImpl implements AiHelpService {

    private static final long SSE_TIMEOUT_MS = 60000L;
    private static final String SYSTEM_PROMPT = """
            你是一个算法教练，请看这道题和用户的代码，指出哪里写错了，给出思路，不要直接给完整代码。
            你的回答必须使用中文。
            如果联网搜索能帮助判断最新资料、库版本、报错背景或相关知识点，请主动使用联网搜索能力。
            回答时优先指出：
            1. 题意理解是否正确
            2. 代码错误位置
            3. 修改思路
            4. 调试建议
            """;

    private final QwenStreamingChatModel streamingModel;

    public AiHelpServiceImpl(
            @Value("${dashscope.api-key}") String apiKey,
            @Value("${dashscope.model:qwen-plus}") String modelName,
            @Value("${dashscope.enable-search:true}") boolean enableSearch,
            @Value("${dashscope.base-url:}") String baseUrl) {
        QwenStreamingChatModel.QwenStreamingChatModelBuilder builder = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .enableSearch(enableSearch);

        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }

        this.streamingModel = builder.build();
    }

    @Override
    public SseEmitter help(AiHelpRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (!isValidRequest(request)) {
            emitter.completeWithError(new IllegalArgumentException("questionContent、wrongCode、errorOutput 不能为空"));
            return emitter;
        }

        // 当前项目的请求字段实际是 questionContent / wrongCode / errorOutput。
        // 如果你的 DTO 后续改成 request.getQuestion() 之类的单字段结构，只需要替换这里的 prompt 拼接逻辑即可。
        String userPrompt = buildPrompt(request);

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(userPrompt)
        );

        emitter.onTimeout(emitter::complete);

        streamingModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                try {
                    // 当前先按纯文本 token 直接推送给前端。
                    // 如果前端需要 JSON 格式，可改成 emitter.send(Map.of("content", token))。
                    emitter.send(token);
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                emitter.completeWithError(error);
            }
        });

        return emitter;
    }

    private String buildPrompt(AiHelpRequest request) {
        return """
                题目内容：
                %s

                用户写错的代码：
                %s

                报错信息 / 错误输出：
                %s
                """.formatted(
                request.getQuestionContent(),
                request.getWrongCode(),
                request.getErrorOutput()
        );
    }

    private boolean isValidRequest(AiHelpRequest request) {
        return request != null
                && StringUtils.hasText(request.getQuestionContent())
                && StringUtils.hasText(request.getWrongCode())
                && StringUtils.hasText(request.getErrorOutput());
    }
}
