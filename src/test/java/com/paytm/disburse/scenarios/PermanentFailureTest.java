package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.domain.FailureReason;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class PermanentFailureTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void invalid_ifsc_does_not_retry_or_fall_back() {
        impsClient.enqueue(ChannelResponse.permanent(FailureReason.INVALID_IFSC, "bad ifsc"));

        var d = service.create(new CreateDisbursementCommand("L-PERM",
            "1234", "INVALID_XYZ", null, 200_000_00L, null, null));
        service.processAttempt(d.id());

        var refreshed = service.findById(d.id()).get();
        assertThat(refreshed.status()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(refreshed.failureReason()).isEqualTo(FailureReason.INVALID_IFSC);
        assertThat(service.attemptsFor(d.id())).hasSize(1);
    }
}
