package com.medpilot.consult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.health.HealthProfileContextService;
import com.medpilot.monitor.LiveTraceRegistry;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsultControllerCancellationTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";

    private final AiConsultClient aiClient = mock(AiConsultClient.class);
    private final SessionOwnershipService ownership = mock(SessionOwnershipService.class);
    private final ConsultationPersistenceService persistence = mock(ConsultationPersistenceService.class);
    private final ConsultationMessageService messages = mock(ConsultationMessageService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final HealthProfileContextService healthProfiles = mock(HealthProfileContextService.class);
    private final Authentication authentication = mock(Authentication.class);
    private final LiveTraceRegistry liveTraces = new LiveTraceRegistry(new ObjectMapper());
    private ConsultController controller;

    @BeforeEach
    void setUp() {
        when(authentication.getName()).thenReturn("user");
        User user = mock(User.class);
        when(user.getId()).thenReturn(2L);
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        when(healthProfiles.resolveForUser(2L)).thenReturn(Map.of());
        when(messages.historyFor(2L, SESSION_ID)).thenReturn(List.of());
        controller = new ConsultController(
                aiClient, ownership, persistence, messages, liveTraces,
                users, healthProfiles, new ObjectMapper());
    }

    @Test
    void cancellingTheClientUsesOneSubscriptionCancelsUpstreamAndNeverPersistsSuccess() {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        AtomicInteger subscriptions = new AtomicInteger();
        CountDownLatch firstEvent = new CountDownLatch(1);
        Flux<String> upstream = Flux.defer(() -> {
            subscriptions.incrementAndGet();
            return Flux.concat(
                    Flux.just(nodeStarted()).doOnNext(ignored -> firstEvent.countDown()),
                    Flux.<String>never().doOnCancel(() -> upstreamCancelled.set(true)));
        });
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of()))
                .thenReturn(Mono.just(upstream));

        ResponseEntity<Flux<String>> response = controller.consult(
                new ConsultController.ConsultRequest("text", SESSION_ID), authentication).block();
        Disposable responseSubscription = response.getBody().subscribe();
        await().atMost(Duration.ofSeconds(2)).until(() -> firstEvent.getCount() == 0);

        responseSubscription.dispose();

        await().atMost(Duration.ofSeconds(2)).untilTrue(upstreamCancelled);
        assertThat(subscriptions).hasValue(1);
        verify(persistence).persistCancellation(anyLong(), any());
        verify(persistence, never()).persist(anyLong(), any(), any(), any());
        assertThat(liveTraces.snapshots()).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo("cancelled"));
    }

    @Test
    void sendsOnlyPersistedPriorTurnsThenStoresTheCurrentUserTurn() {
        List<AiConsultClient.HistoryMessage> prior = List.of(
                new AiConsultClient.HistoryMessage("user", "第一轮症状"),
                new AiConsultClient.HistoryMessage("assistant", "持续多久了？"));
        when(messages.historyFor(2L, SESSION_ID)).thenReturn(prior);
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), prior))
                .thenReturn(Mono.just(Flux.just(done())));

        ResponseEntity<Flux<String>> response = controller.consult(
                new ConsultController.ConsultRequest("text", SESSION_ID), authentication).block();
        response.getBody().collectList().block();

        verify(messages).appendUser(2L, SESSION_ID, "text");
        verify(aiClient).openConsult("text", SESSION_ID, Map.of(), prior);
    }

    @Test
    void cancellationWinningWhileDoneIsProcessedNeverPersistsSuccess() throws Exception {
        BlockingLiveTraceRegistry blockingLiveTraces = new BlockingLiveTraceRegistry();
        controller = new ConsultController(
                aiClient, ownership, persistence, messages, blockingLiveTraces,
                users, healthProfiles, new ObjectMapper());
        when(aiClient.openConsult("text", SESSION_ID, Map.of(), List.of()))
                .thenReturn(Mono.just(Flux.just(done())
                        .subscribeOn(Schedulers.boundedElastic())));

        ResponseEntity<Flux<String>> response = controller.consult(
                new ConsultController.ConsultRequest("text", SESSION_ID), authentication).block();
        Disposable responseSubscription = response.getBody().subscribe();
        try {
            assertThat(blockingLiveTraces.publishStarted.await(2, TimeUnit.SECONDS)).isTrue();

            responseSubscription.dispose();

            verify(persistence, timeout(2_000)).persistCancellation(anyLong(), any());
        } finally {
            blockingLiveTraces.allowPublish.release();
        }

        assertThat(blockingLiveTraces.publishReturned.await(2, TimeUnit.SECONDS)).isTrue();
        verify(persistence, after(200).never()).persist(anyLong(), any(), any(), any());
        assertThat(blockingLiveTraces.snapshots()).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.status()).isEqualTo("cancelled"));
    }

    private String nodeStarted() {
        return "{\"protocol_version\":\"1.0\",\"trace_id\":\"" + TRACE_ID
                + "\",\"session_id\":\"" + SESSION_ID + "\",\"sequence\":1,"
                + "\"type\":\"node\",\"node\":\"extract\",\"status\":\"started\","
                + "\"elapsed_ms\":0,\"state\":{\"intent\":\"medical_consult\","
                + "\"phase\":\"collecting\",\"turn_count\":1,\"history_mode\":\"full\"},"
                + "\"data\":{}}";
    }

    private String done() {
        return "{\"protocol_version\":\"1.0\",\"trace_id\":\"" + TRACE_ID
                + "\",\"session_id\":\"" + SESSION_ID + "\",\"sequence\":1,"
                + "\"type\":\"done\",\"status\":\"completed\",\"elapsed_ms\":0,"
                + "\"state\":{\"intent\":\"medical_consult\",\"phase\":\"completed\","
                + "\"turn_count\":1,\"history_mode\":\"full\"},\"data\":{}}";
    }

    private static final class BlockingLiveTraceRegistry extends LiveTraceRegistry {
        private final CountDownLatch publishStarted = new CountDownLatch(1);
        private final CountDownLatch publishReturned = new CountDownLatch(1);
        private final Semaphore allowPublish = new Semaphore(0);

        private BlockingLiveTraceRegistry() {
            super(new ObjectMapper());
        }

        @Override
        public void publish(Handle handle, String traceId, String rawEvent) {
            publishStarted.countDown();
            allowPublish.acquireUninterruptibly();
            try {
                super.publish(handle, traceId, rawEvent);
            } finally {
                publishReturned.countDown();
            }
        }
    }
}
