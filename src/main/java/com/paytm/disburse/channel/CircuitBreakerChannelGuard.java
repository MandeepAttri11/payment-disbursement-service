package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class CircuitBreakerChannelGuard implements Supplier<Set<Channel>> {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerChannelGuard(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Set<Channel> get() {
        Set<Channel> open = EnumSet.noneOf(Channel.class);
        for (Channel c : Channel.values()) {
            CircuitBreaker cb = registry.circuitBreaker(c.name().toLowerCase());
            if (cb.getState() == CircuitBreaker.State.OPEN) open.add(c);
        }
        return open;
    }
}
