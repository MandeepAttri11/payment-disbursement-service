package com.paytm.disburse.worker;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.repository.DisbursementRepository;
import com.paytm.disburse.service.DisbursementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@ConditionalOnProperty(value = "disburse.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DisbursementWorker {

    private static final Logger log = LoggerFactory.getLogger(DisbursementWorker.class);

    private final DisbursementRepository repo;
    private final DisbursementService service;
    private final int batchSize;

    public DisbursementWorker(DisbursementRepository repo, DisbursementService service,
                              @Value("${disburse.worker.batch-size}") int batchSize) {
        this.repo = repo;
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${disburse.worker.poll-interval-ms}")
    public void tick() {
        List<Disbursement> batch = claim();
        for (Disbursement d : batch) {
            try {
                drive(d);
            } catch (RuntimeException ex) {
                log.error("Worker error for disbursement {}: {}", d.id(), ex.getMessage(), ex);
            }
        }
    }

    @Transactional
    protected List<Disbursement> claim() {
        return repo.claimWorkBatch(batchSize);
    }

    private void drive(Disbursement d) {
        if (d.status() == DisbursementStatus.UNCERTAIN) {
            service.pollUncertain(d.id());
        } else {
            service.processAttempt(d.id());
        }
    }
}
