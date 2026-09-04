package com.medpilot.attachment;

import com.medpilot.consult.SessionOwnershipService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultationAttachmentServiceTest {

    @Test
    void fileDeletionFailurePreservesDatabaseMetadata() {
        ConsultationAttachmentRepository repository = mock(ConsultationAttachmentRepository.class);
        ConsultationAttachmentStorage storage = mock(ConsultationAttachmentStorage.class);
        SessionOwnershipService ownership = mock(SessionOwnershipService.class);
        ConsultationAttachmentService service = new ConsultationAttachmentService(
                repository, storage, ownership, 30);
        ConsultationAttachment attachment = new ConsultationAttachment(
                2L,
                "1779673a-c983-47e4-9715-f2d9548f469a",
                new ConsultationAttachmentStorage.StoredAttachment(
                        "2c293933-6590-4bfc-b0e8-507d3063c90b.bin",
                        "note.txt", "text/plain", 4L, "a".repeat(64),
                        AttachmentKind.TEXT, "note"),
                "note",
                30);
        when(repository.findByIdAndUserId(attachment.getId(), 2L))
                .thenReturn(Optional.of(attachment));
        org.mockito.Mockito.doThrow(new AttachmentStorageException(
                        "attachment could not be deleted", new IOException("locked")))
                .when(storage).delete(attachment.getStorageKey());

        assertThatThrownBy(() -> service.delete(2L, attachment.getId()))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageContaining("could not be deleted");

        verify(repository, never()).delete(attachment);
    }
}
