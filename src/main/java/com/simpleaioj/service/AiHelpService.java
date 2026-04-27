package com.simpleaioj.service;

import com.simpleaioj.dto.AiHelpRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AiHelpService {

    SseEmitter help(AiHelpRequest request);
}
