package com.paytm.disburse.channel.mock;

import com.paytm.disburse.channel.ChannelClient;
import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.FailureReason;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

abstract class MockChannelBase implements ChannelClient {

    private final Map<UUID, ChannelResponse> processed = new ConcurrentHashMap<>();
    protected final MockChannelProperties props;
    protected final Random random;

    protected MockChannelBase(MockChannelProperties props, Random random) {
        this.props = props;
        this.random = random;
    }

    @Override
    public long maxAmountPaise() { return props.getMaxAmountPaise(); }

    @Override
    public ChannelResponse transfer(ChannelRequest req) {
        ChannelResponse prior = processed.get(req.referenceId());
        if (prior != null) {
            return new ChannelResponse(prior.status(), prior.failureReason(),
                "duplicate: " + prior.rawResponse());
        }

        if (req.amountPaise() > props.getMaxAmountPaise()) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.AMOUNT_EXCEEDS_CHANNEL_LIMIT, "amount > channel max"));
        }
        if (req.ifsc() != null && req.ifsc().startsWith("INVALID_")) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.INVALID_IFSC, "invalid ifsc: " + req.ifsc()));
        }
        if (req.account() != null && req.account().startsWith("CLOSED_")) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.ACCOUNT_CLOSED, "account closed"));
        }

        sleepUpTo(props.getMeanLatencyMs());

        double roll = random.nextDouble();
        if (roll < props.getTimeoutRate()) {
            ChannelResponse internalOutcome = decideTerminalOutcome();
            processed.put(req.referenceId(), internalOutcome);
            return ChannelResponse.uncertain(
                FailureReason.CHANNEL_TIMEOUT_AFTER_SEND,
                "timeout after send; status unknown to caller");
        }
        if (roll < props.getTimeoutRate() + (1 - props.getSuccessRate())) {
            return record(req.referenceId(), ChannelResponse.transient_(
                FailureReason.CHANNEL_5XX, "transient error"));
        }
        return record(req.referenceId(), ChannelResponse.success("ok"));
    }

    @Override
    public ChannelResponse status(UUID referenceId) {
        ChannelResponse known = processed.get(referenceId);
        if (known != null) {
            return new ChannelResponse(known.status(), known.failureReason(), "poll: " + known.rawResponse());
        }
        return ChannelResponse.transient_(FailureReason.NETWORK_ERROR,
            "no record; safe to retry");
    }

    private ChannelResponse decideTerminalOutcome() {
        return random.nextDouble() < props.getSuccessRate()
            ? ChannelResponse.success("delayed-ack")
            : ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "delayed-failure");
    }

    private ChannelResponse record(UUID ref, ChannelResponse r) {
        processed.put(ref, r);
        return r;
    }

    private void sleepUpTo(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep((long)(random.nextDouble() * ms)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
