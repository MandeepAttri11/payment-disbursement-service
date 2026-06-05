package com.paytm.disburse.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {

    private static final List<Duration> UNCERTAIN_BACKOFFS = List.of(
        Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
        Duration.ofSeconds(60), Duration.ofSeconds(120));

    private final int maxAttemptsPerChannel;
    private final List<Duration> backoffs;
    private final int jitterPercent;

    @org.springframework.beans.factory.annotation.Autowired
    private RetryPolicy(
        @Value("${disburse.retry.max-attempts-per-channel}") int maxAttempts,
        @Value("${disburse.retry.backoff-ms}") String backoffsCsv,
        @Value("${disburse.retry.jitter-percent}") int jitterPercent
    ) {
        this(maxAttempts,
             java.util.Arrays.stream(backoffsCsv.split(","))
                 .map(s -> Duration.ofMillis(Long.parseLong(s.trim().replaceAll("[\\[\\]]", ""))))
                 .toList(),
             jitterPercent);
    }

    RetryPolicy(int maxAttempts, List<Duration> backoffs, int jitterPercent) {
        this.maxAttemptsPerChannel = maxAttempts;
        this.backoffs = backoffs;
        this.jitterPercent = jitterPercent;
    }

    public int maxAttemptsPerChannel() { return maxAttemptsPerChannel; }

    public boolean exhausted(int attemptNumber) { return attemptNumber >= maxAttemptsPerChannel; }

    public Duration backoffFor(int attemptNumber) {
        int idx = Math.min(attemptNumber - 1, backoffs.size() - 1);
        Duration base = backoffs.get(idx);
        if (jitterPercent <= 0) return base;
        long jitterMs = (long)(base.toMillis() * (jitterPercent / 100.0));
        long delta = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
        return base.plusMillis(delta);
    }

    public Duration uncertainPollBackoff(int pollCount) {
        return UNCERTAIN_BACKOFFS.get(Math.min(pollCount, UNCERTAIN_BACKOFFS.size() - 1));
    }
}
