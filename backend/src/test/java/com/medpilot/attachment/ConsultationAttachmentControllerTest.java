package com.medpilot.attachment;

import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsultationAttachmentControllerTest {

    @Test
    void deletionStorageFailureReturnsObservableServiceUnavailableResponse() {
        ConsultationAttachmentService service = mock(ConsultationAttachmentService.class);
        UserRepository users = mock(UserRepository.class);
        Authentication authentication = mock(Authentication.class);
        User user = mock(User.class);
        when(authentication.getName()).thenReturn("user");
        when(user.getId()).thenReturn(2L);
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        doThrow(new AttachmentStorageException(
                "attachment could not be deleted", new IOException("locked")))
                .when(service).delete(2L, "attachment-1");
        ConsultationAttachmentController controller =
                new ConsultationAttachmentController(service, users);

        ResponseEntity<ApiResponse<Void>> response =
                controller.delete("attachment-1", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("附件文件删除失败，元数据已保留");
    }
}
