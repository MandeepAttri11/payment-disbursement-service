package com.paytm.disburse.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.disburse.api.dto.DisburseRequest;
import com.paytm.disburse.api.dto.DisburseResponse;
import com.paytm.disburse.api.dto.DisbursementDetailResponse;
import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/disburse")
public class DisbursementController {

    private final DisbursementService service;
    private final IdempotencyService idempotency;
    private final ObjectMapper json;

    public DisbursementController(DisbursementService service, IdempotencyService idempotency,
                                  ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<DisburseResponse> create(
        @Valid @RequestBody DisburseRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) throws Exception {
        String hash = idempotencyKey == null ? null : idempotency.hash(json.writeValueAsString(body));
        Disbursement d = service.create(new CreateDisbursementCommand(
            body.loanId(), body.borrowerAccount(), body.borrowerIfsc(), body.borrowerUpi(),
            body.amountPaise(), idempotencyKey, hash));
        return ResponseEntity.status(201).body(DisburseResponse.from(d));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DisbursementDetailResponse> get(@PathVariable UUID id) {
        return service.findById(id)
            .map(d -> ResponseEntity.ok(DisbursementDetailResponse.from(d, service.attemptsFor(id))))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<DisburseResponse> retry(@PathVariable UUID id) {
        Disbursement d = service.manualRetry(id);
        return ResponseEntity.ok(DisburseResponse.from(d));
    }
}
