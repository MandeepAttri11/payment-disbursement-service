package com.paytm.disburse.domain;

import java.util.Map;
import java.util.Set;

import static com.paytm.disburse.domain.DisbursementStatus.*;

public final class StateMachine {

    private static final Map<DisbursementStatus, Set<DisbursementStatus>> ALLOWED = Map.of(
        PENDING,        Set.of(IN_FLIGHT, FAILED),
        IN_FLIGHT,      Set.of(SUCCESS, FAILED, UNCERTAIN, PENDING_RETRY),
        PENDING_RETRY,  Set.of(IN_FLIGHT, FAILED),
        UNCERTAIN,      Set.of(SUCCESS, FAILED, PENDING_RETRY),
        SUCCESS,        Set.of(),
        FAILED,         Set.of(PENDING_RETRY)
    );

    private StateMachine() {}

    public static void requireValid(DisbursementStatus from, DisbursementStatus to) {
        if (from == to) return;
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateTransitionException(
                "Illegal transition: " + from + " -> " + to);
        }
    }
}
