# Payment Disbursement Service — Design Spec

**Date:** 2026-06-05
**Status:** Approved for implementation
**Owner:** Mandeep Attri

---

## 1. Problem & Constraints

Build a service that reliably transfers approved loan amounts to borrower bank accounts via IMPS, NEFT, or UPI. The service must guarantee **exactly-once disbursement per loan** under partial failures (timeouts, retries, channel outages, crashes).

**Hard constraints from the assignment:**
- Duplicate disbursement is *unrecoverable* — money paid twice cannot be clawed back.
- Channels have different speed/cost/success-rate/limit profiles and occasional outages.
- Failures split into transient (retry) vs permanent (do not retry).
- Bank statements arrive T+1; we must detect record-vs-statement discrepancies.

**Soft constraints (engineering choices):**
- Java 17 + Spring Boot 3.2 + Maven, H2 embedded for the exercise.
- 6–8 hours implementation budget. Prefer correctness depth over breadth.
- No external infrastructure required to run locally.

---

## 2. Architecture

```
+----------------+      +---------------+      +-------------------+
|  HTTP API      |----->|  Disburse-    |----->|  Channel Strategy |
|  (Controller)  |      |  mentService  |      |  (UPI/IMPS/NEFT)  |
+----------------+      +-------+-------+      +---------+---------+
                                |                        |
                                v                        v
                        +-------+--------+      +--------+--------+
                        |  Repository    |      |  Channel        |
                        |  (JdbcTemplate)|      |  Clients (mock) |
                        +-------+--------+      +-----------------+
                                |
                                v
                        +-------+--------+
                        |  H2 (MVStore)  |
                        +----------------+

      +-----------+                 +-------------------+
      |  Worker   |-- polls -->     |  disbursement_    |
      |  @Sched   |                 |  attempt          |
      +-----------+                 +-------------------+
```

The service is one Spring Boot application with three concurrent concerns:
1. **HTTP request handling** (synchronous, fast — only creates the disbursement row).
2. **Background worker** (asynchronous, drives attempts forward).
3. **Reconciliation handler** (on-demand via API, compares records to bank statement).

The worker is what makes the system reliable — the HTTP layer never blocks on a channel call.

---

## 3. Domain Model

Two aggregates. The split is deliberate: **disbursement** is the loan-level intent (one per loan_id, forever), **attempt** is one channel call (many per disbursement).

### `disbursement`
| field | type | notes |
|---|---|---|
| id | UUID PK | |
| loan_id | VARCHAR UNIQUE | natural idempotency key |
| borrower_account | VARCHAR | account number |
| borrower_ifsc | VARCHAR | for IMPS/NEFT |
| borrower_upi | VARCHAR NULL | for UPI |
| amount_paise | BIGINT | money in paise to avoid float |
| status | ENUM | see state machine |
| current_attempt_id | UUID NULL | FK to active attempt |
| idempotency_key | VARCHAR NULL UNIQUE | client header |
| created_at, updated_at | TIMESTAMP | |
| version | INT | optimistic locking |

### `disbursement_attempt`
| field | type | notes |
|---|---|---|
| id | UUID PK | this IS the reference_id sent to channel |
| disbursement_id | UUID FK | |
| channel | ENUM | UPI/IMPS/NEFT |
| attempt_number | INT | 1..N for this channel |
| status | ENUM | IN_FLIGHT/SUCCESS/FAILED_TRANSIENT/FAILED_PERMANENT/UNCERTAIN |
| failure_reason | VARCHAR NULL | structured error code |
| channel_response | TEXT NULL | raw response for debugging |
| created_at, completed_at | TIMESTAMP | |

**Why two tables:** the attempt history is forensic evidence for reconciliation breaks ("we tried UPI 3 times then IMPS once; bank shows IMPS credit"). Collapsing into one row destroys this.

---

## 4. State Machine

There are **two** state machines: one for the disbursement aggregate, one for each attempt. Keeping them separate prevents the common bug where "the disbursement failed" is conflated with "this attempt failed."

### 4.1 Disbursement statuses
```
       ┌─────────┐
       │ PENDING │  created via POST /disburse, no attempts yet
       └────┬────┘
            │ worker creates first attempt
            v
       ┌───────────┐    attempt is FAILED_TRANSIENT and retries/fallback remain
       │ IN_FLIGHT │<──────────────────────────────────────────────────────────┐
       └─────┬─────┘                                                           │
             │                                                                 │
   ┌─────────┼─────────────┬─────────────┐                                     │
   v         v             v             v                                     │
SUCCESS   FAILED        UNCERTAIN    PENDING_RETRY                             │
(term)    (term,       (an attempt   (transient fail, backing off              │
          all channels timed out;    before next attempt)                      │
          exhausted    polling)            │                                   │
          OR permanent     │               └───────── worker wakes ────────────┘
          failure)         │
                           └── poll resolves → SUCCESS or → IN_FLIGHT (fallback)
```

