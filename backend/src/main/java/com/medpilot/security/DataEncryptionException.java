package com.medpilot.security;

public class DataEncryptionException extends IllegalStateException {

    DataEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
