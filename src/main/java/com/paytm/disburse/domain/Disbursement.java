package com.paytm.disburse.domain;

import java.time.Instant;
import java.util.UUID;

public class Disbursement {
    private final UUID id;
    private final String loanId;
    private final String borrowerAccount;
    private final String borrowerIfsc;
    private final String borrowerUpi;
    private final long amountPaise;
    private DisbursementStatus status;
    private UUID currentAttemptId;
    private String idempotencyKey;
    private String idempotencyRequestHash;
    private String idempotencyResponse;
    private Instant nextActionAt;
    private FailureReason failureReason;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    public Disbursement(UUID id, String loanId, String borrowerAccount, String borrowerIfsc,
                        String borrowerUpi, long amountPaise, DisbursementStatus status,
                        Instant createdAt) {
        this.id = id;
        this.loanId = loanId;
        this.borrowerAccount = borrowerAccount;
        this.borrowerIfsc = borrowerIfsc;
        this.borrowerUpi = borrowerUpi;
        this.amountPaise = amountPaise;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void transitionTo(DisbursementStatus next) {
        StateMachine.requireValid(this.status, next);
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String loanId() { return loanId; }
    public String borrowerAccount() { return borrowerAccount; }
    public String borrowerIfsc() { return borrowerIfsc; }
    public String borrowerUpi() { return borrowerUpi; }
    public long amountPaise() { return amountPaise; }
    public DisbursementStatus status() { return status; }
    public UUID currentAttemptId() { return currentAttemptId; }
    public String idempotencyKey() { return idempotencyKey; }
    public String idempotencyRequestHash() { return idempotencyRequestHash; }
    public String idempotencyResponse() { return idempotencyResponse; }
    public Instant nextActionAt() { return nextActionAt; }
    public FailureReason failureReason() { return failureReason; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void setCurrentAttemptId(UUID id) { this.currentAttemptId = id; }
    public void setIdempotency(String key, String hash, String response) {
        this.idempotencyKey = key;
        this.idempotencyRequestHash = hash;
        this.idempotencyResponse = response;
    }
    public void setNextActionAt(Instant t) { this.nextActionAt = t; }
    public void setFailureReason(FailureReason r) { this.failureReason = r; }
    public void setVersion(int v) { this.version = v; }
    public void touch() { this.updatedAt = Instant.now(); }

    public static Disbursement hydrate(UUID id, String loanId, String account, String ifsc,
                                       String upi, long paise, DisbursementStatus status,
                                       UUID attemptId, String idKey, String idHash, String idResp,
                                       Instant nextAction, FailureReason reason, int version,
                                       Instant created, Instant updated) {
        Disbursement d = new Disbursement(id, loanId, account, ifsc, upi, paise, status, created);
        d.currentAttemptId = attemptId;
        d.idempotencyKey = idKey;
        d.idempotencyRequestHash = idHash;
        d.idempotencyResponse = idResp;
        d.nextActionAt = nextAction;
        d.failureReason = reason;
        d.version = version;
        d.updatedAt = updated;
        return d;
    }
}
