package com.simpleaioj.controller;

import com.simpleaioj.common.Result;
import com.simpleaioj.dto.QuestionRequest;
import com.simpleaioj.entity.Question;
import com.simpleaioj.service.QuestionService;
import com.simpleaioj.vo.QuestionDetailVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionService questionService;

    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;
    }

    @PostMapping
    public Result<Long> createQuestion(@RequestBody QuestionRequest request) {
        return Result.success(questionService.createQuestion(request));
    }

    @PutMapping("/{id}")
    public Result<Boolean> updateQuestion(@PathVariable Long id, @RequestBody QuestionRequest request) {
        boolean updated = questionService.updateQuestion(id, request);
        if (!updated) {
            return Result.fail("Question not found");
        }
        return Result.success(true);
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> deleteQuestion(@PathVariable Long id) {
        boolean removed = questionService.deleteQuestion(id);
        if (!removed) {
            return Result.fail("Question not found");
        }
        return Result.success(true);
    }

    @GetMapping("/{id}")
    public Result<Question> getQuestionById(@PathVariable Long id) {
        Question question = questionService.getById(id);
        if (question == null) {
            return Result.fail("Question not found");
        }
        return Result.success(question);
    }

    @GetMapping
    public Result<List<Question>> listQuestions() {
        return Result.success(questionService.listQuestions());
    }

    @GetMapping("/{id}/detail")
    public Result<QuestionDetailVO> getQuestionDetail(@PathVariable Long id) {
        QuestionDetailVO detailVO = questionService.getQuestionDetail(id);
        if (detailVO == null) {
            return Result.fail("Question not found");
        }
        return Result.success(detailVO);
    }
}
