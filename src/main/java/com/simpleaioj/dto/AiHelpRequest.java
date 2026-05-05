package com.simpleaioj.dto;

public class AiHelpRequest {

    private String questionContent;
    private String wrongCode;
    private String errorOutput;

    public String getQuestionContent() {
        return questionContent;
    }

    public void setQuestionContent(String questionContent) {
        this.questionContent = questionContent;
    }

    public String getWrongCode() {
        return wrongCode;
    }


    public void setWrongCode(String wrongCode) {
        this.wrongCode = wrongCode;
    }

    public String getErrorOutput() {
        return errorOutput;
    }

    public void setErrorOutput(String errorOutput) {
        this.errorOutput = errorOutput;
    }
}
