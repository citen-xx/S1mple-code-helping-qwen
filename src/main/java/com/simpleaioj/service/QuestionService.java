package com.simpleaioj.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.simpleaioj.dto.QuestionRequest;
import com.simpleaioj.entity.Question;
import com.simpleaioj.vo.QuestionDetailVO;

import java.util.List;

public interface QuestionService extends IService<Question> {

    Long createQuestion(QuestionRequest request);

    boolean updateQuestion(Long id, QuestionRequest request);

    boolean deleteQuestion(Long id);

    List<Question> listQuestions();

    QuestionDetailVO getQuestionDetail(Long id);
}
