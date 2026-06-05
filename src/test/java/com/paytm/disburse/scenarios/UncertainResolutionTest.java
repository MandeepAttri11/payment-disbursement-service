package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
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
class UncertainResolutionTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void uncertain_state_never_falls_back_to_different_channel() {
        impsClient.enqueue(new ChannelResponse(AttemptStatus.UNCERTAIN,
            FailureReason.CHANNEL_TIMEOUT_AFTER_SEND, "timeout"));

        var d = service.create(new CreateDisbursementCommand("L-UNCERT",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.UNCERTAIN);

        for (int i = 0; i < 10; i++) service.pollUncertain(d.id());

        var atts = service.attemptsFor(d.id());
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).channel().name()).isEqualTo("IMPS");
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.UNCERTAIN);
    }
}
