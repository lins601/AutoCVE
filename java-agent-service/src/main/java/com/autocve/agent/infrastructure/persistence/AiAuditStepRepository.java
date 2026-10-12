package com.autocve.agent.infrastructure.persistence;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAuditStepRepository extends JpaRepository<AiAuditStepEntity, String> {
    Optional<AiAuditStepEntity> findFirstByAuditIdAndStatusOrderByStartedAtDesc(
            String auditId, String status);

    long countByAuditIdAndStage(String auditId, com.autocve.agent.workflow.AuditStage stage);

    long countByAuditId(String auditId);

    Optional<AiAuditStepEntity> findFirstByAuditIdAndStageAndStatusOrderByAttemptDesc(
            String auditId,
            com.autocve.agent.workflow.AuditStage stage,
            String status);

    List<AiAuditStepEntity> findByAuditIdAndStatus(String auditId, String status);
}
