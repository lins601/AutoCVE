package com.autocve.agent.infrastructure.event;

import com.autocve.agent.workflow.AgentWorkflowProperties;
import com.autocve.agent.workflow.AuditStage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class InMemoryAuditEventBroker implements AuditEventPublisher {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, ArrayDeque<AuditEvent>> history = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();
    private final int maxStoredEvents;

    public InMemoryAuditEventBroker(AgentWorkflowProperties properties) {
        this.maxStoredEvents = Math.max(50, properties.getMaxStoredEvents());
    }

    @Override
    public AuditEvent publish(
            String auditId,
            String type,
            AuditStage stage,
            String summary,
            Map<String, Object> data) {
        AuditEvent event = new AuditEvent(
                sequence.incrementAndGet(),
                auditId,
                type,
                stage,
                truncate(summary),
                data,
                Instant.now());
        ArrayDeque<AuditEvent> auditHistory =
                history.computeIfAbsent(auditId, ignored -> new ArrayDeque<>());
        synchronized (auditHistory) {
            auditHistory.addLast(event);
            while (auditHistory.size() > maxStoredEvents) {
                auditHistory.removeFirst();
            }
        }
        for (SseEmitter emitter : subscribers.getOrDefault(auditId, new CopyOnWriteArrayList<>())) {
            send(auditId, emitter, event);
        }
        return event;
    }

    public SseEmitter subscribe(String auditId, Long afterSequence) {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.computeIfAbsent(auditId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(auditId, emitter));
        emitter.onTimeout(() -> remove(auditId, emitter));
        emitter.onError(error -> remove(auditId, emitter));
        for (AuditEvent event : events(auditId, afterSequence)) {
            send(auditId, emitter, event);
        }
        return emitter;
    }

    public List<AuditEvent> events(String auditId, Long afterSequence) {
        ArrayDeque<AuditEvent> auditHistory = history.get(auditId);
        if (auditHistory == null) {
            return List.of();
        }
        long lowerBound = afterSequence == null ? 0L : afterSequence;
        synchronized (auditHistory) {
            List<AuditEvent> result = new ArrayList<>();
            for (AuditEvent event : auditHistory) {
                if (event.sequence() > lowerBound) {
                    result.add(event);
                }
            }
            return List.copyOf(result);
        }
    }

    private void send(String auditId, SseEmitter emitter, AuditEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.type())
                    .data(event));
        }
        catch (IOException | IllegalStateException exception) {
            remove(auditId, emitter);
            emitter.completeWithError(exception);
        }
    }

    private void remove(String auditId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> auditSubscribers = subscribers.get(auditId);
        if (auditSubscribers != null) {
            auditSubscribers.remove(emitter);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(1000, value.length()));
    }
}
