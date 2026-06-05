package com.paytm.disburse.domain;

public enum DisbursementStatus {
    PENDING,
    IN_FLIGHT,
    PENDING_RETRY,
    UNCERTAIN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() { return this == SUCCESS || this == FAILED; }
}
