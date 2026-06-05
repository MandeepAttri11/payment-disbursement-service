package com.paytm.disburse.repository;

import com.paytm.disburse.domain.DisbursementAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AttemptRepository {
    private final JdbcTemplate jdbc;
    public AttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(DisbursementAttempt a) {
        jdbc.update("""
            INSERT INTO disbursement_attempt (id, disbursement_id, channel, attempt_number, status,
                failure_reason, channel_response, poll_count, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            a.id().toString(), a.disbursementId().toString(), a.channel().name(), a.attemptNumber(),
            a.status().name(),
            a.failureReason() == null ? null : a.failureReason().name(),
            a.channelResponse(), a.pollCount(),
            Timestamp.from(a.createdAt()),
            a.completedAt() == null ? null : Timestamp.from(a.completedAt())
        );
    }

    public void update(DisbursementAttempt a) {
        jdbc.update("""
            UPDATE disbursement_attempt
               SET status = ?, failure_reason = ?, channel_response = ?, poll_count = ?, completed_at = ?
             WHERE id = ?
            """,
            a.status().name(),
            a.failureReason() == null ? null : a.failureReason().name(),
            a.channelResponse(), a.pollCount(),
            a.completedAt() == null ? null : Timestamp.from(a.completedAt()),
            a.id().toString()
        );
    }

    public Optional<DisbursementAttempt> findById(UUID id) {
        return jdbc.query("SELECT * FROM disbursement_attempt WHERE id = ?",
            RowMappers.ATTEMPT, id.toString()).stream().findFirst();
    }

    public List<DisbursementAttempt> findByDisbursementId(UUID disbId) {
        return jdbc.query("SELECT * FROM disbursement_attempt WHERE disbursement_id = ? ORDER BY created_at",
            RowMappers.ATTEMPT, disbId.toString());
    }

    public int countByDisbursementIdAndChannel(UUID disbId, com.paytm.disburse.domain.Channel ch) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM disbursement_attempt WHERE disbursement_id = ? AND channel = ?",
            Integer.class, disbId.toString(), ch.name());
        return c == null ? 0 : c;
    }
}
