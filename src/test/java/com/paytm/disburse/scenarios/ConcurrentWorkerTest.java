package com.paytm.disburse.scenarios;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ConcurrentWorkerTest {

    @Test
    @Disabled("H2 in-memory does not provide the row-level locking semantics required to reliably test " +
              "this; the SELECT ... FOR UPDATE SKIP LOCKED behavior is exercised in production against " +
              "Postgres. Documented in DESIGN_DECISIONS.md.")
    void two_workers_dont_double_process_any_row() {
        // Intentionally empty — see @Disabled message.
    }
}
