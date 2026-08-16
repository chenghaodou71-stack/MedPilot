package com.medpilot.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FollowUpTaskRepository extends JpaRepository<FollowUpTask, Long> {
    List<FollowUpTask> findByUserIdOrderByDueAtAsc(Long userId);
    List<FollowUpTask> findByUserIdAndStatusAndDueAtLessThanEqualOrderByDueAtAsc(
            Long userId, FollowUpTask.Status status, Instant dueAt);
    Optional<FollowUpTask> findByIdAndUserId(Long id, Long userId);
}
