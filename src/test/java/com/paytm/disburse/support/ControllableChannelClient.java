package com.paytm.disburse.support;

import com.paytm.disburse.channel.ChannelClient;
import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;
import com.paytm.disburse.domain.FailureReason;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ControllableChannelClient implements ChannelClient {

    private final Channel channel;
    private final Deque<ChannelResponse> scriptedResponses = new ArrayDeque<>();
    private final Map<UUID, ChannelResponse> processed = new ConcurrentHashMap<>();
    private long maxAmount = Long.MAX_VALUE;

    public ControllableChannelClient(Channel channel) { this.channel = channel; }

    public ControllableChannelClient enqueue(ChannelResponse r) { scriptedResponses.add(r); return this; }
    public ControllableChannelClient maxAmount(long v) { this.maxAmount = v; return this; }
    public void reset() { scriptedResponses.clear(); processed.clear(); }

    @Override public Channel channel() { return channel; }
    @Override public long maxAmountPaise() { return maxAmount; }

    @Override
    public synchronized ChannelResponse transfer(ChannelRequest req) {
        ChannelResponse prior = processed.get(req.referenceId());
        if (prior != null) return new ChannelResponse(prior.status(), prior.failureReason(),
            "duplicate: " + prior.rawResponse());
        if (scriptedResponses.isEmpty()) {
            throw new IllegalStateException(channel + ": no scripted response available");
        }
        ChannelResponse r = scriptedResponses.poll();
        processed.put(req.referenceId(), r);
        return r;
    }

    @Override
    public synchronized ChannelResponse status(UUID referenceId) {
        ChannelResponse r = processed.get(referenceId);
        if (r == null) return ChannelResponse.transient_(FailureReason.NETWORK_ERROR, "unknown reference");
        return r;
    }
}
