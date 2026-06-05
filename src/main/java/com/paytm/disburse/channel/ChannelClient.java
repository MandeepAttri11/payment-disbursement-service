package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;

public interface ChannelClient {
    Channel channel();
    long maxAmountPaise();
    ChannelResponse transfer(ChannelRequest request);
    ChannelResponse status(java.util.UUID referenceId);
}
