package com.paytm.disburse.scenarios;

import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class IdempotencyTest {

    @Autowired DisbursementService service;

    @Test
    void same_loan_id_creates_only_one_disbursement() {
        var first = service.create(new CreateDisbursementCommand(
            "L-IDEM-1", "1234", "HDFC0001234", null, 100_000L, null, null));
        var second = service.create(new CreateDisbursementCommand(
            "L-IDEM-1", "1234", "HDFC0001234", null, 100_000L, null, null));
        assertThat(second.id()).isEqualTo(first.id());
    }
}
