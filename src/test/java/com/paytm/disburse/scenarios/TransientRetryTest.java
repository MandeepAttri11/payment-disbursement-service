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
class TransientRetryTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void imps_timeout_then_success_on_second_attempt() {
        impsClient.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_TIMEOUT_BEFORE_SEND, "timeout"));
        impsClient.enqueue(ChannelResponse.success("ok"));

        var d = service.create(new CreateDisbursementCommand("L-RETRY",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.PENDING_RETRY);
        service.processAttempt(d.id());
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.SUCCESS);
        assertThat(service.attemptsFor(d.id())).hasSize(2);
    }
}
