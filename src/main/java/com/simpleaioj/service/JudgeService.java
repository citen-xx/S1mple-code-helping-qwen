package com.simpleaioj.service;

import com.simpleaioj.dto.JudgeRequest;
import com.simpleaioj.vo.JudgeResponse;

import java.util.concurrent.CompletableFuture;

public interface JudgeService {

    CompletableFuture<JudgeResponse> judge(JudgeRequest request);
}
