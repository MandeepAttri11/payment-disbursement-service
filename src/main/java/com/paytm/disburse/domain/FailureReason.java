package com.paytm.disburse.domain;

public enum FailureReason {
    INVALID_IFSC(Kind.PERMANENT),
    ACCOUNT_CLOSED(Kind.PERMANENT),
    KYC_FAILED(Kind.PERMANENT),
    AMOUNT_EXCEEDS_CHANNEL_LIMIT(Kind.PERMANENT),
    BLOCKED_ACCOUNT(Kind.PERMANENT),

    CHANNEL_TIMEOUT_BEFORE_SEND(Kind.TRANSIENT),
    RATE_LIMITED(Kind.TRANSIENT),
    CHANNEL_5XX(Kind.TRANSIENT),
    CIRCUIT_OPEN(Kind.TRANSIENT),
    NETWORK_ERROR(Kind.TRANSIENT),

    CHANNEL_TIMEOUT_AFTER_SEND(Kind.UNCERTAIN),
    CHANNEL_UNPARSEABLE_RESPONSE(Kind.UNCERTAIN);

    public enum Kind { PERMANENT, TRANSIENT, UNCERTAIN }
    private final Kind kind;
    FailureReason(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
