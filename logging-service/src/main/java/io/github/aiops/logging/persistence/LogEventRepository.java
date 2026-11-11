package io.github.aiops.logging.persistence;

import io.github.aiops.contracts.event.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

public interface LogEventRepository extends JpaRepository<LogEventEntity, String> {

    @Query("""
            select event from LogEventEntity event
            where (:service is null or event.service = :service)
              and (:severity is null or event.severity = :severity)
              and (:startTime is null or event.timestamp >= :startTime)
              and (:endTime is null or event.timestamp <= :endTime)
            """)
    Page<LogEventEntity> search(
            @Param("service") String service,
            @Param("severity") Severity severity,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime,
            Pageable pageable);

    @Query("""
            select event from LogEventEntity event
            where event.timestamp >= :startTime
              and event.timestamp <= :endTime
              and (:service is null or event.service = :service)
            order by event.timestamp desc
            """)
    List<LogEventEntity> findRecent(
            @Param("service") String service,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