`FAILED` is terminal unless the operator calls `POST /disburse/{id}/retry`, which transitions back to `PENDING_RETRY` and creates a fresh attempt.

### 4.2 Attempt statuses
```
  IN_FLIGHT ──► SUCCESS              (channel confirmed credit)
            ──► FAILED_TRANSIENT     (retryable: timeout-before-send, 5xx, rate limit)
            ──► FAILED_PERMANENT     (terminal: invalid IFSC, account closed, KYC)
            ──► UNCERTAIN ──► SUCCESS / FAILED_TRANSIENT / FAILED_PERMANENT
                              (resolved by polling the channel /status endpoint)
```

Attempt statuses never leave their terminal state. A new attempt is a new row.

### 4.3 Invariants
- A disbursement in `UNCERTAIN` is **never** moved to a different channel until the uncertain attempt resolves. This is the bug most naive implementations have.
- A disbursement with any attempt in `SUCCESS` cannot transition out of `SUCCESS`. Once money moved, it moved.
- A disbursement in `FAILED` because of a `FAILED_PERMANENT` attempt cannot be auto-retried — the operator must explicitly call `/retry` with awareness of why it failed.

Transitions are enforced in `DisbursementService.transitionTo()` — any invalid transition throws `IllegalStateTransitionException` and is logged + metered.

---

## 5. Idempotency Design (the central engineering decision)

Three layers, **all must hold for exactly-once**:

### Layer 1: API-level natural key (loan_id UNIQUE constraint)
- `POST /disburse` with a `loan_id` that already exists returns the existing `disbursement_id` + current status. Never creates a duplicate.
- DB-enforced via UNIQUE constraint, not application logic — race-safe.

### Layer 2: API-level `Idempotency-Key` header (Stripe-style)
- Optional header. If present, server stores `(idempotency_key → response_hash, response_body)` for 24h.
- Replay with same key returns the cached response. Different key with same loan_id still hits Layer 1.
- Implements the case "two different services in our platform both think they should disburse the same loan."

### Layer 3: Channel-call idempotency via reference_id
- Each `disbursement_attempt.id` IS the `reference_id` sent to the channel.
- **Persisted to DB before the channel call** (write-ahead). If the process crashes mid-call, recovery sees the in-flight attempt and polls instead of replaying.
- Retry on the **same channel** reuses the same reference_id → channel returns "duplicate" → we know it already processed and poll for status.
- Fallback to a **different channel** generates a new attempt with a new reference_id, but **only** after the current attempt is in a terminal state (SUCCESS / FAILED_PERMANENT / FAILED_TRANSIENT confirmed). UNCERTAIN does not permit fallback.

### Why all three are needed
Layer 1 alone fails when the API returns 500 to the client (client may retry with new request and same loan_id — Layer 1 covers this) but doesn't help if the worker crashes mid-call (Layer 3 covers this).

Layer 2 covers the case where the *same* request is replayed at the HTTP layer.

Layer 3 covers the case where the channel call itself is in doubt.

---

## 6. Channel Selection Strategy

A `ChannelRouter` interface returns an ordered list of channels to try for a given disbursement. The default implementation is `AmountTieredChannelRouter`:

```
amount ≤ ₹1,00,000:   [UPI, IMPS, NEFT]   // use free instant when we can
amount ≤ ₹5,00,000:   [IMPS, NEFT]        // UPI can't handle, fall back to slow+cheap
amount  > ₹5,00,000:  [NEFT]              // only channel that supports
```

**Circuit breaker per channel**: each channel client is wrapped in a `CircuitBreaker` (Resilience4j). Opens after 5 consecutive failures in 60s. While open, router skips that channel. Half-opens after 30s.

This solves the "UPI is down → fall back" scenario without hardcoding — if UPI's circuit is open, the router sees `[IMPS, NEFT]` for a ≤₹1L disbursement.

**Permanent failures bypass fallback**: `FAILED_PERMANENT` on a disbursement means *no further channel attempts* — invalid IFSC isn't fixed by switching channels.

The Strategy pattern means a `CostOptimizedChannelRouter` or `BusinessHoursChannelRouter` could be plugged in without touching the rest of the system.

---

## 7. Retry Policy

Per-channel:
- Max attempts: **3**
- Backoff: **2s → 8s → 30s** with **±25% jitter** to avoid thundering herd on a recovering channel
- After 3 transient failures, mark this channel exhausted for this disbursement; router moves to next channel
- All channels exhausted → status becomes `FAILED` (terminal pending manual review)

