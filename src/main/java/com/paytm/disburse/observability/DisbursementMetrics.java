package com.paytm.disburse.observability;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;
import com.paytm.disburse.domain.DisbursementStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DisbursementMetrics {

    private final MeterRegistry registry;

    public DisbursementMetrics(MeterRegistry registry) { this.registry = registry; }

    public void created() { registry.counter("disbursement.created").increment(); }

    public void completed(DisbursementStatus status) {
        registry.counter("disbursement.completed", "outcome", status.name()).increment();
    }

    public void attempt(Channel channel, AttemptStatus status, Duration duration) {
        registry.timer("disbursement.attempt", "channel", channel.name(), "outcome", status.name())
            .record(duration);
    }

    public void transition(DisbursementStatus from, DisbursementStatus to) {
        registry.counter("disbursement.transitions", "from", from.name(), "to", to.name()).increment();
    }

    public void reconcileBreak(String type) {
        registry.counter("reconcile.breaks", "type", type).increment();
    }
}
