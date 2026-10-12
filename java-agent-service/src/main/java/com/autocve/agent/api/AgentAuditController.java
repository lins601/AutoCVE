package com.autocve.agent.api;

import com.autocve.agent.application.AuditCommandService;
import com.autocve.agent.application.AuditQueryService;
import com.autocve.agent.application.AuditSnapshot;
import com.autocve.agent.application.ApprovalSnapshot;
import com.autocve.agent.infrastructure.event.InMemoryAuditEventBroker;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/agent-audits")
public class AgentAuditController {

    private final AuditCommandService commands;
    private final AuditQueryService queries;
    private final InMemoryAuditEventBroker events;

    public AgentAuditController(
            AuditCommandService commands,
            AuditQueryService queries,
            InMemoryAuditEventBroker events) {
        this.commands = commands;
        this.queries = queries;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<AuditSnapshot> create(
            @Valid @RequestBody CreateAgentAuditRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResponseEntity.accepted().body(commands.create(request.toSpec(), idempotencyKey));
    }

    @GetMapping("/{auditId}")
    public AuditSnapshot get(@PathVariable String auditId) {
        return queries.get(auditId);
    }

    @PostMapping("/{auditId}/cancel")
    public AuditSnapshot cancel(@PathVariable String auditId) {
        return commands.cancel(auditId);
    }

    @PostMapping("/{auditId}/retry")
    public ResponseEntity<AuditSnapshot> retry(@PathVariable String auditId) {
        return ResponseEntity.accepted().body(commands.retry(auditId));
    }

    @GetMapping("/{auditId}/approvals")
    public List<ApprovalSnapshot> approvals(@PathVariable String auditId) {
        return commands.listApprovals(auditId);
    }

    @PostMapping("/{auditId}/approvals/{approvalId}/approve")
    public ApprovalSnapshot approve(
            @PathVariable String auditId,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return commands.approve(
                auditId, approvalId, request.actionDigest(), request.decidedBy());
    }

    @PostMapping("/{auditId}/approvals/{approvalId}/reject")
    public ApprovalSnapshot reject(
            @PathVariable String auditId,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return commands.reject(
                auditId, approvalId, request.actionDigest(), request.decidedBy());
    }

    @GetMapping(path = "/{auditId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String auditId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        queries.require(auditId);
        return events.subscribe(auditId, parseEventId(lastEventId));
    }

    private static Long parseEventId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Last-Event-ID must be a number");
        }
    }
}
