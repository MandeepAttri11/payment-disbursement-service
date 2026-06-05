package com.paytm.disburse.repository;

import com.paytm.disburse.domain.*;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public final class RowMappers {

    public static final RowMapper<Disbursement> DISBURSEMENT = (ResultSet rs, int n) -> Disbursement.hydrate(
        UUID.fromString(rs.getString("id")),
        rs.getString("loan_id"),
        rs.getString("borrower_account"),
        rs.getString("borrower_ifsc"),
        rs.getString("borrower_upi"),
        rs.getLong("amount_paise"),
        DisbursementStatus.valueOf(rs.getString("status")),
        rs.getString("current_attempt_id") == null ? null : UUID.fromString(rs.getString("current_attempt_id")),
        rs.getString("idempotency_key"),
        rs.getString("idempotency_request_hash"),
        rs.getString("idempotency_response"),
        rs.getTimestamp("next_action_at") == null ? null : rs.getTimestamp("next_action_at").toInstant(),
        rs.getString("failure_reason") == null ? null : FailureReason.valueOf(rs.getString("failure_reason")),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant()
    );

    public static final RowMapper<DisbursementAttempt> ATTEMPT = (ResultSet rs, int n) -> DisbursementAttempt.hydrate(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("disbursement_id")),
        Channel.valueOf(rs.getString("channel")),
        rs.getInt("attempt_number"),
        AttemptStatus.valueOf(rs.getString("status")),
        rs.getString("failure_reason") == null ? null : FailureReason.valueOf(rs.getString("failure_reason")),
        rs.getString("channel_response"),
        rs.getInt("poll_count"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
    );

    private RowMappers() {}
}
