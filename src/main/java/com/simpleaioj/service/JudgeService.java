package com.simpleaioj.service;

import com.simpleaioj.dto.JudgeRequest;
import com.simpleaioj.vo.JudgeResponse;

public interface JudgeService {

    JudgeResponse judge(JudgeRequest request);
}
