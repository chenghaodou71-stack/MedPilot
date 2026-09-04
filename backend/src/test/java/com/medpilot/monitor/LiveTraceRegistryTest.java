package com.medpilot.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTraceRegistryTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";

    @Test
    void exposesActiveSnapshotsAndReplaysTheLatestSnapshotToNewSseSubscribers() {
        LiveTraceRegistry registry = new LiveTraceRegistry(new ObjectMapper());
        LiveTraceRegistry.Handle handle = registry.start(SESSION_ID, 2L);
        registry.publish(handle, TRACE_ID, "{\"type\":\"node\",\"node\":\"extract\"}");

        assertThat(registry.snapshots()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.traceId()).isEqualTo(TRACE_ID);
            assertThat(snapshot.status()).isEqualTo("active");
            assertThat(snapshot.events()).hasSize(1);
        });

        LiveTraceRegistry.Update update = registry.stream()
                .next()
                .block(Duration.ofSeconds(1));
        assertThat(update).isNotNull();
        assertThat(update.kind()).isEqualTo("snapshot");
        assertThat(update.traces()).singleElement()
                .satisfies(snapshot -> assertThat(snapshot.traceId()).isEqualTo(TRACE_ID));
    }

    @Test
    void publishesExplicitCompletedFailedAndCancelledTerminalStates() {
        LiveTraceRegistry registry = new LiveTraceRegistry(new ObjectMapper());
        LiveTraceRegistry.Handle completed = registry.start(SESSION_ID, 2L);
        LiveTraceRegistry.Handle failed = registry.start(
                "2779673a-c983-47e4-9715-f2d9548f469a", 2L);
        LiveTraceRegistry.Handle cancelled = registry.start(
                "3779673a-c983-47e4-9715-f2d9548f469a", 2L);

        registry.complete(completed, TRACE_ID);
        registry.fail(failed, "3c293933-6590-4bfc-b0e8-507d3063c90b", "inference_timeout");
        registry.cancel(cancelled, "4c293933-6590-4bfc-b0e8-507d3063c90b");

        assertThat(registry.snapshots()).extracting(LiveTraceRegistry.Snapshot::status)
                .containsExactlyInAnyOrder("completed", "failed", "cancelled");
        assertThat(registry.snapshots())
                .filteredOn(snapshot -> "failed".equals(snapshot.status()))
                .singleElement()
                .extracting(LiveTraceRegistry.Snapshot::failureCode)
                .isEqualTo("inference_timeout");
    }

    @Test
    void neverEvictsAnActiveTraceToEnforceTheRecentTerminalTraceLimit() {
        LiveTraceRegistry registry = new LiveTraceRegistry(new ObjectMapper());
        LiveTraceRegistry.Handle first = registry.start(SESSION_ID, 2L);
        for (int index = 0; index < 256; index++) {
            registry.start(java.util.UUID.randomUUID().toString(), 2L);
        }

        registry.publish(first, TRACE_ID, "{\"type\":\"node\",\"node\":\"extract\"}");

        assertThat(registry.find(TRACE_ID)).isPresent();
        assertThat(registry.snapshots()).hasSize(257);
    }
}
