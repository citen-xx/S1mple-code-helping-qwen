package com.simpleaioj.enums;

public enum JudgeStatus {

    ACCEPTED("Accepted"),
    WRONG_ANSWER("Wrong Answer"),
    COMPILE_ERROR("Compile Error"),
    RUNTIME_ERROR("Runtime Error"),
    TIME_LIMIT_EXCEEDED("Time Limit Exceeded"),
    SYSTEM_ERROR("System Error");

    private final String value;

    JudgeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
