package com.simpleaioj.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.simpleaioj.dto.QuestionRequest;
import com.simpleaioj.dto.TestCaseRequest;
import com.simpleaioj.entity.Question;
import com.simpleaioj.entity.TestCase;
import com.simpleaioj.mapper.QuestionMapper;
import com.simpleaioj.mapper.TestCaseMapper;
import com.simpleaioj.service.QuestionService;
import com.simpleaioj.vo.QuestionDetailVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    private final TestCaseMapper testCaseMapper;

    public QuestionServiceImpl(TestCaseMapper testCaseMapper) {
        this.testCaseMapper = testCaseMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createQuestion(QuestionRequest request) {
        Question question = buildQuestion(request);
        this.save(question);
        saveTestCases(question.getId(), request.getTestCases());
        return question.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateQuestion(Long id, QuestionRequest request) {
        Question existingQuestion = this.getById(id);
        if (existingQuestion == null) {
            return false;
        }

        Question question = buildQuestion(request);
        question.setId(id);
        boolean updated = this.updateById(question);
        if (!updated) {
            return false;
        }

        testCaseMapper.delete(new LambdaUpdateWrapper<TestCase>()
                .eq(TestCase::getQuestionId, id));
        saveTestCases(id, request.getTestCases());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteQuestion(Long id) {
        testCaseMapper.delete(new LambdaUpdateWrapper<TestCase>()
                .eq(TestCase::getQuestionId, id));
        return this.removeById(id);
    }

    @Override
    public List<Question> listQuestions() {
        return this.list();
    }

    @Override
    public QuestionDetailVO getQuestionDetail(Long id) {
        Question question = this.getById(id);
        if (question == null) {
            return null;
        }

        List<TestCase> testCases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getQuestionId, id)
                .orderByAsc(TestCase::getId));

        QuestionDetailVO detailVO = new QuestionDetailVO();
        detailVO.setQuestion(question);
        detailVO.setTestCases(testCases);
        return detailVO;
    }

    private Question buildQuestion(QuestionRequest request) {
        Question question = new Question();
        question.setTitle(request.getTitle());
        question.setContent(request.getContent());
        question.setDifficulty(request.getDifficulty());
        question.setTimeLimit(request.getTimeLimit());
        question.setMemoryLimit(request.getMemoryLimit());
        return question;
    }

    private void saveTestCases(Long questionId, List<TestCaseRequest> testCaseRequests) {
        if (CollectionUtils.isEmpty(testCaseRequests)) {
            return;
        }

        List<TestCase> entities = new ArrayList<>();
        for (TestCaseRequest testCaseRequest : testCaseRequests) {
            TestCase testCase = new TestCase();
            testCase.setQuestionId(questionId);
            testCase.setInput(testCaseRequest.getInput());
            testCase.setExpectedOutput(testCaseRequest.getExpectedOutput());
            entities.add(testCase);
        }

        for (TestCase entity : entities) {
            testCaseMapper.insert(entity);
        }
    }
}
