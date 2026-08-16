package com.medpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitFilterTest {

    @Test
    void rejectsActualBytesWhenContentLengthIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/consult");
        request.setContent(new byte[RequestBodyLimitFilter.MAX_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamCalled = new AtomicBoolean(false);

        new RequestBodyLimitFilter(new ObjectMapper()).doFilter(
                request,
                response,
                (wrappedRequest, wrappedResponse) -> downstreamCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("请求内容超过 1 MiB 限制");
        assertThat(downstreamCalled).isFalse();
    }

    @Test
    void replaysAnAcceptedBodyToSpringMvc() throws Exception {
        byte[] body = "{\"text\":\"咳嗽\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/consult");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestBodyLimitFilter(new ObjectMapper()).doFilter(
                request,
                response,
                (wrappedRequest, wrappedResponse) -> assertThat(
                        wrappedRequest.getInputStream().readAllBytes()).isEqualTo(body));

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
