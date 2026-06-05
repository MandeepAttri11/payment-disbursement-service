package com.paytm.disburse.api.dto;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementAttempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DisbursementDetailResponse(
    UUID disbursementId,
    String loanId,
    String status,
    long amountPaise,
    String failureReason,
    Instant createdAt,
    Instant updatedAt,
    List<AttemptDto> attempts
) {
    public record AttemptDto(UUID id, String channel, int attemptNumber, String status,
                             String failureReason, Instant createdAt, Instant completedAt) {}

    public static DisbursementDetailResponse from(Disbursement d, List<DisbursementAttempt> atts) {
        return new DisbursementDetailResponse(d.id(), d.loanId(), d.status().name(), d.amountPaise(),
            d.failureReason() == null ? null : d.failureReason().name(),
            d.createdAt(), d.updatedAt(),
            atts.stream().map(a -> new AttemptDto(a.id(), a.channel().name(), a.attemptNumber(),
                a.status().name(),
                a.failureReason() == null ? null : a.failureReason().name(),
                a.createdAt(), a.completedAt())).toList());
    }
}
