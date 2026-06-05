package com.paytm.disburse.api.dto;

import jakarta.validation.constraints.*;

public record DisburseRequest(
    @NotBlank @Size(max=64) String loanId,
    @NotBlank @Size(max=32) String borrowerAccount,
    @Size(max=16) String borrowerIfsc,
    @Size(max=64) String borrowerUpi,
    @Positive long amountPaise
) {}
