package com.simpleaioj.controller;

import com.simpleaioj.common.Result;
import com.simpleaioj.dto.JudgeRequest;
import com.simpleaioj.service.JudgeService;
import com.simpleaioj.vo.JudgeResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/judge")
public class JudgeController {

    private final JudgeService judgeService;

    public JudgeController(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @PostMapping
    public CompletableFuture<Result<JudgeResponse>> judge(@RequestBody JudgeRequest request) {
        if (request == null || request.getQuestionId() == null) {
            return CompletableFuture.completedFuture(Result.fail("questionId is required"));
        }
        if (!StringUtils.hasText(request.getLanguage())) {
            return CompletableFuture.completedFuture(Result.fail("language is required"));
        }
        if (!StringUtils.hasText(request.getCode())) {
            return CompletableFuture.completedFuture(Result.fail("code is required"));
        }
        return judgeService.judge(request)
                .thenApply(Result::success);
    }
}
