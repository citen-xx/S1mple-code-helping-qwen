package com.simpleaioj.controller;

import com.simpleaioj.dto.AiHelpRequest;
import com.simpleaioj.service.AiHelpService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiHelpService aiHelpService;

    public AiController(AiHelpService aiHelpService) {
        this.aiHelpService = aiHelpService;
    }

    @PostMapping(value = "/help", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter help(@RequestBody AiHelpRequest request) {
        return aiHelpService.help(request);
    }
}
