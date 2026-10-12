package com.autocve.agent.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiToolCallRecordRepository extends JpaRepository<AiToolCallRecordEntity, String> {
    List<AiToolCallRecordEntity> findByAuditIdOrderByCreatedAtAsc(String auditId);
}
