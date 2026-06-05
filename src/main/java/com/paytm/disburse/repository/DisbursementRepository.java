package com.paytm.disburse.repository;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DisbursementRepository {

    private final JdbcTemplate jdbc;
    public DisbursementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(Disbursement d) {
        jdbc.update("""
            INSERT INTO disbursement (id, loan_id, borrower_account, borrower_ifsc, borrower_upi,
                amount_paise, status, idempotency_key, idempotency_request_hash, idempotency_response,
                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            d.id().toString(), d.loanId(), d.borrowerAccount(), d.borrowerIfsc(), d.borrowerUpi(),
            d.amountPaise(), d.status().name(), d.idempotencyKey(),
            d.idempotencyRequestHash(), d.idempotencyResponse(),
            Timestamp.from(d.createdAt()), Timestamp.from(d.updatedAt())
        );
    }

    public Optional<Disbursement> findById(UUID id) {
        return jdbc.query("SELECT * FROM disbursement WHERE id = ?",
            RowMappers.DISBURSEMENT, id.toString()).stream().findFirst();
    }

    public Optional<Disbursement> findByLoanId(String loanId) {
        return jdbc.query("SELECT * FROM disbursement WHERE loan_id = ?",
            RowMappers.DISBURSEMENT, loanId).stream().findFirst();
    }

    public Optional<Disbursement> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM disbursement WHERE idempotency_key = ?",
            RowMappers.DISBURSEMENT, key).stream().findFirst();
    }

    public List<Disbursement> claimWorkBatch(int batchSize) {
        return jdbc.query("""
            SELECT * FROM disbursement
            WHERE status IN ('PENDING', 'PENDING_RETRY', 'IN_FLIGHT', 'UNCERTAIN')
              AND (next_action_at IS NULL OR next_action_at <= CURRENT_TIMESTAMP)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """, RowMappers.DISBURSEMENT, batchSize);
    }

    public void update(Disbursement d) {
        int rows = jdbc.update("""
            UPDATE disbursement
               SET status = ?, current_attempt_id = ?, next_action_at = ?,
                   failure_reason = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """,
            d.status().name(),
            d.currentAttemptId() == null ? null : d.currentAttemptId().toString(),
            d.nextActionAt() == null ? null : Timestamp.from(d.nextActionAt()),
            d.failureReason() == null ? null : d.failureReason().name(),
            d.id().toString(), d.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException("Disbursement " + d.id() + " was modified concurrently");
        }
        d.setVersion(d.version() + 1);
    }
}
