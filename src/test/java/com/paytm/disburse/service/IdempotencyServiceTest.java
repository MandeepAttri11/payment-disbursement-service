package com.paytm.disburse.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdempotencyServiceTest {

    private final IdempotencyService svc = new IdempotencyService();

    @Test
    void same_request_body_produces_same_hash() {
        String h1 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        String h2 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void different_request_body_produces_different_hash() {
        String h1 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        String h2 = svc.hash("{\"loan_id\":\"L1\",\"amount\":101}");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void verifying_same_key_and_hash_is_a_noop() {
        assertThatCode(() -> svc.verifyOrThrow("KEY-1", "hash-1", "KEY-1", "hash-1"))
            .doesNotThrowAnyException();
    }

    @Test
    void verifying_same_key_but_different_hash_throws_conflict() {
        assertThatThrownBy(() -> svc.verifyOrThrow("KEY-1", "hash-1", "KEY-1", "hash-2"))
            .isInstanceOf(IdempotencyConflictException.class);
    }
}
