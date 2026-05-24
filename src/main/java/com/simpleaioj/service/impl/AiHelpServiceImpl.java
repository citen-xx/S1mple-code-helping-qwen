package com.simpleaioj.service.impl;

import com.simpleaioj.dto.AiHelpRequest;
import com.simpleaioj.enums.JudgeStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final int MAX_EXPECTED_OUTPUT_CHARS = 1000;
    private static final int MAX_FAILED_INPUT_CHARS = 1000;
    private static final int DEFAULT_ERROR_OUTPUT_CHARS = 1500;
    private static final int JAVA_COMPILE_ERROR_OUTPUT_CHARS = 3000;
    private static final int CPP_COMPILE_ERROR_OUTPUT_CHARS = 4000;
    private static final int CPP_RUNTIME_ERROR_OUTPUT_CHARS = 2500;
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
                    "缺少必要参数：wrongCode 必须提供，judgeMessage、actualOutput、errorOutput 至少提供一个");
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
        String judgeStatus = truncateField(request.getJudgeStatus(), MAX_JUDGE_STATUS_CHARS, false,
                "judgeStatus", truncation);
        String prompt;
        if (JudgeStatus.COMPILE_ERROR.getValue().equals(judgeStatus)) {
            prompt = buildCompileErrorPrompt(request, truncation, judgeStatus);
        } else if (JudgeStatus.RUNTIME_ERROR.getValue().equals(judgeStatus)) {
            prompt = buildRuntimeErrorPrompt(request, truncation, judgeStatus);
        } else if (JudgeStatus.WRONG_ANSWER.getValue().equals(judgeStatus)) {
            prompt = buildWrongAnswerPrompt(request, truncation, judgeStatus);
        } else {
            prompt = buildGenericPrompt(request, truncation, judgeStatus);
        }

        if (prompt.length() > MAX_PROMPT_CHARS) {
            truncation.markField("promptBudget");
            prompt = prompt.substring(0, MAX_PROMPT_CHARS - TRUNCATED_MARK.length()) + TRUNCATED_MARK;
        }
        return new PromptPayload(prompt, truncation.wasTruncated(), truncation.summary());
    }

    private String buildCompileErrorPrompt(AiHelpRequest request, PromptTruncation truncation, String judgeStatus) {
        String rawLanguage = request.getLanguage();
        String questionTitle = truncateField(request.getQuestionTitle(), MAX_QUESTION_TITLE_CHARS, false,
                "questionTitle", truncation);
        String questionContent = truncateField(request.getQuestionContent(), MAX_QUESTION_CONTENT_CHARS, false,
                "questionContent", truncation);
        String language = truncateField(rawLanguage, MAX_LANGUAGE_CHARS, false,
                "language", truncation);
        String rawErrorOutput = resolveCompileErrorOutput(request);
        String processedErrorOutput = preprocessCompilerError(rawErrorOutput, rawLanguage);
        String errorOutput = truncateField(processedErrorOutput,
                resolveErrorOutputChars(rawLanguage, request.getJudgeStatus()), false, "errorOutput", truncation);
        String wrongCode = truncateField(request.getWrongCode(), MAX_WRONG_CODE_CHARS, true,
                "wrongCode", truncation);

        StringBuilder prompt = new StringBuilder();
        appendSection(prompt, "场景说明", "当前提交在编译阶段失败，请优先分析语法、类型、导入、类名或方法签名、API 调用方式等编译期问题。");
        appendOptionalSection(prompt, "题目标题", questionTitle);
        appendOptionalSection(prompt, "题目描述", questionContent);
        appendSection(prompt, "编程语言", defaultText(language));
        appendSection(prompt, "判题状态", defaultText(judgeStatus));
        appendSection(prompt, "编译错误输出", defaultText(errorOutput));
        appendSection(prompt, "用户代码", defaultText(wrongCode));
        appendSection(prompt, "请你完成", """
                1. 结合编译器报错，定位最可能出错的代码位置或代码段。
                2. 用中文解释报错含义，以及为什么会触发这个编译错误。
                3. 给出具体修改思路；如果修复编译错误需要，可以直接给出修正后的完整相关代码片段，但重点应放在解释错误和修复原因，不要扩展成整题完整题解。
                4. 如果题目要求、函数签名或上下文不足以判断，请明确说明还缺什么。
                """);
        return prompt.toString();
    }

    private String buildRuntimeErrorPrompt(AiHelpRequest request, PromptTruncation truncation, String judgeStatus) {
        String rawLanguage = request.getLanguage();
        String questionTitle = truncateField(request.getQuestionTitle(), MAX_QUESTION_TITLE_CHARS, false,
                "questionTitle", truncation);
        String questionContent = truncateField(request.getQuestionContent(), MAX_QUESTION_CONTENT_CHARS, false,
                "questionContent", truncation);
        String language = truncateField(rawLanguage, MAX_LANGUAGE_CHARS, false,
                "language", truncation);
        String failedCaseIndex = truncateField(request.getFailedCaseIndex() == null ? "" : String.valueOf(request.getFailedCaseIndex()),
                MAX_FAILED_CASE_INDEX_CHARS, false, "failedCaseIndex", truncation);
        String errorOutput = truncateField(resolveRuntimeErrorOutput(request),
                resolveErrorOutputChars(rawLanguage, request.getJudgeStatus()), false, "errorOutput", truncation);
        String failedInput = truncateField(request.getFailedInput(), MAX_FAILED_INPUT_CHARS, false,
                "failedInput", truncation);
        String wrongCode = truncateField(request.getWrongCode(), MAX_WRONG_CODE_CHARS, true,
                "wrongCode", truncation);

        StringBuilder prompt = new StringBuilder();
        appendSection(prompt, "场景说明", "当前提交已通过编译，但在运行时失败，请优先分析异常触发条件、边界情况和崩溃原因。");
        appendOptionalSection(prompt, "题目标题", questionTitle);
        appendOptionalSection(prompt, "题目描述", questionContent);
        appendSection(prompt, "编程语言", defaultText(language));
        appendSection(prompt, "判题状态", defaultText(judgeStatus));
        appendOptionalSection(prompt, "失败用例序号", failedCaseIndex);
        appendOptionalSection(prompt, "失败输入", failedInput);
        appendOptionalSection(prompt, "运行错误输出", errorOutput);
        appendSection(prompt, "用户代码", defaultText(wrongCode));
        appendSection(prompt, "请你完成", """
                1. 结合运行错误输出，判断最可能的异常类型或崩溃原因，例如数组越界、空指针、除零、非法访问、递归过深、段错误或输入处理异常等。
                2. 结合失败输入和失败用例序号，分析为什么这个问题只会在该类输入下暴露，并指出最可疑的代码位置、变量状态或执行路径。
                3. 给出有针对性的排查顺序和修改思路，优先说明应该检查哪些边界条件、判空、下标范围、循环终止条件或资源访问逻辑。
                4. 不要直接给出整题完整可复制答案；重点是帮助用户定位 Runtime Error 的触发原因和修复方向。
                5. 如果信息仍不足以定位，请明确说明还缺少哪些上下文。
                """);
        return prompt.toString();
    }

    private String buildWrongAnswerPrompt(AiHelpRequest request, PromptTruncation truncation, String judgeStatus) {
        String questionTitle = truncateField(request.getQuestionTitle(), MAX_QUESTION_TITLE_CHARS, false,
                "questionTitle", truncation);
        String questionContent = truncateField(request.getQuestionContent(), MAX_QUESTION_CONTENT_CHARS, false,
                "questionContent", truncation);
        String language = truncateField(request.getLanguage(), MAX_LANGUAGE_CHARS, false,
                "language", truncation);
        String failedCaseIndex = truncateField(request.getFailedCaseIndex() == null ? "" : String.valueOf(request.getFailedCaseIndex()),
                MAX_FAILED_CASE_INDEX_CHARS, false, "failedCaseIndex", truncation);
        String judgeMessage = truncateField(request.getJudgeMessage(), MAX_JUDGE_MESSAGE_CHARS, false,
                "judgeMessage", truncation);
        String actualOutput = truncateField(request.getActualOutput(), MAX_ACTUAL_OUTPUT_CHARS, false,
                "actualOutput", truncation);
        String expectedOutput = truncateField(request.getExpectedOutput(), MAX_EXPECTED_OUTPUT_CHARS, false,
                "expectedOutput", truncation);
        String failedInput = truncateField(request.getFailedInput(), MAX_FAILED_INPUT_CHARS, false,
                "failedInput", truncation);
        String wrongCode = truncateField(request.getWrongCode(), MAX_WRONG_CODE_CHARS, true,
                "wrongCode", truncation);

        StringBuilder prompt = new StringBuilder();
        appendSection(prompt, "场景说明", "当前提交能够运行，但输出结果与预期不一致，请优先分析逻辑错误、边界条件、状态更新或题意理解偏差。");
        appendOptionalSection(prompt, "题目标题", questionTitle);
        appendOptionalSection(prompt, "题目描述", questionContent);
        appendSection(prompt, "编程语言", defaultText(language));
        appendSection(prompt, "判题状态", defaultText(judgeStatus));
        appendOptionalSection(prompt, "失败用例序号", failedCaseIndex);
        appendOptionalSection(prompt, "失败输入", failedInput);
        appendOptionalSection(prompt, "期望输出", expectedOutput);
        appendOptionalSection(prompt, "实际输出", actualOutput);
        appendOptionalSection(prompt, "判题信息", judgeMessage);
        appendSection(prompt, "用户代码", defaultText(wrongCode));
        appendSection(prompt, "请你完成", """
                1. 根据题意、失败输入、期望输出和实际输出，判断最可能的逻辑问题。
                2. 指出哪些边界条件、分支、循环、状态转移或输出格式最可能出错。
                3. 给出修改思路和建议的自测方向，不要直接给完整可复制答案。
                4. 如果现有信息不足以定位，请明确说明还缺什么。
                """);
        return prompt.toString();
    }

    private String buildGenericPrompt(AiHelpRequest request, PromptTruncation truncation, String judgeStatus) {
        String rawLanguage = request.getLanguage();
        String questionTitle = truncateField(request.getQuestionTitle(), MAX_QUESTION_TITLE_CHARS, false,
                "questionTitle", truncation);
        String questionContent = truncateField(request.getQuestionContent(), MAX_QUESTION_CONTENT_CHARS, false,
                "questionContent", truncation);
        String language = truncateField(rawLanguage, MAX_LANGUAGE_CHARS, false,
                "language", truncation);
        String failedCaseIndex = truncateField(request.getFailedCaseIndex() == null ? "" : String.valueOf(request.getFailedCaseIndex()),
                MAX_FAILED_CASE_INDEX_CHARS, false, "failedCaseIndex", truncation);
        String judgeMessage = truncateField(request.getJudgeMessage(), MAX_JUDGE_MESSAGE_CHARS, false,
                "judgeMessage", truncation);
        String actualOutput = truncateField(request.getActualOutput(), MAX_ACTUAL_OUTPUT_CHARS, false,
                "actualOutput", truncation);
        String errorOutput = truncateField(resolveGenericErrorOutput(request),
                resolveErrorOutputChars(rawLanguage, request.getJudgeStatus()), false, "errorOutput", truncation);
        String expectedOutput = truncateField(request.getExpectedOutput(), MAX_EXPECTED_OUTPUT_CHARS, false,
                "expectedOutput", truncation);
        String failedInput = truncateField(request.getFailedInput(), MAX_FAILED_INPUT_CHARS, false,
                "failedInput", truncation);
        String wrongCode = truncateField(request.getWrongCode(), MAX_WRONG_CODE_CHARS, true,
                "wrongCode", truncation);

        return """
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
    }

    private int resolveErrorOutputChars(String language, String judgeStatus) {
        if (!StringUtils.hasText(judgeStatus)) {
            return DEFAULT_ERROR_OUTPUT_CHARS;
        }
        String normalizedLanguage = normalizeLanguageKey(language);
        if (JudgeStatus.COMPILE_ERROR.getValue().equals(judgeStatus) && "cpp".equals(normalizedLanguage)) {
            return CPP_COMPILE_ERROR_OUTPUT_CHARS;
        }
        if (JudgeStatus.COMPILE_ERROR.getValue().equals(judgeStatus)) {
            return JAVA_COMPILE_ERROR_OUTPUT_CHARS;
        }
        if ("cpp".equals(normalizedLanguage)) {
            return CPP_RUNTIME_ERROR_OUTPUT_CHARS;
        }
        return DEFAULT_ERROR_OUTPUT_CHARS;
    }

    // g++ 报错里最容易淹没有效信息的是 include 链、模板实例化链和大段候选重载列表。
    // 这里优先保留首个 error: 周围的函数上下文、源码片段，以及距离最近的少量 note。
    // include 链只展示用户入口和最终落点，中间折叠成摘要；纯系统头文件实例化链压成一行说明。
    // 所有 error: 行都会保留，但默认丢掉只指向系统头文件内部的噪音 note，尽量把预算留给核心诊断。
    private String preprocessCompilerError(String rawError, String language) {
        if (!isCppLanguage(language) || !StringUtils.hasText(rawError)) {
            return rawError;
        }

        List<String> lines = compressIncludeChains(new ArrayList<>(List.of(rawError.split("\\R", -1))));
        List<String> result = new ArrayList<>();
        String pendingContext = null;
        int omittedTemplateChainLines = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }

            if (isCompilerContextLine(trimmed)) {
                pendingContext = line;
                continue;
            }

            if (isTemplateInstantiationLine(trimmed)) {
                if (containsUserSource(trimmed) || !isSystemHeaderLine(trimmed)) {
                    omittedTemplateChainLines = appendTemplateChainSummary(result, omittedTemplateChainLines);
                    appendUniqueLine(result, line);
                } else {
                    omittedTemplateChainLines++;
                }
                continue;
            }

            if (isErrorLine(trimmed)) {
                omittedTemplateChainLines = appendTemplateChainSummary(result, omittedTemplateChainLines);
                if (StringUtils.hasText(pendingContext)) {
                    appendUniqueLine(result, pendingContext);
                    pendingContext = null;
                }

                appendUniqueLine(result, line);
                boolean errorInSystemHeader = isSystemHeaderLine(trimmed);
                int nextIndex = i + 1;
                int keptExcerptLines = 0;
                while (nextIndex < lines.size()
                        && isSourceExcerptLine(lines.get(nextIndex))
                        && keptExcerptLines < 2) {
                    appendUniqueLine(result, lines.get(nextIndex));
                    keptExcerptLines++;
                    nextIndex++;
                }

                int keptNotes = 0;
                while (nextIndex < lines.size()) {
                    String nextLine = lines.get(nextIndex);
                    String nextTrimmed = nextLine.trim();
                    if (!StringUtils.hasText(nextTrimmed)) {
                        nextIndex++;
                        continue;
                    }
                    if (!isNoteLine(nextTrimmed)) {
                        break;
                    }
                    if (shouldKeepCompilerNote(nextTrimmed, errorInSystemHeader, keptNotes)) {
                        appendUniqueLine(result, nextLine);
                        keptNotes++;
                    }
                    nextIndex++;
                    while (nextIndex < lines.size() && isSourceExcerptLine(lines.get(nextIndex))) {
                        nextIndex++;
                    }
                }
                i = nextIndex - 1;
                continue;
            }

            if (isDiagnosticSummaryLine(trimmed)) {
                appendUniqueLine(result, line);
                continue;
            }

            if (containsUserSource(trimmed) && !isSystemHeaderLine(trimmed)) {
                omittedTemplateChainLines = appendTemplateChainSummary(result, omittedTemplateChainLines);
                appendUniqueLine(result, line);
            }
        }

        appendTemplateChainSummary(result, omittedTemplateChainLines);
        return result.isEmpty() ? rawError : String.join("\n", result);
    }

    private String resolveCompileErrorOutput(AiHelpRequest request) {
        String errorOutput = sanitizeCompositeErrorOutput(
                request.getErrorOutput(), request.getJudgeStatus(), request.getJudgeMessage());
        if (StringUtils.hasText(errorOutput)) {
            return errorOutput;
        }
        String judgeMessage = normalizeDiagnosticValue(request.getJudgeMessage());
        if (StringUtils.hasText(judgeMessage)) {
            return judgeMessage;
        }
        return normalizeDiagnosticValue(request.getActualOutput());
    }

    private String resolveRuntimeErrorOutput(AiHelpRequest request) {
        String judgeMessage = normalizeDiagnosticValue(request.getJudgeMessage());
        if (StringUtils.hasText(judgeMessage)) {
            return judgeMessage;
        }
        return sanitizeCompositeErrorOutput(request.getErrorOutput(), request.getJudgeStatus(), null);
    }

    private String resolveGenericErrorOutput(AiHelpRequest request) {
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

    private List<String> compressIncludeChains(List<String> lines) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (!isIncludeChainStart(trimmed)) {
                result.add(line);
                continue;
            }

            List<String> chain = new ArrayList<>();
            chain.add(line);
            int nextIndex = i + 1;
            while (nextIndex < lines.size() && isIncludeChainContinuation(lines.get(nextIndex).trim())) {
                chain.add(lines.get(nextIndex));
                nextIndex++;
            }

            if (chain.size() <= 2) {
                result.addAll(chain);
            } else {
                String userEntryLine = findUserEntryLine(chain);
                String deepestIncludeLine = chain.get(0);
                result.add(userEntryLine);
                result.add("[... 省略 " + (chain.size() - 2) + " 层 include 链 ...]");
                if (!deepestIncludeLine.equals(userEntryLine)) {
                    result.add(deepestIncludeLine);
                }
            }
            i = nextIndex - 1;
        }
        return result;
    }

    private String sanitizeCompositeErrorOutput(String rawErrorOutput, String judgeStatus, String judgeMessage) {
        if (!StringUtils.hasText(rawErrorOutput)) {
            return "";
        }
        List<String> lines = new ArrayList<>(List.of(rawErrorOutput.split("\\R", -1)));
        removeLeadingLine(lines, judgeStatus);
        removeLeadingLine(lines, judgeMessage);
        String sanitized = String.join("\n", lines).trim();
        return normalizeDiagnosticValue(sanitized);
    }

    private void removeLeadingLine(List<String> lines, String expectedLine) {
        if (!StringUtils.hasText(expectedLine) || lines.isEmpty()) {
            return;
        }
        while (!lines.isEmpty() && !StringUtils.hasText(lines.get(0))) {
            lines.remove(0);
        }
        if (!lines.isEmpty() && expectedLine.trim().equals(lines.get(0).trim())) {
            lines.remove(0);
        }
    }

    private String normalizeDiagnosticValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if ("No runtime output".equalsIgnoreCase(normalized)) {
            return "";
        }
        return normalized;
    }

    private void appendSection(StringBuilder prompt, String title, String value) {
        prompt.append("【").append(title).append("】\n")
                .append(value)
                .append("\n\n");
    }

    private void appendOptionalSection(StringBuilder prompt, String title, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        appendSection(prompt, title, value);
    }

    private int appendTemplateChainSummary(List<String> result, int omittedTemplateChainLines) {
        if (omittedTemplateChainLines > 0) {
            appendUniqueLine(result, "[... 省略 " + omittedTemplateChainLines + " 行标准库模板实例化链 ...]");
        }
        return 0;
    }

    private void appendUniqueLine(List<String> result, String line) {
        if (!StringUtils.hasText(line)) {
            return;
        }
        if (!result.isEmpty() && result.get(result.size() - 1).equals(line)) {
            return;
        }
        result.add(line);
    }

    private String findUserEntryLine(List<String> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (containsUserSource(chain.get(i))) {
                return chain.get(i);
            }
        }
        return chain.get(chain.size() - 1);
    }

    private boolean isCppLanguage(String language) {
        return "cpp".equals(normalizeLanguageKey(language));
    }

    private String normalizeLanguageKey(String language) {
        return StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean isErrorLine(String line) {
        return line.contains(" error: ");
    }

    private boolean isNoteLine(String line) {
        return line.contains(" note: ");
    }

    private boolean isCompilerContextLine(String line) {
        return line.contains(": In function ")
                || line.contains(": In member function ")
                || line.contains(": In static member function ")
                || line.contains(": In constructor ")
                || line.contains(": In destructor ")
                || line.contains(": In lambda function ")
                || line.contains(": At global scope")
                || line.contains(": In instantiation of ")
                || line.contains(": In substitution of ");
    }

    private boolean isTemplateInstantiationLine(String line) {
        return line.contains("required from")
                || line.contains("required by substitution of")
                || line.contains("In instantiation of")
                || line.contains("In substitution of");
    }

    private boolean isIncludeChainStart(String line) {
        return line.startsWith("In file included from ");
    }

    private boolean isIncludeChainContinuation(String line) {
        return line.startsWith("from ");
    }

    private boolean isDiagnosticSummaryLine(String line) {
        return line.startsWith("[... 省略 ");
    }

    private boolean isSourceExcerptLine(String line) {
        String trimmed = line.trim();
        if (!StringUtils.hasText(trimmed)) {
            return false;
        }
        if (isErrorLine(trimmed)
                || isNoteLine(trimmed)
                || isCompilerContextLine(trimmed)
                || isTemplateInstantiationLine(trimmed)
                || isIncludeChainStart(trimmed)
                || isIncludeChainContinuation(trimmed)
                || isDiagnosticSummaryLine(trimmed)) {
            return false;
        }
        return line.startsWith(" ")
                || line.startsWith("\t")
                || trimmed.startsWith("^")
                || trimmed.startsWith("~")
                || trimmed.startsWith("|");
    }

    private boolean shouldKeepCompilerNote(String line, boolean errorInSystemHeader, int keptNotes) {
        if (keptNotes >= 3) {
            return false;
        }
        if (errorInSystemHeader) {
            return true;
        }
        if (containsUserSource(line)) {
            return true;
        }
        if (line.contains("note: candidate:")) {
            return true;
        }
        return !isSystemHeaderLine(line);
    }

    private boolean containsUserSource(String line) {
        return line.contains("main.cpp");
    }

    private boolean isSystemHeaderLine(String line) {
        if (containsUserSource(line)) {
            return false;
        }
        return line.matches(".*(/usr/include|/usr/lib|include/c\\+\\+|include\\\\c\\+\\+|bits/|bits\\\\|/mingw|\\\\mingw|libstdc\\+\\+|x86_64-linux-gnu|clang/|clang\\\\).*");
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
