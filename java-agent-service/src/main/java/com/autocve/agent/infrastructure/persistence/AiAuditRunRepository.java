package com.autocve.agent.infrastructure.persistence;

import java.util.Optional;
import java.util.List;
import com.autocve.agent.domain.AuditRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAuditRunRepository extends JpaRepository<AiAuditRunEntity, String> {
    Optional<AiAuditRunEntity> findByIdempotencyKey(String idempotencyKey);

    List<AiAuditRunEntity> findByStatus(AuditRunStatus status);
}
