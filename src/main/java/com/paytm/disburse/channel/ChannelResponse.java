package com.paytm.disburse.channel;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.FailureReason;

public record ChannelResponse(
    AttemptStatus status,
    FailureReason failureReason,
    String rawResponse
) {
    public static ChannelResponse success(String raw) {
        return new ChannelResponse(AttemptStatus.SUCCESS, null, raw);
    }
    public static ChannelResponse transient_(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.FAILED_TRANSIENT, r, raw);
    }
    public static ChannelResponse permanent(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.FAILED_PERMANENT, r, raw);
    }
    public static ChannelResponse uncertain(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.UNCERTAIN, r, raw);
    }
}
