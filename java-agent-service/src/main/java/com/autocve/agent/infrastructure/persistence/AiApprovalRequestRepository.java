package com.autocve.agent.infrastructure.persistence;

import com.autocve.agent.domain.ApprovalStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiApprovalRequestRepository
        extends JpaRepository<AiApprovalRequestEntity, String> {

    List<AiApprovalRequestEntity> findByAuditIdOrderByCreatedAtAsc(String auditId);

    List<AiApprovalRequestEntity> findByAuditIdAndStatusOrderByCreatedAtAsc(
            String auditId, ApprovalStatus status);

    Optional<AiApprovalRequestEntity> findByAuditIdAndFindingIdAndActionDigest(
            String auditId, String findingId, String actionDigest);

    long countByAuditIdAndStatus(String auditId, ApprovalStatus status);
}
