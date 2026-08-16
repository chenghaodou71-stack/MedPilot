package com.medpilot.attachment;

public class AttachmentTooLargeException extends RuntimeException {

    public AttachmentTooLargeException() {
        super("Attachment exceeds the 10 MB limit");
    }
}
