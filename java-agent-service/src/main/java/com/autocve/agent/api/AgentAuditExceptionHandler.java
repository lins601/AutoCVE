package com.autocve.agent.api;

import com.autocve.agent.application.AuditConflictException;
import com.autocve.agent.application.AuditNotFoundException;
import com.autocve.agent.application.ApprovalNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class AgentAuditExceptionHandler {

    @ExceptionHandler(AuditNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(AuditNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ApprovalNotFoundException.class)
    ResponseEntity<ApiErrorResponse> approvalNotFound(ApprovalNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "APPROVAL_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AuditConflictException.class)
    ResponseEntity<ApiErrorResponse> conflict(AuditConflictException exception) {
        return error(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> optimisticConflict(OptimisticLockingFailureException exception) {
        return error(
                HttpStatus.CONFLICT,
                "CONFLICT",
                "The audit or approval was updated concurrently; reload before retrying");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ApiErrorResponse> invalid(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage());
    }

    private static ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code,
                message == null ? status.getReasonPhrase() : message,
                status.value(),
                Instant.now()));
    }
}
