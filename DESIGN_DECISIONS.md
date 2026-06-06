# Design Decisions

Author: Mandeep Attri — mandeep.attri@paytm.com

---

## The single thing I optimized for

Exactly-once disbursement under partial failures, end to end. Not just at the API layer — at the channel call layer. A borrower must receive money exactly once regardless of whether the app crashes mid-call, the channel times out, or a retry worker picks up the same row twice. Every other decision below is in service of that constraint.

---

## Three-layer idempotency

I built three independent layers because each one defends a different failure mode.

**Layer 1 — `loan_id` UNIQUE constraint (DB-enforced):** A duplicate `POST /disburse` with the same loan ID returns the existing record immediately, before any business logic runs. This defends against the client calling twice (network retry, double-click). It is enforced at the database, not in application code, so it holds even under concurrent requests hitting different JVM instances.

**Layer 2 — `Idempotency-Key` header with SHA-256 request body hash:** For callers that reuse an idempotency key, the service verifies the hash of the new request body matches the stored hash. A match means "safe replay — return the cached response". A mismatch returns HTTP 409, because reusing an idempotency key with a different body is a client bug, not a retryable condition. This defends against an upstream service replaying the same key with a subtly changed payload (different amount, different account) after a timeout.

**Layer 3 — per-attempt `reference_id` written to the DB before the channel call:** Before making any channel call, the worker writes a unique `reference_id` to the attempt row. If the app crashes immediately after the channel call is sent but before the response is processed, the next worker pickup finds an existing `reference_id` and polls the channel's status endpoint with it rather than issuing a new payment. This defends against the crash-after-send scenario — the most dangerous one, because money may already have moved.

---

## The UNCERTAIN state

When a channel call times out after the network packet has left the JVM, money may have moved. A naive service flips the attempt to `FAILED` and retries on the next channel in the route — that is a double-disbursement path. I modeled `UNCERTAIN` as an explicit disbursement state that blocks fallback entirely. An `UNCERTAIN` disbursement is resolved only by polling the channel's status endpoint with the same `reference_id`. Only when the channel confirms `SUCCESS` or `FAILED` does the disbursement transition out of `UNCERTAIN`. This is the single most important design call in the service. The UNCERTAIN state is what separates "we tried and it timed out, so we'll just try again" (wrong) from "we don't know, so we will ask before doing anything else" (correct).

Real-world basis: NPCI IMPS, UPI PSP APIs, and RBI NEFT all expose a transaction status lookup by reference ID. The assumption is not hypothetical.

---

## Two state machines, not one

The disbursement and the attempt have separate state machines. Disbursement-level states: `PENDING`, `IN_FLIGHT`, `PENDING_RETRY`, `UNCERTAIN`, `SUCCESS`, `FAILED`. Attempt-level states: `IN_FLIGHT`, `SUCCESS`, `FAILED_TRANSIENT`, `FAILED_PERMANENT`, `UNCERTAIN`.

Conflating them produces the bug "this attempt failed, therefore the disbursement failed." That is wrong when there are remaining retries or fallback channels. By keeping them separate, `DisbursementService` can move an attempt to `FAILED_TRANSIENT` while leaving the disbursement in `PENDING_RETRY`, ready for the next worker tick to try a different channel or backoff slot. The `StateMachine` class enforces the allowed transitions for each type and throws on illegal ones — caught by `StateMachineTest`.

---

## Channel routing

Routing is amount-tiered: UPI for amounts up to ₹1 lakh, IMPS for amounts up to ₹5 lakh (including fallback from UPI), NEFT for everything. The default ordering is speed-first because borrowers expect near-instant credit after loan approval. NEFT is the slowest channel and appears last in every tier.

`AmountTieredChannelRouter` accepts a `Supplier<Set<Channel>>` of currently-open (tripped) circuit breakers. This seam means the routing policy is pluggable — a cost-optimized strategy (prefer NEFT off-peak) or a capacity-weighted strategy can be wired in by providing a different implementation. `CircuitBreakerChannelGuard` wraps Resilience4j and provides the current set of open circuits so they are filtered out of the route before the worker ever attempts them.

