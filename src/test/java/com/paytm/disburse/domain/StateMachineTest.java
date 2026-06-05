package com.paytm.disburse.domain;

import org.junit.jupiter.api.Test;
import static com.paytm.disburse.domain.DisbursementStatus.*;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {

    @Test
    void pending_can_go_to_in_flight() {
        assertThatCode(() -> StateMachine.requireValid(PENDING, IN_FLIGHT)).doesNotThrowAnyException();
    }

    @Test
    void in_flight_can_go_to_success() {
        assertThatCode(() -> StateMachine.requireValid(IN_FLIGHT, SUCCESS)).doesNotThrowAnyException();
    }

    @Test
    void success_is_terminal() {
        assertThatThrownBy(() -> StateMachine.requireValid(SUCCESS, PENDING))
            .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> StateMachine.requireValid(SUCCESS, FAILED))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void failed_can_only_go_to_pending_retry_via_manual_retry() {
        assertThatCode(() -> StateMachine.requireValid(FAILED, PENDING_RETRY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StateMachine.requireValid(FAILED, IN_FLIGHT))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void uncertain_cannot_skip_to_failed_without_resolution() {
        assertThatThrownBy(() -> StateMachine.requireValid(UNCERTAIN, IN_FLIGHT))
            .isInstanceOf(IllegalStateTransitionException.class);
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, SUCCESS)).doesNotThrowAnyException();
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, FAILED)).doesNotThrowAnyException();
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, PENDING_RETRY)).doesNotThrowAnyException();
    }

    @Test
    void identity_transition_is_a_no_op_not_an_error() {
        assertThatCode(() -> StateMachine.requireValid(IN_FLIGHT, IN_FLIGHT)).doesNotThrowAnyException();
    }
}
