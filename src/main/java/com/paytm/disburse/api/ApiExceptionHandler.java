package com.paytm.disburse.api;

import com.paytm.disburse.domain.IllegalStateTransitionException;
import com.paytm.disburse.service.IdempotencyConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String,String>> idempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "IDEMPOTENCY_KEY_REUSED", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "INVALID_STATE", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String,String>> badTransition(IllegalStateTransitionException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "ILLEGAL_TRANSITION", "message", e.getMessage()));
    }
}
