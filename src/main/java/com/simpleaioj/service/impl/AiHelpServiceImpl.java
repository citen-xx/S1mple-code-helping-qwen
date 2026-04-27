package com.simpleaioj.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simpleaioj.config.DashScopeProperties;
import com.simpleaioj.dto.AiHelpRequest;
import com.simpleaioj.service.AiHelpService;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiHelpServiceImpl implements AiHelpService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final long SSE_TIMEOUT_MS = 0L;
    private static final String SYSTEM_PROMPT = "你是一个算法教练，请看这道题和用户的代码，指出哪里写错了，给出思路，不要直接给完整代码。请使用中文回答。";

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final DashScopeProperties dashScopeProperties;

    public AiHelpServiceImpl(OkHttpClient okHttpClient, ObjectMapper objectMapper, DashScopeProperties dashScopeProperties) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
        this.dashScopeProperties = dashScopeProperties;
    }

    @Override
    public SseEmitter help(AiHelpRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (!isValidRequest(request)) {
            sendAndComplete(emitter, "error", "questionContent、wrongCode、errorOutput 不能为空");
            return emitter;
        }
        if (!StringUtils.hasText(dashScopeProperties.getApiKey())) {
            sendAndComplete(emitter, "error", "dashscope.api-key 未配置");
            return emitter;
        }

        try {
            Request httpRequest = buildRequest(request);
            Call call = okHttpClient.newCall(httpRequest);

            emitter.onCompletion(call::cancel);
            emitter.onTimeout(() -> {
                call.cancel();
                trySend(emitter, "error", "SSE timeout");
                emitter.complete();
            });

            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    sendAndComplete(emitter, "error", "调用 DashScope 失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response; ResponseBody body = response.body()) {
                        if (!response.isSuccessful()) {
                            String errorBody = body == null ? "" : body.string();
                            sendAndComplete(emitter, "error", "DashScope HTTP " + response.code() + ": " + errorBody);
                            return;
                        }
                        if (body == null) {
                            sendAndComplete(emitter, "error", "DashScope response body is empty");
                            return;
                        }

                        forwardStream(body, emitter);
                    } catch (Exception e) {
                        sendAndComplete(emitter, "error", "解析流式响应失败: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            sendAndComplete(emitter, "error", "构造请求失败: " + e.getMessage());
        }

        return emitter;
    }

    private Request buildRequest(AiHelpRequest request) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", dashScopeProperties.getModel());
        payload.put("stream", true);
        payload.put("messages", List.of(
                buildMessage("system", SYSTEM_PROMPT),
                buildMessage("user", buildUserPrompt(request))
        ));

        String json = objectMapper.writeValueAsString(payload);
        return new Request.Builder()
                .url(dashScopeProperties.getEndpoint())
                .addHeader("Authorization", "Bearer " + dashScopeProperties.getApiKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(json, JSON_MEDIA_TYPE))
                .build();
    }

    private Map<String, String> buildMessage(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String buildUserPrompt(AiHelpRequest request) {
        return """
                题目内容：
                %s

                用户写错的代码：
                %s

                报错信息/错误输出：
                %s
                """.formatted(
                request.getQuestionContent(),
                request.getWrongCode(),
                request.getErrorOutput()
        );
    }

    private void forwardStream(ResponseBody body, SseEmitter emitter) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }

                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    trySend(emitter, "done", "[DONE]");
                    emitter.complete();
                    return;
                }

                String content = extractContent(data);
                if (StringUtils.hasText(content)) {
                    // 按块透传模型增量文本，前端可直接用于打字机效果。
                    trySend(emitter, "message", content);
                }
            }
        }

        emitter.complete();
    }

    private String extractContent(String data) throws IOException {
        JsonNode root = objectMapper.readTree(data);

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("delta").path("content").asText("");
        }

        JsonNode outputChoices = root.path("output").path("choices");
        if (outputChoices.isArray() && !outputChoices.isEmpty()) {
            return outputChoices.get(0).path("message").path("content").asText("");
        }

        return "";
    }

    private boolean isValidRequest(AiHelpRequest request) {
        return request != null
                && StringUtils.hasText(request.getQuestionContent())
                && StringUtils.hasText(request.getWrongCode())
                && StringUtils.hasText(request.getErrorOutput());
    }

    private void sendAndComplete(SseEmitter emitter, String eventName, String data) {
        trySend(emitter, eventName, data);
        emitter.complete();
    }

    private void trySend(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException ignored) {
            emitter.completeWithError(ignored);
        }
    }
}
