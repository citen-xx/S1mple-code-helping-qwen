package com.simpleaioj.vo;

public class JudgeResponse {

    private String status;
    private String output;
    private String message;
    private String failedInput;
    private String expectedOutput;
    private String actualOutput;
    private Integer failedCaseIndex;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFailedInput() {
        return failedInput;
    }

    public void setFailedInput(String failedInput) {
        this.failedInput = failedInput;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public void setExpectedOutput(String expectedOutput) {
        this.expectedOutput = expectedOutput;
    }

    public String getActualOutput() {
        return actualOutput;
    }

    public void setActualOutput(String actualOutput) {
        this.actualOutput = actualOutput;
    }

    public Integer getFailedCaseIndex() {
        return failedCaseIndex;
    }

    public void setFailedCaseIndex(Integer failedCaseIndex) {
        this.failedCaseIndex = failedCaseIndex;
    }
}
