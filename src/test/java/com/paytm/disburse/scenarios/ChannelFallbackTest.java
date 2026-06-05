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
class ChannelFallbackTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient upiClient;
    @Autowired ControllableChannelClient impsClient;

    @Test
    void upi_exhausted_falls_back_to_imps() {
        upiClient.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        upiClient.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        upiClient.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        impsClient.enqueue(ChannelResponse.success("ok"));

        var d = service.create(new CreateDisbursementCommand("L-FALL",
            "1234", "HDFC0001234", "x@upi", 50_000_00L, null, null));
        for (int i = 0; i < 4; i++) service.processAttempt(d.id());

        var attempts = service.attemptsFor(d.id());
        assertThat(attempts).extracting(a -> a.channel().name())
            .containsExactly("UPI","UPI","UPI","IMPS");
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.SUCCESS);
    }
}
