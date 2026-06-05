package com.paytm.disburse.channel;

import java.util.UUID;

public record ChannelRequest(
    UUID referenceId,
    String account,
    String ifsc,
    String upiId,
    long amountPaise
) {}