---

## Permanent vs transient failure classification

`FailureReason` carries a `Kind` — either `PERMANENT` or `TRANSIENT`. Permanent failures (`INVALID_IFSC`, `ACCOUNT_CLOSED`) skip both retry and fallback. There is no value in trying NEFT when the IFSC code is invalid across all channels; retrying consumes worker capacity and delays the borrower getting a correct resolution. Transient failures (timeouts, rate limits) retry on the same channel up to 3 times using 2s/8s/30s backoff with 25% jitter, then fall back to the next channel in the route. `UNCERTAIN` is neither — it polls.

---

## JdbcTemplate over JPA

I chose JdbcTemplate for three reasons. First, I need `SELECT ... FOR UPDATE SKIP LOCKED` in `claimWorkBatch` — this is not expressible cleanly in JPQL or Criteria API without native query escapes. Second, I want write-ahead semantics: the worker writes the `reference_id` to the DB and flushes it before making the channel call. JPA's first-level cache would make it possible to call `persist()` and have the row not actually be in the database until the transaction commits — which is after the channel call. That breaks the write-ahead guarantee. Third, explicit SQL makes the insert and update order deterministic, which matters for deadlock avoidance when multiple workers run concurrently.

---

## Storage

H2 in MVStore mode with PostgreSQL compatibility dialect for this exercise. In production: Postgres. The SQL is identical — I deliberately wrote standard SQL with no JPA-generated DDL and no H2-specific extensions, so swapping the datasource config and driver is the only change needed.

---

## Rejected alternatives

**Saga / 2PC across channels**: Real payment channels do not expose a compensation endpoint. IMPS has no "reverse this payment" API that completes synchronously. Sagas require compensatable operations; that assumption doesn't hold here.

**Optimistic concurrency only (no `FOR UPDATE SKIP LOCKED`)**: Two workers reading the same pending row before either writes would both claim it and produce two channel calls. `SKIP LOCKED` ensures a row in flight is invisible to the second worker at the DB level.

**Idempotency at the HTTP layer alone**: Covers the caller-replay case but not the worker-crash-mid-channel-call case. After a crash, the next worker pickup bypasses the HTTP layer entirely — it reads directly from the DB. HTTP-layer idempotency provides no protection there.

**Channel idempotency by `loan_id` rather than `reference_id`**: Couples each retry attempt to the loan identifier. If a retry is needed, using the same `loan_id` as the idempotency key would make the channel reject the retry as a duplicate. A fresh `reference_id` per attempt gives the channel a new idempotency unit while still allowing the worker to look up the status of a previous attempt if needed.

---

## What I'd do with more time

- Replace the `@Scheduled` embedded worker with Temporal or Camunda. Channel routing rules change frequently in production (e.g., a bank disables UPI on weekends); workflow versioning handles that without redeployment.
- Per-borrower velocity limits as a fraud signal before the disbursement reaches the channel.
- Pull-based reconciliation: poll the bank's API daily rather than waiting for a CSV email. The `/reconcile` endpoint exists so the interviewer can drive the reconciliation manually.
- Real Postgres in Testcontainers to bring back `ConcurrentWorkerTest`. That test verifies the `SKIP LOCKED` guarantee at the DB level, which H2 does not enforce.
- Outbox table for emitting a `DisbursementSucceeded` event to downstream systems (loan accounting, notifications) on `SUCCESS`, instead of having consumers poll the status endpoint.

---

## Assumptions

- Channels expose a status lookup by `reference_id`. NPCI IMPS, UPI PSP APIs, and RBI NEFT all do this in practice. The mock clients implement a `getStatus(referenceId)` method that mirrors that behavior.
- Reusing an `Idempotency-Key` header with a different request body returns HTTP 409. That signals a client bug; silently accepting the new body would make idempotency keys meaningless.
- Reconciliation runs T+1 from a CSV the bank emails us. In production this would be a scheduled batch job; the `/reconcile` endpoint exists to make the scenario interactively testable.
