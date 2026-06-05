package com.paytm.disburse.api.dto;

import com.paytm.disburse.domain.Disbursement;

import java.time.Instant;
import java.util.UUID;

public record DisburseResponse(
    UUID disbursementId,
    String loanId,
    String status,
    long amountPaise,
    Instant createdAt
) {
    public static DisburseResponse from(Disbursement d) {
        return new DisburseResponse(d.id(), d.loanId(), d.status().name(), d.amountPaise(), d.createdAt());
    }
}
