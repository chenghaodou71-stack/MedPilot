package com.medpilot.attachment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ConsultationAttachmentRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ConsultationAttachmentRetentionJob.class);
    private final ConsultationAttachmentService service;

    public ConsultationAttachmentRetentionJob(ConsultationAttachmentService service) {
        this.service = service;
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purgeExpired() {
        int purged = service.purgeExpired(Instant.now());
        if (purged > 0) {
            log.info("attachment_retention purged_count={}", purged);
        }
    }
}