Failure classification (channel client's job to return one of these):
- **Permanent**: `INVALID_IFSC`, `ACCOUNT_CLOSED`, `BENEFICIARY_KYC_FAILED`, `BLOCKED_ACCOUNT` → no retry, no fallback
- **Transient**: `TIMEOUT_CONFIRMED_NOT_PROCESSED`, `RATE_LIMITED`, `CHANNEL_5XX`, `NETWORK_ERROR_BEFORE_SEND` → retry
- **Uncertain**: `TIMEOUT_AFTER_SEND`, `CHANNEL_RESPONSE_UNPARSEABLE` → goto UNCERTAIN state, poll channel

UNCERTAIN polling:
- Poll channel's `/status?reference_id=X` endpoint
- Max 5 polls with backoff 5s → 15s → 30s → 60s → 120s
- After 5 polls, alert ops via metric and leave UNCERTAIN; reconciliation will eventually resolve

Manual `POST /disburse/{id}/retry` is allowed only when disbursement `status = FAILED`. Returns `409 Conflict` otherwise (including for IN_FLIGHT and UNCERTAIN — operators must not race the worker). Generates a fresh attempt and transitions the disbursement to `PENDING_RETRY`.

---

## 8. Background Worker

Single-process `@Scheduled(fixedDelay = 2000)` worker. Each tick:

```sql
SELECT * FROM disbursement
WHERE status IN ('PENDING', 'PENDING_RETRY', 'IN_FLIGHT', 'UNCERTAIN')
  AND (next_action_at IS NULL OR next_action_at <= NOW())
ORDER BY created_at
LIMIT 50
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE SKIP LOCKED` lets multiple worker replicas run safely in production. H2 supports it in MVStore mode; same SQL works in Postgres unchanged.

For each row, the worker:
1. Decides next action based on current state (call channel / poll for status / retry / fallback).
2. Updates DB *before* the action (write-ahead).
3. Executes the action.
4. Records the outcome in a new transaction.

Steps 2 and 4 are separate transactions on purpose — the channel call cannot be inside a DB transaction because it's a network call.

---

## 9. Reconciliation

`POST /reconcile` accepts a CSV bank statement:

```csv
bank_reference_id,transaction_date,amount_paise,account,ifsc,status
TXN-IMPS-001234,2026-06-05,5000000,XXXX1234,HDFC0001234,SUCCESS
```

**Matching algorithm** (executed in-process for the exercise; production: batch job):

1. **Exact match** on `(reference_id, amount, account)` — happy path, ~99%.
2. **Soft match** for breaks where reference_id is missing/garbled:
   - Same `(amount, account, date ± 1)` and no exact match for either side → probable match, flag for human review.
3. **Discrepancies reported**:
   - `BANK_ONLY` — money left our account, no internal record. Critical alert.
   - `INTERNAL_ONLY` — we show SUCCESS, bank shows nothing. Possible UNCERTAIN that resolved wrongly. Critical alert.
   - `AMOUNT_MISMATCH` — same reference, different amount. Critical alert.
   - `STATUS_MISMATCH` — we show SUCCESS, bank shows FAILED. Roll back internal status.

Response: JSON summary + per-break details.

---

## 10. Mock Payment Channels

`ChannelClient` interface implemented by `UpiClient`, `ImpsClient`, `NeftClient`. Each mock:
- Reads its profile (success_rate, mean_latency, p99_latency, max_amount) from `application.yml` — configurable for tests.
- Generates outcomes per `Random.nextDouble() < success_rate`.
- Simulates timeouts (Thread.sleep beyond a threshold → return UNCERTAIN).
- Maintains an in-memory `Set<reference_id>` of "already processed" references — second call with same ref returns `DUPLICATE` (matches the assignment requirement).
- Exposes `/mock/{channel}/status?ref=X` so the worker can poll for UNCERTAIN resolution.

For tests, channels are swappable with deterministic test doubles (no randomness).

---

## 11. Observability

- **Structured logging** via Logback JSON encoder. Every log line includes `disbursement_id`, `loan_id`, `attempt_id` via MDC.
- **Micrometer metrics** exposed at `/actuator/prometheus`:
  - `disbursement.created` (counter, tag: channel)
  - `disbursement.completed` (counter, tags: channel, outcome)
  - `disbursement.attempt.duration` (timer, tags: channel, outcome)
  - `disbursement.state.transitions` (counter, tags: from, to)
  - `channel.circuit_breaker.state` (gauge, tag: channel)
  - `reconcile.breaks` (counter, tag: type)
  - `worker.queue.depth` (gauge)
- **Grafana dashboard JSON** committed to `dashboards/` — panels for throughput, success rate per channel, p99 latency, circuit breaker state, queue depth, reconciliation breaks.
- **Health checks**: `/actuator/health` reports DB + each channel's circuit state.

---

## 12. API Contract

### `POST /disburse`
```json
Request:
{
  "loan_id": "LOAN-2026-001234",
  "borrower_account": "XXXX1234",
  "borrower_ifsc": "HDFC0001234",
  "borrower_upi": "borrower@upi",
  "amount_paise": 5000000
}
Headers (optional):
  Idempotency-Key: <uuid>

Response 201 (or 200 if idempotent replay):
{
  "disbursement_id": "uuid",
  "status": "PENDING",
  "created_at": "2026-06-05T10:00:00Z"
}
Response 409 Conflict (different request body, same idempotency key):
{ "error": "IDEMPOTENCY_KEY_REUSED", ... }
```

### `GET /disburse/{id}`
```json
Response 200:
{
  "disbursement_id": "uuid",
  "loan_id": "...",
  "status": "SUCCESS",
  "amount_paise": 5000000,
  "attempts": [
    { "channel": "UPI",  "status": "FAILED_TRANSIENT", "reason": "TIMEOUT", "at": "..." },
    { "channel": "IMPS", "status": "SUCCESS", "reference_id": "uuid", "at": "..." }
  ]
}
```

### `POST /disburse/{id}/retry`
Returns `200` with new attempt info, or `409` if not in a retryable state.

### `POST /reconcile`
Accepts `multipart/form-data` with CSV file. Returns reconciliation report JSON.

---

## 13. Storage Schema (DDL sketch)

```sql
CREATE TABLE disbursement (
  id              UUID PRIMARY KEY,
  loan_id         VARCHAR(64) NOT NULL UNIQUE,
  borrower_account VARCHAR(32) NOT NULL,
  borrower_ifsc   VARCHAR(16),
  borrower_upi    VARCHAR(64),
  amount_paise    BIGINT NOT NULL CHECK (amount_paise > 0),
  status          VARCHAR(24) NOT NULL,
  current_attempt_id UUID,
  idempotency_key VARCHAR(128) UNIQUE,
  idempotency_request_hash VARCHAR(64),
  idempotency_response TEXT,
  next_action_at  TIMESTAMP,
  version         INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE disbursement_attempt (
  id              UUID PRIMARY KEY,        -- this is the reference_id
  disbursement_id UUID NOT NULL REFERENCES disbursement(id),
  channel         VARCHAR(8) NOT NULL,
  attempt_number  INT NOT NULL,
  status          VARCHAR(24) NOT NULL,
  failure_reason  VARCHAR(64),
  channel_response TEXT,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at    TIMESTAMP,
  poll_count      INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_attempt_disbursement ON disbursement_attempt(disbursement_id);
CREATE INDEX idx_disbursement_status_action ON disbursement(status, next_action_at);
```

---

## 14. Testing Strategy

Tests prove **behaviors under failure**, not just happy paths. The six scenarios from the PDF each get a dedicated integration test:

| Scenario | Test class |
|---|---|
| Happy path: ₹50k IMPS first try | `HappyPathTest` |
| Transient + retry | `TransientRetryTest` |
| Permanent (invalid IFSC) | `PermanentFailureTest` |
| Duplicate prevention (same loan_id) | `IdempotencyTest` |
| Channel fallback (UPI down → IMPS) | `ChannelFallbackTest` |
| Reconciliation mismatch | `ReconciliationTest` |

Plus:
- `StateMachineTest` — every valid + invalid transition.
- `UncertainResolutionTest` — kill-switch the channel response mid-call, ensure worker polls and resolves correctly. **This is the hardest test and the most important one.**
- `ConcurrentWorkerTest` — two workers, 100 disbursements, no double-process via SKIP LOCKED.

Channel mocks have a deterministic mode for tests (no `Random`, scripted responses).

---

## 15. Trade-offs & Future Work

Documented in detail in `DESIGN_DECISIONS.md` (the deliverable). Highlights:

**Chosen**:
- H2 over Postgres for exercise (production: Postgres, same SQL works).
- In-process worker over a queue (Kafka/SQS) — keeps deployment story to one binary. Production: extract worker, use a queue.
- `SELECT FOR UPDATE SKIP LOCKED` over an external lock service. Scales to ~10 worker replicas before DB contention dominates.
- Per-attempt reference_id over per-disbursement — clearer audit trail, easier reconciliation.

**Rejected**:
- Saga / 2-phase commit across channel — channels don't support compensation in the real world.
- Optimistic concurrency only (no `FOR UPDATE`) — race between two workers picking the same row is too risky.
- Idempotency at HTTP layer only — wouldn't help with worker crashes mid-channel-call.

**Future work** (in DESIGN_DECISIONS.md):
- Per-borrower velocity limits / fraud checks.
- Pull-based reconciliation (we poll bank's API daily instead of accepting CSV).
- Multi-region active-active with conflict resolution on `loan_id`.
- Replace embedded worker with Temporal/Camunda for workflow versioning.
