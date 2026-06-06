# Payment Disbursement Service

A Spring Boot 3.2 / Java 17 service that disburses loan payouts to borrowers over UPI, IMPS, and NEFT channels with exactly-once semantics under partial failures. Written as an engineering interview submission by Mandeep Attri (mandeep.attri@paytm.com).

---

## How to run

**Prerequisites**: JDK 17, Maven 3.8+.

```bash
cd payment-disbursement-service
mvn spring-boot:run
```

The service starts on `http://localhost:8080`. The H2 console is at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:disburse`).

### Endpoints

**Create a disbursement**
```bash
curl -s -X POST http://localhost:8080/disburse \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: idem-001" \
  -d '{"loanId":"LOAN-001","amount":200000,"accountNumber":"9876543210","ifsc":"HDFC0001234","beneficiaryName":"Ravi Kumar"}' \
  | jq .
```

**Get disbursement status**
```bash
curl -s http://localhost:8080/disburse/{id} | jq .
```
Replace `{id}` with the `id` field returned from the create call.

**Manual retry** (only valid when status is `FAILED`)
```bash
curl -s -X POST http://localhost:8080/disburse/{id}/retry | jq .
```

**Run reconciliation** (paste CSV inline via a temp file)
```bash
cat > /tmp/bank.csv <<'EOF'
reference_id,status,amount,timestamp
REF-abc123,SUCCESS,200000,2026-06-05T10:00:00Z
EOF

curl -s -X POST http://localhost:8080/reconcile \
  -F "file=@/tmp/bank.csv" | jq .
```

The reconciliation endpoint returns a JSON array of break records, each with a `breakType` of `INTERNAL_ONLY`, `BANK_ONLY`, `AMOUNT_MISMATCH`, or `STATUS_MISMATCH`.

---

## How to test

```bash
mvn test
```

35 tests run; 1 is disabled (`ConcurrentWorkerTest`) because H2 does not enforce `SKIP LOCKED` — running it on H2 would produce a false-green.

**Named scenario tests** (in `src/test/java/com/paytm/disburse/scenarios/`):

| Test class | Method |
|---|---|
| `HappyPathTest` | `rs_2L_via_imps_succeeds_first_try` |
| `ChannelFallbackTest` | `upi_exhausted_falls_back_to_imps` |
| `TransientRetryTest` | `imps_timeout_then_success_on_second_attempt` |
| `UncertainResolutionTest` | `uncertain_state_never_falls_back_to_different_channel` |
| `PermanentFailureTest` | `invalid_ifsc_does_not_retry_or_fall_back` |
| `IdempotencyTest` | `same_loan_id_creates_only_one_disbursement` |
| `ReconciliationTest` | `internal_success_but_no_bank_row_creates_INTERNAL_ONLY_break` |

---

## Architecture at a glance

- Two aggregate state machines: disbursement-level (`PENDING` → `IN_FLIGHT` → `SUCCESS/FAILED/UNCERTAIN/PENDING_RETRY`) and attempt-level — details in [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md).
- Amount-tiered channel routing (UPI ≤ ₹1L, IMPS ≤ ₹5L, NEFT for all) with Resilience4j circuit breakers — see [`DESIGN_DECISIONS.md`](DESIGN_DECISIONS.md#channel-routing).
- Three-layer idempotency: DB unique constraint, HTTP idempotency key, per-attempt `reference_id` written before the channel call.
- `UNCERTAIN` is an explicit state that blocks fallback until the channel's status endpoint confirms the outcome.
- Full design rationale: [`docs/specs/2026-06-05-payment-disbursement-design.md`](docs/specs/2026-06-05-payment-disbursement-design.md).

---

## Observability

Prometheus metrics are exposed at `http://localhost:8080/actuator/prometheus`.

Grafana dashboard JSON: [`dashboards/grafana-disbursement.json`](dashboards/grafana-disbursement.json). Import it into any Grafana instance pointed at the Prometheus endpoint above.

---

## What I'd change for production

- **Postgres instead of H2**: the SQL is identical (I used JdbcTemplate and avoided JPA-generated DDL deliberately); swap the datasource config and driver.
- **Secret management**: database credentials and channel API keys go into Vault or AWS Secrets Manager — not `application.properties`.
- **Queue-backed worker**: replace the `@Scheduled` poll with a Kafka or SQS consumer so the worker scales horizontally without racing on the same rows.
- **Real channel SDKs**: the mock clients implement scripted behavior; swap them for the actual NPCI IMPS SDK, UPI PSP client, and RBI NEFT adapter behind the `ChannelClient` interface.
- **Stricter timeout and retry config**: current timeouts (2s/8s/30s) are illustrative. Production values come from actual p99 latency data per channel; the Resilience4j config in `application.properties` is the single place to tune them.
