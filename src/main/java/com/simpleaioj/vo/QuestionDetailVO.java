package com.simpleaioj.vo;

import com.simpleaioj.entity.Question;
import com.simpleaioj.entity.TestCase;

import java.util.List;

public class QuestionDetailVO {

    private Question question;
    private List<TestCase> testCases;

    public Question getQuestion() {
        return question;
    }

    public void setQuestion(Question question) {
        this.question = question;
    }

    public List<TestCase> getTestCases() {
        return testCases;
    }

    public void setTestCases(List<TestCase> testCases) {
        this.testCases = testCases;
    }
}
