package com.medpilot.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("""
            select a from AuditLog a
            where (:actor is null or :actor = '' or a.actorUsername = :actor)
              and (:status is null or a.status = :status)
              and (:fromTime is null or a.createdAt >= :fromTime)
              and (:toTime is null or a.createdAt <= :toTime)
            order by a.createdAt desc
            """)
    Page<AuditLog> search(@Param("actor") String actor,
                          @Param("status") Integer status,
                          @Param("fromTime") Instant fromTime,
                          @Param("toTime") Instant toTime,
                          Pageable pageable);
}
