package com.paytm.disburse.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
        3, List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30)),
        0);

    @Test
    void backoff_for_each_attempt_number() {
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void attempt_at_or_past_max_is_exhausted() {
        assertThat(policy.exhausted(2)).isFalse();
        assertThat(policy.exhausted(3)).isTrue();
        assertThat(policy.exhausted(4)).isTrue();
    }

    @Test
    void uncertain_poll_backoff_grows() {
        RetryPolicy p = new RetryPolicy(3, List.of(Duration.ofSeconds(2)), 0);
        assertThat(p.uncertainPollBackoff(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.uncertainPollBackoff(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.uncertainPollBackoff(4)).isEqualTo(Duration.ofSeconds(120));
    }
}
