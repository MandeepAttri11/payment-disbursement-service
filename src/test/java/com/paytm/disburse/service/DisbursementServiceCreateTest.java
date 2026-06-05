package com.paytm.disburse.service;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DisbursementServiceCreateTest {

    @Autowired DisbursementService service;

    @Test
    void create_returns_new_disbursement_in_pending() {
        Disbursement d = service.create(new CreateDisbursementCommand(
            "L-A", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        assertThat(d.status()).isEqualTo(DisbursementStatus.PENDING);
        assertThat(d.id()).isNotNull();
    }

    @Test
    void duplicate_loan_id_returns_existing_disbursement() {
        Disbursement first = service.create(new CreateDisbursementCommand(
            "L-DUP", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        Disbursement second = service.create(new CreateDisbursementCommand(
            "L-DUP", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void same_idempotency_key_with_different_body_throws() {
        service.create(new CreateDisbursementCommand(
            "L-X1", "1234", "HDFC0001234", null, 50_000_00L, "KEY-A", "hash-X1"));
        assertThatThrownBy(() -> service.create(new CreateDisbursementCommand(
            "L-X2", "1234", "HDFC0001234", null, 70_000_00L, "KEY-A", "hash-X2")))
            .isInstanceOf(IdempotencyConflictException.class);
    }
}
