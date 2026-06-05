package com.paytm.disburse.channel.mock;

import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;
import com.paytm.disburse.domain.FailureReason;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockChannelTest {

    private MockChannelProperties props() {
        MockChannelProperties p = new MockChannelProperties();
        p.setSuccessRate(1.0); p.setTimeoutRate(0.0);
        p.setMeanLatencyMs(0); p.setMaxAmountPaise(Long.MAX_VALUE);
        return p;
    }

    @Test
    void rejects_duplicate_reference_id() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        UUID ref = UUID.randomUUID();
        ChannelRequest req = new ChannelRequest(ref, "1234", "HDFC0001234", null, 50000);

        ChannelResponse first = client.transfer(req);
        ChannelResponse second = client.transfer(req);

        assertThat(first.status()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(second.status()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(second.rawResponse()).contains("duplicate");
    }

    @Test
    void rejects_invalid_ifsc_as_permanent() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        ChannelRequest req = new ChannelRequest(UUID.randomUUID(), "1234", "INVALID_IFSC", null, 50000);

        ChannelResponse resp = client.transfer(req);

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_PERMANENT);
        assertThat(resp.failureReason()).isEqualTo(FailureReason.INVALID_IFSC);
    }

    @Test
    void rejects_amount_over_channel_limit_as_permanent() {
        MockChannelProperties p = props();
        p.setMaxAmountPaise(100_000_00L);
        UpiClient client = new UpiClient(p, new Random(42));
        ChannelRequest req = new ChannelRequest(UUID.randomUUID(), "1234", null, "x@upi", 200_000_00L);

        ChannelResponse resp = client.transfer(req);

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_PERMANENT);
        assertThat(resp.failureReason()).isEqualTo(FailureReason.AMOUNT_EXCEEDS_CHANNEL_LIMIT);
    }

    @Test
    void status_endpoint_returns_known_outcome_for_processed_reference() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        UUID ref = UUID.randomUUID();
        client.transfer(new ChannelRequest(ref, "1234", "HDFC0001234", null, 50000));

        ChannelResponse statusResp = client.status(ref);

        assertThat(statusResp.status()).isEqualTo(AttemptStatus.SUCCESS);
    }

    @Test
    void status_for_unknown_reference_returns_uncertain() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        ChannelResponse resp = client.status(UUID.randomUUID());

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_TRANSIENT);
    }

    @Test
    void channel_returns_correct_enum() {
        MockChannelProperties p = props();
        assertThat(new UpiClient(p, new Random()).channel()).isEqualTo(Channel.UPI);
        assertThat(new ImpsClient(p, new Random()).channel()).isEqualTo(Channel.IMPS);
        assertThat(new NeftClient(p, new Random()).channel()).isEqualTo(Channel.NEFT);
    }
}
