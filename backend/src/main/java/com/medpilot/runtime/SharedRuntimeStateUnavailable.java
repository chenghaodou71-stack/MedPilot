package com.medpilot.runtime;

/** Raised when a required distributed safety control cannot be used. */
public class SharedRuntimeStateUnavailable extends RuntimeException {
    public SharedRuntimeStateUnavailable(String message) {
        super(message);
    }

    public SharedRuntimeStateUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
