package com.simpleaioj.dto;

public class AiHelpRequest {

    private String questionTitle;
    private String questionContent;
    private String language;
    private String wrongCode;
    private String judgeStatus;
    private String judgeMessage;
    private String actualOutput;
    private String errorOutput;
    private String expectedOutput;
    private String failedInput;
    private Integer failedCaseIndex;

    public String getQuestionTitle() {
        return questionTitle;
    }

    public void setQuestionTitle(String questionTitle) {
        this.questionTitle = questionTitle;
    }

    public String getQuestionContent() {
        return questionContent;
    }

    public void setQuestionContent(String questionContent) {
        this.questionContent = questionContent;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getWrongCode() {
        return wrongCode;
    }

    public void setWrongCode(String wrongCode) {
        this.wrongCode = wrongCode;
    }

    public String getJudgeStatus() {
        return judgeStatus;
    }

    public void setJudgeStatus(String judgeStatus) {
        this.judgeStatus = judgeStatus;
    }

    public String getJudgeMessage() {
        return judgeMessage;
    }

    public void setJudgeMessage(String judgeMessage) {
        this.judgeMessage = judgeMessage;
    }

    public String getActualOutput() {
        return actualOutput;
    }

    public void setActualOutput(String actualOutput) {
        this.actualOutput = actualOutput;
    }

    public String getErrorOutput() {
        return errorOutput;
    }

    public void setErrorOutput(String errorOutput) {
        this.errorOutput = errorOutput;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public void setExpectedOutput(String expectedOutput) {
        this.expectedOutput = expectedOutput;
    }

    public String getFailedInput() {
        return failedInput;
    }

    public void setFailedInput(String failedInput) {
        this.failedInput = failedInput;
    }

    public Integer getFailedCaseIndex() {
        return failedCaseIndex;
    }

    public void setFailedCaseIndex(Integer failedCaseIndex) {
        this.failedCaseIndex = failedCaseIndex;
    }
}
