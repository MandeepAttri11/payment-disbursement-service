package com.paytm.disburse.service;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.repository.DisbursementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DisbursementService {

    private final DisbursementRepository disbursements;
    private final IdempotencyService idempotency;

    public DisbursementService(DisbursementRepository disbursements,
                               IdempotencyService idempotency) {
        this.disbursements = disbursements;
        this.idempotency = idempotency;
    }

    @Transactional
    public Disbursement create(CreateDisbursementCommand cmd) {
        if (cmd.idempotencyKey() != null) {
            var existing = disbursements.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                idempotency.verifyOrThrow(
                    existing.get().idempotencyKey(), existing.get().idempotencyRequestHash(),
                    cmd.idempotencyKey(), cmd.requestBodyHash());
                return existing.get();
            }
        }

        var existingByLoan = disbursements.findByLoanId(cmd.loanId());
        if (existingByLoan.isPresent()) {
            return existingByLoan.get();
        }

        Disbursement d = new Disbursement(
            UUID.randomUUID(), cmd.loanId(), cmd.borrowerAccount(), cmd.borrowerIfsc(),
            cmd.borrowerUpi(), cmd.amountPaise(), DisbursementStatus.PENDING, Instant.now()
        );
        if (cmd.idempotencyKey() != null) {
            d.setIdempotency(cmd.idempotencyKey(), cmd.requestBodyHash(), null);
        }
        try {
            disbursements.insert(d);
        } catch (DuplicateKeyException dupe) {
            return disbursements.findByLoanId(cmd.loanId()).orElseThrow();
        }
        return d;
    }
}
