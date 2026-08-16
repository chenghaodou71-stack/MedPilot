package com.medpilot.common;

import org.springframework.http.HttpStatus;

public class AiServiceRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String clientMessage;

    public AiServiceRequestException(HttpStatus status, String clientMessage, Throwable cause) {
        super(clientMessage, cause);
        this.status = status;
        this.clientMessage = clientMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getClientMessage() {
        return clientMessage;
    }
}
