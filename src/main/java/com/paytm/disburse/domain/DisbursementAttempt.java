package com.paytm.disburse.domain;

import java.time.Instant;
import java.util.UUID;

public class DisbursementAttempt {
    private final UUID id;
    private final UUID disbursementId;
    private final Channel channel;
    private final int attemptNumber;
    private AttemptStatus status;
    private FailureReason failureReason;
    private String channelResponse;
    private int pollCount;
    private final Instant createdAt;
    private Instant completedAt;

    public DisbursementAttempt(UUID id, UUID disbursementId, Channel channel,
                               int attemptNumber, AttemptStatus status, Instant createdAt) {
        this.id = id;
        this.disbursementId = disbursementId;
        this.channel = channel;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void complete(AttemptStatus status, FailureReason reason, String channelResponse) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("Attempt " + id + " already terminal: " + this.status);
        }
        this.status = status;
        this.failureReason = reason;
        this.channelResponse = channelResponse;
        this.completedAt = Instant.now();
    }

    public void incrementPollCount() { this.pollCount++; }

    public UUID id() { return id; }
    public UUID disbursementId() { return disbursementId; }
    public Channel channel() { return channel; }
    public int attemptNumber() { return attemptNumber; }
    public AttemptStatus status() { return status; }
    public FailureReason failureReason() { return failureReason; }
    public String channelResponse() { return channelResponse; }
    public int pollCount() { return pollCount; }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }

    public static DisbursementAttempt hydrate(UUID id, UUID disbId, Channel ch, int num,
                                              AttemptStatus status, FailureReason reason,
                                              String resp, int polls, Instant created, Instant completed) {
        DisbursementAttempt a = new DisbursementAttempt(id, disbId, ch, num, status, created);
        a.failureReason = reason;
        a.channelResponse = resp;
        a.pollCount = polls;
        a.completedAt = completed;
        return a;
    }
}
