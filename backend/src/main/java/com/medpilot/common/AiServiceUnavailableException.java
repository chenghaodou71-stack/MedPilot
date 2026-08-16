package com.medpilot.common;

public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(Throwable cause) {
        super("AI service unavailable", cause);
    }

    public AiServiceUnavailableException() {
        super("AI service unavailable");
    }
}
