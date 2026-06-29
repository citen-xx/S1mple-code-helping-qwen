package com.simpleaioj.service.impl;

import com.simpleaioj.dto.JudgeRequest;
import com.simpleaioj.entity.Question;
import com.simpleaioj.entity.TestCase;
import com.simpleaioj.enums.JudgeStatus;
import com.simpleaioj.service.JudgeService;
import com.simpleaioj.service.QuestionService;
import com.simpleaioj.vo.JudgeResponse;
import com.simpleaioj.vo.QuestionDetailVO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class JudgeServiceImpl implements JudgeService {

    private static final long DEFAULT_TIME_LIMIT_MS = 1000L;
    private static final long COMPILE_TIMEOUT_MS = 10000L;
    private static final long CPP_MEMORY_LIMIT_KB = 262144L;
    private static final int CPP_MAX_PROCESSES = 15;
    private static final String LANGUAGE_JAVA = "java";
    private static final String LANGUAGE_CPP = "cpp";

    private final QuestionService questionService;
    private final TaskExecutor judgeExecutor;
    private final Semaphore judgeSemaphore = new Semaphore(10);

    public JudgeServiceImpl(QuestionService questionService,
                            @Qualifier("judgeExecutor") TaskExecutor judgeExecutor) {
        this.questionService = questionService;
        this.judgeExecutor = judgeExecutor;
    }

    @Override
    public CompletableFuture<JudgeResponse> judge(JudgeRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;
            Path workDir = null;
            try {
                judgeSemaphore.acquire();
                acquired = true;

                QuestionDetailVO detailVO = questionService.getQuestionDetail(request.getQuestionId());
                if (detailVO == null || detailVO.getQuestion() == null) {
                    return buildResponse(JudgeStatus.SYSTEM_ERROR, "", "Question not found");
                }
                if (CollectionUtils.isEmpty(detailVO.getTestCases())) {
                    return buildResponse(JudgeStatus.SYSTEM_ERROR, "", "No test cases found");
                }

                String language = normalizeLanguage(request.getLanguage());
                workDir = Files.createTempDirectory("simple-ai-oj-judge-");

                Question question = detailVO.getQuestion();
                long timeLimitMs = resolveTimeLimit(question);
                int memoryLimitMb = (question != null && question.getMemoryLimit() != null && question.getMemoryLimit() > 0)
                        ? question.getMemoryLimit() : 128;
                SourceConfig sourceConfig = buildSourceConfig(language, workDir, memoryLimitMb, timeLimitMs);
                Files.writeString(sourceConfig.getSourcePath(), request.getCode(), StandardCharsets.UTF_8);

                ProcessResult compileResult = executeCommand(sourceConfig.getCompileCommand(), workDir, null, COMPILE_TIMEOUT_MS, true);
                if (compileResult.isTimedOut()) {
                    return buildResponse(JudgeStatus.COMPILE_ERROR, compileResult.getStdout(), "Compile timeout");
                }
                if (compileResult.getExitCode() != 0) {
                    return buildResponse(JudgeStatus.COMPILE_ERROR, compileResult.getStdout(), "Compile failed");
                }

                return runTestCases(sourceConfig, question, detailVO.getTestCases(), workDir);
            } catch (IllegalArgumentException e) {
                return buildResponse(JudgeStatus.SYSTEM_ERROR, "", e.getMessage());
            } catch (IOException e) {
                return buildResponse(JudgeStatus.SYSTEM_ERROR, "", "Failed to create temp files or execute command: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return buildResponse(JudgeStatus.SYSTEM_ERROR, "", "Judge thread interrupted");
            } finally {
                if (acquired) {
                    judgeSemaphore.release();
                }
                deleteDirectoryQuietly(workDir);
            }
        }, judgeExecutor);
    }

    private JudgeResponse runTestCases(SourceConfig sourceConfig, Question question, List<TestCase> testCases, Path workDir)
            throws IOException, InterruptedException {
        String lastOutput = "";
        long timeLimitMs = resolveTimeLimit(question);

        for (int i = 0; i < testCases.size(); i++) {
            TestCase testCase = testCases.get(i);
            ProcessResult runResult = executeCommand(sourceConfig.getRunCommand(), workDir, testCase.getInput(), timeLimitMs, false);
            lastOutput = runResult.getStdout();
            int failedCaseIndex = i + 1;
            String failedInput = testCase.getInput();

            if (runResult.isTimedOut()) {
                return buildResponse(JudgeStatus.TIME_LIMIT_EXCEEDED, lastOutput,
                        "Test case " + failedCaseIndex + " timed out",
                        failedInput, null, normalizeOutput(runResult.getStdout()), failedCaseIndex);
            }
            if (runResult.getExitCode() != 0) {
                String message = normalizeOutput(runResult.getStderr());
                if (message.isEmpty()) {
                    message = "Program exited abnormally";
                }
                return buildResponse(JudgeStatus.RUNTIME_ERROR, lastOutput, message,
                        failedInput, null, normalizeOutput(runResult.getStdout()), failedCaseIndex);
            }

            String actualOutput = normalizeOutput(runResult.getStdout());
            String expectedOutput = normalizeOutput(testCase.getExpectedOutput());
            if (!expectedOutput.equals(actualOutput)) {
                return buildResponse(JudgeStatus.WRONG_ANSWER, runResult.getStdout(),
                        "Wrong answer on test case " + failedCaseIndex,
                        failedInput, expectedOutput, actualOutput, failedCaseIndex);
            }
        }

        return buildResponse(JudgeStatus.ACCEPTED, lastOutput, "All test cases passed");
    }

    private String normalizeLanguage(String language) {
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if (LANGUAGE_JAVA.equals(normalized)) {
            return LANGUAGE_JAVA;
        }
        if (LANGUAGE_CPP.equals(normalized) || "c++".equals(normalized) || "cc".equals(normalized) || "cxx".equals(normalized)) {
            return LANGUAGE_CPP;
        }
        throw new IllegalArgumentException("Only java and cpp are supported");
    }

    private SourceConfig buildSourceConfig(String language, Path workDir, int memoryLimitMb, long timeLimitMs) {
        int timeLimitSeconds = (int) Math.ceil(timeLimitMs / 1000.0);
        if (LANGUAGE_JAVA.equals(language)) {
            Path sourcePath = workDir.resolve("Main.java");
            List<String> runCommand = isWindows()
                    ? List.of("java", "-Dfile.encoding=UTF-8", "-Xmx" + memoryLimitMb + "m", "-cp", ".", "Main")
                    : List.of("bash", "-c",
                    String.format(
                            "ulimit -t %d -f 0 -u %d -n 16 && timeout %d java -Dfile.encoding=UTF-8 -Xmx%dm -cp . Main",
                            timeLimitSeconds + 2, CPP_MAX_PROCESSES, timeLimitSeconds, memoryLimitMb
                    ));
            return new SourceConfig(
                    sourcePath,
                    List.of("javac", "-encoding", "UTF-8", "Main.java"),
                    runCommand
            );
        }

        String binaryName = isWindows() ? "main.exe" : "main";
        Path sourcePath = workDir.resolve("main.cpp");
        String runCommand = isWindows() ? binaryName : "./" + binaryName;
        return new SourceConfig(
                sourcePath,
                List.of("g++", "-std=c++17", "-O2", "main.cpp", "-o", binaryName),
                isWindows()
                        ? List.of(runCommand)
                        : List.of("bash", "-c",
                        String.format(
                                "ulimit -v %d -t %d -f 0 -u %d -n 16 && timeout %d ./%s",
                                CPP_MEMORY_LIMIT_KB,
                                timeLimitSeconds + 2,
                                CPP_MAX_PROCESSES,
                                timeLimitSeconds,
                                binaryName
                        ))
        );
    }

    private ProcessResult executeCommand(List<String> command, Path workDir, String input, long timeoutMs, boolean mergeErrorStream)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(mergeErrorStream);

        Process process = processBuilder.start();
        CompletableFuture<String> stdoutFuture = readStreamAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = mergeErrorStream
                ? CompletableFuture.completedFuture("")
                : readStreamAsync(process.getErrorStream());

        if (input != null) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(input);
            }
        } else {
            process.getOutputStream().close();
        }

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            return new ProcessResult(-1, safeJoin(stdoutFuture), safeJoin(stderrFuture), true);
        }

        int exitCode = process.exitValue();
        return new ProcessResult(exitCode, safeJoin(stdoutFuture), safeJoin(stderrFuture),
                isTimeoutExit(command, exitCode));
    }

    private boolean isTimeoutExit(List<String> command, int exitCode) {
        return exitCode == 124
                && command != null
                && command.size() >= 3
                && "bash".equals(command.get(0))
                && "-c".equals(command.get(1))
                && command.get(2).contains("timeout ");
    }

    private CompletableFuture<String> readStreamAsync(InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream in = inputStream) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String safeJoin(CompletableFuture<String> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException uncheckedIOException) {
                throw uncheckedIOException.getCause();
            }
            throw e;
        }
    }

    private long resolveTimeLimit(Question question) {
        if (question == null || question.getTimeLimit() == null || question.getTimeLimit() <= 0) {
            return DEFAULT_TIME_LIMIT_MS;
        }
        return question.getTimeLimit();
    }

    private String normalizeOutput(String output) {
        return output == null ? "" : output.trim();
    }

    private JudgeResponse buildResponse(JudgeStatus status, String output, String message) {
        return buildResponse(status, output, message, null, null, null, null);
    }

    private JudgeResponse buildResponse(JudgeStatus status, String output, String message,
                                        String failedInput, String expectedOutput,
                                        String actualOutput, Integer failedCaseIndex) {
        JudgeResponse response = new JudgeResponse();
        response.setStatus(status.getValue());
        response.setOutput(output == null ? "" : output);
        response.setMessage(message);
        response.setFailedInput(failedInput);
        response.setExpectedOutput(expectedOutput);
        response.setActualOutput(actualOutput);
        response.setFailedCaseIndex(failedCaseIndex);
        return response;
    }

    private void deleteDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        try (var pathStream = Files.walk(directory)) {
            pathStream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static class SourceConfig {

        private final Path sourcePath;
        private final List<String> compileCommand;
        private final List<String> runCommand;

        private SourceConfig(Path sourcePath, List<String> compileCommand, List<String> runCommand) {
            this.sourcePath = sourcePath;
            this.compileCommand = compileCommand;
            this.runCommand = runCommand;
        }

        public Path getSourcePath() {
            return sourcePath;
        }

        public List<String> getCompileCommand() {
            return compileCommand;
        }

        public List<String> getRunCommand() {
            return runCommand;
        }
    }

    private static class ProcessResult {

        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean timedOut;

        private ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public boolean isTimedOut() {
            return timedOut;
        }
    }
}
