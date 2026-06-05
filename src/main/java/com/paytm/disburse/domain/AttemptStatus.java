package com.paytm.disburse.domain;

public enum AttemptStatus {
    IN_FLIGHT,
    SUCCESS,
    FAILED_TRANSIENT,
    FAILED_PERMANENT,
    UNCERTAIN;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED_TRANSIENT || this == FAILED_PERMANENT;
    }
}
