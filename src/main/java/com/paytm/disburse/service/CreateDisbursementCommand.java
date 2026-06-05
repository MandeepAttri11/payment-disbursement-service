package com.paytm.disburse.service;

public record CreateDisbursementCommand(
    String loanId,
    String borrowerAccount,
    String borrowerIfsc,
    String borrowerUpi,
    long amountPaise,
    String idempotencyKey,
    String requestBodyHash
) {}
