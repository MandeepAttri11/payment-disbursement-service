package com.paytm.disburse.service;

import com.paytm.disburse.channel.ChannelClientRegistry;
import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.channel.ChannelRouter;
import com.paytm.disburse.domain.*;
import com.paytm.disburse.repository.AttemptRepository;
import com.paytm.disburse.repository.DisbursementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DisbursementService {

    private final DisbursementRepository disbursements;
    private final AttemptRepository attempts;
    private final IdempotencyService idempotency;
    private final ChannelRouter router;
    private final ChannelClientRegistry channels;
    private final RetryPolicy retryPolicy;

    public DisbursementService(DisbursementRepository disbursements,
                               AttemptRepository attempts,
                               IdempotencyService idempotency,
                               ChannelRouter router,
                               ChannelClientRegistry channels,
                               RetryPolicy retryPolicy) {
        this.disbursements = disbursements;
        this.attempts = attempts;
        this.idempotency = idempotency;
        this.router = router;
        this.channels = channels;
        this.retryPolicy = retryPolicy;
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
        if (existingByLoan.isPresent()) return existingByLoan.get();

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

    @Transactional
    public void processAttempt(UUID disbursementId) {
        Disbursement d = disbursements.findById(disbursementId).orElseThrow();
        if (d.status().isTerminal()) return;
        if (d.status() == DisbursementStatus.UNCERTAIN) return;

        Channel channel = nextChannelFor(d);
        if (channel == null) {
            markFailed(d, FailureReason.CIRCUIT_OPEN);
            return;
        }

        int attemptNumber = attempts.countByDisbursementIdAndChannel(d.id(), channel) + 1;
        DisbursementAttempt attempt = new DisbursementAttempt(
            UUID.randomUUID(), d.id(), channel, attemptNumber,
            AttemptStatus.IN_FLIGHT, Instant.now());

        // WRITE-AHEAD: persist attempt + link before the network call. Recovery on crash
        // relies on this row being visible so the worker can poll status(referenceId) and
        // resolve uncertainty without double-disbursing.
        attempts.insert(attempt);
        d.setCurrentAttemptId(attempt.id());
        d.transitionTo(DisbursementStatus.IN_FLIGHT);
        disbursements.update(d);

        ChannelRequest req = new ChannelRequest(
            attempt.id(), d.borrowerAccount(), d.borrowerIfsc(), d.borrowerUpi(), d.amountPaise());
        ChannelResponse resp = channels.get(channel).transfer(req);

        applyChannelResponse(d, attempt, resp);
    }

    private void applyChannelResponse(Disbursement d, DisbursementAttempt attempt, ChannelResponse resp) {
        attempt.complete(resp.status(), resp.failureReason(), resp.rawResponse());
        attempts.update(attempt);

        switch (resp.status()) {
            case SUCCESS -> {
                d.transitionTo(DisbursementStatus.SUCCESS);
                d.setNextActionAt(null);
            }
            case FAILED_PERMANENT -> {
                d.setFailureReason(resp.failureReason());
                d.transitionTo(DisbursementStatus.FAILED);
            }
            case FAILED_TRANSIENT -> scheduleRetryOrFallback(d, attempt);
            case UNCERTAIN -> {
                // Do NOT fall back to a different channel — money may have moved.
                d.transitionTo(DisbursementStatus.UNCERTAIN);
                d.setNextActionAt(Instant.now().plus(retryPolicy.uncertainPollBackoff(0)));
            }
            case IN_FLIGHT -> throw new IllegalStateException("channel returned IN_FLIGHT");
        }
        disbursements.update(d);
    }

    private void scheduleRetryOrFallback(Disbursement d, DisbursementAttempt last) {
        if (!retryPolicy.exhausted(last.attemptNumber())) {
            d.setNextActionAt(Instant.now().plus(retryPolicy.backoffFor(last.attemptNumber() + 1)));
            d.transitionTo(DisbursementStatus.PENDING_RETRY);
            return;
        }
        List<Channel> route = router.routeFor(d.amountPaise());
        int idx = route.indexOf(last.channel());
        if (idx >= 0 && idx + 1 < route.size()) {
            d.setNextActionAt(Instant.now());
            d.transitionTo(DisbursementStatus.PENDING_RETRY);
        } else {
            d.setFailureReason(last.failureReason());
            d.transitionTo(DisbursementStatus.FAILED);
        }
    }

    private Channel nextChannelFor(Disbursement d) {
        List<Channel> route = router.routeFor(d.amountPaise());
        if (route.isEmpty()) return null;
        if (d.currentAttemptId() == null) return route.get(0);

        DisbursementAttempt last = attempts.findById(d.currentAttemptId()).orElseThrow();
        if (!retryPolicy.exhausted(last.attemptNumber())) return last.channel();

        int idx = route.indexOf(last.channel());
        if (idx >= 0 && idx + 1 < route.size()) return route.get(idx + 1);
        return null;
    }

    private void markFailed(Disbursement d, FailureReason reason) {
        d.setFailureReason(reason);
        d.transitionTo(DisbursementStatus.FAILED);
        disbursements.update(d);
    }
}
