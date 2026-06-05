package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.DisbursementStatus;
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
class HappyPathTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void rs_2L_via_imps_succeeds_first_try() {
        impsClient.enqueue(ChannelResponse.success("ok"));
        var d = service.create(new CreateDisbursementCommand("L-HAPPY",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());
        var refreshed = service.findById(d.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo(DisbursementStatus.SUCCESS);
        var attempts = service.attemptsFor(d.id());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempts.get(0).channel().name()).isEqualTo("IMPS");
    }
}
