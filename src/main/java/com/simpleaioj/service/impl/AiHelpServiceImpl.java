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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiHelpServiceImpl implements AiHelpService {

    private static final Logger log = LoggerFactory.getLogger(AiHelpServiceImpl.class);

    private static final long SSE_TIMEOUT_MS = 60000L;
    private static final int MAX_PROMPT_CHARS = 8000;
    private static final int MAX_QUESTION_TITLE_CHARS = 200;
    private static final int MAX_QUESTION_CONTENT_CHARS = 2000;
    private static final int MAX_LANGUAGE_CHARS = 30;
    private static final int MAX_WRONG_CODE_CHARS = 3000;
    private static final int MAX_FAILED_CASE_INDEX_CHARS = 20;
    private static final int MAX_JUDGE_STATUS_CHARS = 80;
    private static final int MAX_JUDGE_MESSAGE_CHARS = 500;
    private static final int MAX_ACTUAL_OUTPUT_CHARS = 1000;
    private static final int MAX_ERROR_OUTPUT_CHARS = 1500;
    private static final int MAX_EXPECTED_OUTPUT_CHARS = 1000;
    private static final int MAX_FAILED_INPUT_CHARS = 1000;
    private static final String TRUNCATED_MARK = "\n[内容过长，已截断]";
    private static final String SYSTEM_PROMPT = """
            你是一个编程练习场景下的算法辅导助手。
            你的回答必须使用中文，重点是帮助用户理解问题、定位错误和获得修改思路。
            不要直接给出完整可复制答案，优先给启发式建议。
            如果信息不足，请明确说明缺少哪些上下文。
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
        String requestId = UUID.randomUUID().toString();
        AtomicBoolean streamClosed = new AtomicBoolean(false);

        if (!isValidRequest(request)) {
            log.warn("AI help request rejected, requestId={}, reason=missing_required_fields", requestId);
            safeSend(requestId, emitter, streamClosed, "error",
                    "缺少必要参数：questionContent、wrongCode 至少需要提供，judgeMessage、actualOutput、errorOutput 至少提供一个");
            emitter.complete();
            return emitter;
        }

        PromptPayload promptPayload = buildPrompt(request);
        if (promptPayload.wasTruncated()) {
            log.info("AI help prompt truncated, requestId={}, finalChars={}, fields={}",
                    requestId, promptPayload.prompt().length(), promptPayload.truncatedFields());
        } else {
            log.info("AI help prompt built, requestId={}, finalChars={}", requestId, promptPayload.prompt().length());
        }

        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT),
                UserMessage.from(promptPayload.prompt())
        );

        emitter.onCompletion(() -> {
            streamClosed.set(true);
            log.info("AI SSE completed, requestId={}", requestId);
        });
        emitter.onTimeout(() -> {
            streamClosed.set(true);
            log.warn("AI SSE timed out, requestId={}", requestId);
            safeSend(requestId, emitter, streamClosed, "error", "AI 分析超时，请稍后重试");
            emitter.complete();
        });
        emitter.onError(error -> {
            streamClosed.set(true);
            log.warn("AI SSE emitter error, requestId={}, message={}",
                    requestId, error == null ? "unknown" : error.getMessage());
        });

        streamingModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                safeSend(requestId, emitter, streamClosed, "message", token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                streamClosed.set(true);
                log.info("AI model stream complete, requestId={}", requestId);
                safeSend(requestId, emitter, streamClosed, "done", "[DONE]");
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                streamClosed.set(true);
                log.error("AI help stream failed, requestId={}, message={}", requestId, error.getMessage(), error);
                safeSend(requestId, emitter, streamClosed, "error", "AI 分析暂时失败，请稍后重试或检查模型配置");
                emitter.complete();
            }
        });

        return emitter;
    }

    private PromptPayload buildPrompt(AiHelpRequest request) {
        PromptTruncation truncation = new PromptTruncation();
        String questionTitle = truncateField(request.getQuestionTitle(), MAX_QUESTION_TITLE_CHARS, false,
                "questionTitle", truncation);
        String questionContent = truncateField(request.getQuestionContent(), MAX_QUESTION_CONTENT_CHARS, false,
                "questionContent", truncation);
        String language = truncateField(request.getLanguage(), MAX_LANGUAGE_CHARS, false,
                "language", truncation);
        String failedCaseIndex = truncateField(request.getFailedCaseIndex() == null ? "" : String.valueOf(request.getFailedCaseIndex()),
                MAX_FAILED_CASE_INDEX_CHARS, false, "failedCaseIndex", truncation);
        String judgeStatus = truncateField(request.getJudgeStatus(), MAX_JUDGE_STATUS_CHARS, false,
                "judgeStatus", truncation);
        String judgeMessage = truncateField(request.getJudgeMessage(), MAX_JUDGE_MESSAGE_CHARS, false,
                "judgeMessage", truncation);
        String actualOutput = truncateField(request.getActualOutput(), MAX_ACTUAL_OUTPUT_CHARS, false,
                "actualOutput", truncation);
        String errorOutput = truncateField(resolveErrorOutput(request), MAX_ERROR_OUTPUT_CHARS, false,
                "errorOutput", truncation);
        String expectedOutput = truncateField(request.getExpectedOutput(), MAX_EXPECTED_OUTPUT_CHARS, false,
                "expectedOutput", truncation);
        String failedInput = truncateField(request.getFailedInput(), MAX_FAILED_INPUT_CHARS, false,
                "failedInput", truncation);
        String wrongCode = truncateField(request.getWrongCode(), MAX_WRONG_CODE_CHARS, true,
                "wrongCode", truncation);

        String prompt = """
                【题目标题】
                %s

                【题目描述】
                %s

                【编程语言】
                %s

                【判题状态】
                %s

                【失败用例序号】
                %s

                【判题信息】
                %s

                【实际输出】
                %s

                【错误输出】
                %s

                【期望输出】
                %s

                【失败输入】
                %s

                【用户代码】
                %s

                【补充说明】
                当前版本可能拿不到结构化失败用例；如果失败输入或期望输出为空，请结合已有判题信息给出分析。

                【请你完成】
                1. 判断最可能的问题类型。
                2. 指出错误位置或错误原因。
                3. 给出修改思路。
                4. 不要直接给完整可复制答案。
                5. 如果信息不足，请明确说明还缺什么。
                """.formatted(
                defaultText(questionTitle),
                defaultText(questionContent),
                defaultText(language),
                defaultText(judgeStatus),
                defaultText(failedCaseIndex),
                defaultText(judgeMessage),
                defaultText(actualOutput),
                defaultText(errorOutput),
                defaultText(expectedOutput),
                defaultText(failedInput),
                defaultText(wrongCode)
        );

        if (prompt.length() > MAX_PROMPT_CHARS) {
            truncation.markField("promptBudget");
            prompt = prompt.substring(0, MAX_PROMPT_CHARS - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
        }
        return new PromptPayload(prompt, truncation.wasTruncated(), truncation.summary());
    }

    private String resolveErrorOutput(AiHelpRequest request) {
        if (StringUtils.hasText(request.getErrorOutput())) {
            return request.getErrorOutput();
        }
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(request.getJudgeStatus())) {
            builder.append(request.getJudgeStatus());
        }
        if (StringUtils.hasText(request.getJudgeMessage())) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(request.getJudgeMessage());
        }
        if (StringUtils.hasText(request.getActualOutput())) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(request.getActualOutput());
        }
        return builder.toString();
    }

    private String truncateField(String value, int maxLength, boolean keepHeadAndTail,
                                 String fieldName, PromptTruncation truncation) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        truncation.markField(fieldName);
        if (!keepHeadAndTail || maxLength < 40) {
            return value.substring(0, maxLength - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
        }
        int available = maxLength - TRUNCATED_MARK.length();
        int headLength = available * 2 / 3;
        int tailLength = available - headLength;
        return value.substring(0, headLength) + TRUNCATED_MARK + value.substring(value.length() - tailLength);
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "未提供";
    }

    private void sendEvent(SseEmitter emitter, String eventName, String data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data == null ? "" : data));
    }

    private boolean safeSend(String requestId, SseEmitter emitter, AtomicBoolean streamClosed, String eventName, String data) {
        if (streamClosed.get()) {
            return false;
        }
        try {
            sendEvent(emitter, eventName, data);
            return true;
        } catch (IOException | IllegalStateException e) {
            streamClosed.set(true);
            log.warn("Failed to send SSE event, requestId={}, event={}", requestId, eventName, e);
            emitter.complete();
            return false;
        }
    }

    private boolean isValidRequest(AiHelpRequest request) {
        return request != null
                && StringUtils.hasText(request.getQuestionContent())
                && StringUtils.hasText(request.getWrongCode())
                && (StringUtils.hasText(request.getErrorOutput())
                || StringUtils.hasText(request.getJudgeMessage())
                || StringUtils.hasText(request.getActualOutput()));
    }

    private record PromptPayload(String prompt, boolean wasTruncated, String truncatedFields) {
    }

    private static class PromptTruncation {

        private final StringBuilder fields = new StringBuilder();

        void markField(String fieldName) {
            if (fields.length() > 0) {
                fields.append(", ");
            }
            fields.append(fieldName);
        }

        boolean wasTruncated() {
            return fields.length() > 0;
        }

        String summary() {
            return fields.length() == 0 ? "none" : fields.toString();
        }
    }
}
