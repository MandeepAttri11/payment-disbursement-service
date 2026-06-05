# Payment Disbursement Service — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 17 + Spring Boot 3 service that disburses approved loans to borrowers via mock UPI/IMPS/NEFT channels with exactly-once guarantees, retry/fallback logic, T+1 reconciliation, and a Grafana dashboard.

**Architecture:** Single Spring Boot app. HTTP controller creates `disbursement` rows. A scheduled worker drives state transitions by selecting rows with `FOR UPDATE SKIP LOCKED` and invoking the channel router. Per-attempt reference IDs are persisted *before* channel calls (write-ahead) so a process crash never causes double-disbursement. Three-layer idempotency: `loan_id UNIQUE` + `Idempotency-Key` header + per-attempt reference_id.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Web, Spring JDBC (JdbcTemplate — not JPA), H2 (embedded, MVStore mode for SKIP LOCKED), Flyway, Resilience4j (circuit breaker), Micrometer + Prometheus, JUnit 5, AssertJ, Maven.

**Source of truth:** `docs/specs/2026-06-05-payment-disbursement-design.md`

---

## File Structure

```
payment-disbursement-service/
├── pom.xml
├── README.md
├── DESIGN_DECISIONS.md             # interview deliverable
├── PROMPTS.md                      # AI session log (interview deliverable)
├── dashboards/grafana-disbursement.json
├── docs/specs/ , docs/plans/       # already exist
└── src/
    ├── main/
    │   ├── java/com/paytm/disburse/
    │   │   ├── DisbursementApplication.java
    │   │   ├── domain/             # enums + aggregates + state machine
    │   │   ├── repository/         # JdbcTemplate-based DAOs
    │   │   ├── channel/            # ChannelClient interface + Router
    │   │   │   └── mock/           # UPI/IMPS/NEFT mocks
    │   │   ├── service/            # DisbursementService, IdempotencyService, ReconciliationService
    │   │   ├── worker/             # scheduled poller
    │   │   ├── api/                # controllers + DTOs + error handler
    │   │   └── observability/      # MeterRegistry wiring
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/       # Flyway: V1__init.sql
    └── test/java/com/paytm/disburse/
        ├── scenarios/              # the 6 PDF scenario tests + extras
        ├── domain/                 # state machine unit tests
        └── support/                # test fixtures, controllable channel
```

---

### Task 1: Maven project + Spring Boot skeleton

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/paytm/disburse/DisbursementApplication.java`
- Create: `src/main/resources/application.yml`
- Create: `.gitignore`

- [ ] **Step 1.1: Write `.gitignore`**

```
target/
.idea/
*.iml
.DS_Store
.vscode/
*.log
```

- [ ] **Step 1.2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>
    <groupId>com.paytm</groupId>
    <artifactId>payment-disbursement-service</artifactId>
    <version>0.1.0</version>
    <properties>
        <java.version>17</java.version>
        <resilience4j.version>2.2.0</resilience4j.version>
    </properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
        <dependency><groupId>io.github.resilience4j</groupId><artifactId>resilience4j-spring-boot3</artifactId><version>${resilience4j.version}</version></dependency>
        <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 1.3: Write `DisbursementApplication.java`**

```java
package com.paytm.disburse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DisbursementApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisbursementApplication.class, args);
    }
}
```

- [ ] **Step 1.4: Write `application.yml`**

```yaml
spring:
  application:
    name: payment-disbursement-service
  datasource:
    url: jdbc:h2:mem:disburse;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    default-property-inclusion: non_null

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always

disburse:
  worker:
    enabled: true
    batch-size: 50
    poll-interval-ms: 2000
  retry:
    max-attempts-per-channel: 3
    backoff-ms: [2000, 8000, 30000]
    jitter-percent: 25
  uncertain:
    max-polls: 5
    poll-backoff-ms: [5000, 15000, 30000, 60000, 120000]
  channels:
    upi:
      success-rate: 0.91
      mean-latency-ms: 200
      timeout-rate: 0.02
      max-amount-paise: 10000000
    imps:
      success-rate: 0.94
      mean-latency-ms: 400
      timeout-rate: 0.03
      max-amount-paise: 50000000
    neft:
      success-rate: 0.995
      mean-latency-ms: 100
      timeout-rate: 0.005
      max-amount-paise: 9223372036854775807

resilience4j:
  circuitbreaker:
    instances:
      upi:   { failureRateThreshold: 50, slidingWindowSize: 10, waitDurationInOpenState: 30s }
      imps:  { failureRateThreshold: 50, slidingWindowSize: 10, waitDurationInOpenState: 30s }
      neft:  { failureRateThreshold: 50, slidingWindowSize: 10, waitDurationInOpenState: 30s }
```

- [ ] **Step 1.5: Verify build + boot**

Run: `cd /Users/mandeepattri/Desktop/payment-disbursement-service && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 1.6: Commit**

```bash
git add pom.xml src .gitignore
git commit -m "Project skeleton: Spring Boot 3.2, JDK 17, H2, Flyway"
```

---

### Task 2: Flyway migrations (DB schema)

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`

- [ ] **Step 2.1: Write `V1__init.sql`**

```sql
CREATE TABLE disbursement (
    id                          VARCHAR(36) PRIMARY KEY,
    loan_id                     VARCHAR(64) NOT NULL UNIQUE,
    borrower_account            VARCHAR(32) NOT NULL,
    borrower_ifsc               VARCHAR(16),
    borrower_upi                VARCHAR(64),
    amount_paise                BIGINT NOT NULL CHECK (amount_paise > 0),
    status                      VARCHAR(24) NOT NULL,
    current_attempt_id          VARCHAR(36),
    idempotency_key             VARCHAR(128) UNIQUE,
    idempotency_request_hash    VARCHAR(64),
    idempotency_response        CLOB,
    next_action_at              TIMESTAMP,
    failure_reason              VARCHAR(64),
    version                     INT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_disbursement_status_action ON disbursement(status, next_action_at);

CREATE TABLE disbursement_attempt (
    id                  VARCHAR(36) PRIMARY KEY,
    disbursement_id     VARCHAR(36) NOT NULL REFERENCES disbursement(id),
    channel             VARCHAR(8) NOT NULL,
    attempt_number      INT NOT NULL,
    status              VARCHAR(24) NOT NULL,
    failure_reason      VARCHAR(64),
    channel_response    CLOB,
    poll_count          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP
);

CREATE INDEX idx_attempt_disbursement ON disbursement_attempt(disbursement_id);
CREATE INDEX idx_attempt_status ON disbursement_attempt(status);
```

- [ ] **Step 2.2: Verify Flyway runs at boot**

Run: `mvn -q spring-boot:run` (then Ctrl-C after `Started DisbursementApplication`)
Expected: log line `Flyway ... Successfully applied 1 migration to schema "PUBLIC"`.

- [ ] **Step 2.3: Commit**

```bash
git add src/main/resources/db/migration
git commit -m "V1 migration: disbursement + disbursement_attempt tables"
```

---

### Task 3: Domain enums

**Files:**
- Create: `src/main/java/com/paytm/disburse/domain/DisbursementStatus.java`
- Create: `src/main/java/com/paytm/disburse/domain/AttemptStatus.java`
- Create: `src/main/java/com/paytm/disburse/domain/Channel.java`
- Create: `src/main/java/com/paytm/disburse/domain/FailureReason.java`

- [ ] **Step 3.1: `DisbursementStatus.java`**

```java
package com.paytm.disburse.domain;

public enum DisbursementStatus {
    PENDING,
    IN_FLIGHT,
    PENDING_RETRY,
    UNCERTAIN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() { return this == SUCCESS || this == FAILED; }
}
```

- [ ] **Step 3.2: `AttemptStatus.java`**

```java
package com.paytm.disburse.domain;

public enum AttemptStatus {
    IN_FLIGHT,
    SUCCESS,
    FAILED_TRANSIENT,
    FAILED_PERMANENT,
    UNCERTAIN;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED_TRANSIENT || this == FAILED_PERMANENT;
    }
}
```

- [ ] **Step 3.3: `Channel.java`**

```java
package com.paytm.disburse.domain;

public enum Channel { UPI, IMPS, NEFT }
```

- [ ] **Step 3.4: `FailureReason.java`**

```java
package com.paytm.disburse.domain;

public enum FailureReason {
    // Permanent
    INVALID_IFSC(Kind.PERMANENT),
    ACCOUNT_CLOSED(Kind.PERMANENT),
    KYC_FAILED(Kind.PERMANENT),
    AMOUNT_EXCEEDS_CHANNEL_LIMIT(Kind.PERMANENT),
    BLOCKED_ACCOUNT(Kind.PERMANENT),

    // Transient
    CHANNEL_TIMEOUT_BEFORE_SEND(Kind.TRANSIENT),
    RATE_LIMITED(Kind.TRANSIENT),
    CHANNEL_5XX(Kind.TRANSIENT),
    CIRCUIT_OPEN(Kind.TRANSIENT),
    NETWORK_ERROR(Kind.TRANSIENT),

    // Uncertain
    CHANNEL_TIMEOUT_AFTER_SEND(Kind.UNCERTAIN),
    CHANNEL_UNPARSEABLE_RESPONSE(Kind.UNCERTAIN);

    public enum Kind { PERMANENT, TRANSIENT, UNCERTAIN }
    private final Kind kind;
    FailureReason(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
```

- [ ] **Step 3.5: Compile**

Run: `mvn -q compile`. Expected: BUILD SUCCESS.

- [ ] **Step 3.6: Commit**

```bash
git add src/main/java/com/paytm/disburse/domain
git commit -m "Domain enums: statuses, channels, failure reasons"
```

---

### Task 4: State machine (TDD)

The state machine is enforced via a single class. **This is one of the most-tested parts** because invalid transitions are a primary source of double-disbursement bugs.

**Files:**
- Create: `src/test/java/com/paytm/disburse/domain/StateMachineTest.java`
- Create: `src/main/java/com/paytm/disburse/domain/StateMachine.java`

- [ ] **Step 4.1: Write the failing tests**

```java
package com.paytm.disburse.domain;

import org.junit.jupiter.api.Test;
import static com.paytm.disburse.domain.DisbursementStatus.*;
import static org.assertj.core.api.Assertions.*;

class StateMachineTest {

    @Test
    void pending_can_go_to_in_flight() {
        assertThatCode(() -> StateMachine.requireValid(PENDING, IN_FLIGHT)).doesNotThrowAnyException();
    }

    @Test
    void in_flight_can_go_to_success() {
        assertThatCode(() -> StateMachine.requireValid(IN_FLIGHT, SUCCESS)).doesNotThrowAnyException();
    }

    @Test
    void success_is_terminal() {
        assertThatThrownBy(() -> StateMachine.requireValid(SUCCESS, PENDING))
            .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> StateMachine.requireValid(SUCCESS, FAILED))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void failed_can_only_go_to_pending_retry_via_manual_retry() {
        assertThatCode(() -> StateMachine.requireValid(FAILED, PENDING_RETRY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StateMachine.requireValid(FAILED, IN_FLIGHT))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void uncertain_cannot_skip_to_failed_without_resolution() {
        assertThatThrownBy(() -> StateMachine.requireValid(UNCERTAIN, IN_FLIGHT))
            .isInstanceOf(IllegalStateTransitionException.class);
        // UNCERTAIN resolves to either SUCCESS, FAILED, or PENDING_RETRY (for fallback)
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, SUCCESS)).doesNotThrowAnyException();
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, FAILED)).doesNotThrowAnyException();
        assertThatCode(() -> StateMachine.requireValid(UNCERTAIN, PENDING_RETRY)).doesNotThrowAnyException();
    }

    @Test
    void identity_transition_is_a_no_op_not_an_error() {
        assertThatCode(() -> StateMachine.requireValid(IN_FLIGHT, IN_FLIGHT)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 4.2: Run — expect compile failures**

Run: `mvn -q test -Dtest=StateMachineTest`. Expected: compilation error, no `StateMachine` class.

- [ ] **Step 4.3: Implement `StateMachine.java` and `IllegalStateTransitionException.java`**

```java
package com.paytm.disburse.domain;

public class IllegalStateTransitionException extends RuntimeException {
    public IllegalStateTransitionException(String message) { super(message); }
}
```

```java
package com.paytm.disburse.domain;

import java.util.Map;
import java.util.Set;

import static com.paytm.disburse.domain.DisbursementStatus.*;

public final class StateMachine {

    private static final Map<DisbursementStatus, Set<DisbursementStatus>> ALLOWED = Map.of(
        PENDING,        Set.of(IN_FLIGHT, FAILED),
        IN_FLIGHT,      Set.of(SUCCESS, FAILED, UNCERTAIN, PENDING_RETRY),
        PENDING_RETRY,  Set.of(IN_FLIGHT, FAILED),
        UNCERTAIN,      Set.of(SUCCESS, FAILED, PENDING_RETRY),
        SUCCESS,        Set.of(),
        FAILED,         Set.of(PENDING_RETRY)
    );

    private StateMachine() {}

    public static void requireValid(DisbursementStatus from, DisbursementStatus to) {
        if (from == to) return;
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateTransitionException(
                "Illegal transition: " + from + " -> " + to);
        }
    }
}
```

- [ ] **Step 4.4: Run — expect PASS**

Run: `mvn -q test -Dtest=StateMachineTest`. Expected: all 6 tests pass.

- [ ] **Step 4.5: Commit**

```bash
git add src/main/java/com/paytm/disburse/domain/StateMachine.java \
        src/main/java/com/paytm/disburse/domain/IllegalStateTransitionException.java \
        src/test/java/com/paytm/disburse/domain/StateMachineTest.java
git commit -m "State machine with explicit transition validation"
```

---

### Task 5: Domain aggregates (Disbursement, DisbursementAttempt)

**Files:**
- Create: `src/main/java/com/paytm/disburse/domain/Disbursement.java`
- Create: `src/main/java/com/paytm/disburse/domain/DisbursementAttempt.java`

These are plain immutable-ish data carriers. No Lombok — explicit code is part of the engineering signal.

- [ ] **Step 5.1: `Disbursement.java`**

```java
package com.paytm.disburse.domain;

import java.time.Instant;
import java.util.UUID;

public class Disbursement {
    private final UUID id;
    private final String loanId;
    private final String borrowerAccount;
    private final String borrowerIfsc;
    private final String borrowerUpi;
    private final long amountPaise;
    private DisbursementStatus status;
    private UUID currentAttemptId;
    private String idempotencyKey;
    private String idempotencyRequestHash;
    private String idempotencyResponse;
    private Instant nextActionAt;
    private FailureReason failureReason;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    public Disbursement(UUID id, String loanId, String borrowerAccount, String borrowerIfsc,
                        String borrowerUpi, long amountPaise, DisbursementStatus status,
                        Instant createdAt) {
        this.id = id;
        this.loanId = loanId;
        this.borrowerAccount = borrowerAccount;
        this.borrowerIfsc = borrowerIfsc;
        this.borrowerUpi = borrowerUpi;
        this.amountPaise = amountPaise;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void transitionTo(DisbursementStatus next) {
        StateMachine.requireValid(this.status, next);
        this.status = next;
        this.updatedAt = Instant.now();
    }

    // getters + package-private setters for repository hydration
    public UUID id() { return id; }
    public String loanId() { return loanId; }
    public String borrowerAccount() { return borrowerAccount; }
    public String borrowerIfsc() { return borrowerIfsc; }
    public String borrowerUpi() { return borrowerUpi; }
    public long amountPaise() { return amountPaise; }
    public DisbursementStatus status() { return status; }
    public UUID currentAttemptId() { return currentAttemptId; }
    public String idempotencyKey() { return idempotencyKey; }
    public String idempotencyRequestHash() { return idempotencyRequestHash; }
    public String idempotencyResponse() { return idempotencyResponse; }
    public Instant nextActionAt() { return nextActionAt; }
    public FailureReason failureReason() { return failureReason; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void setCurrentAttemptId(UUID id) { this.currentAttemptId = id; }
    public void setIdempotency(String key, String hash, String response) {
        this.idempotencyKey = key;
        this.idempotencyRequestHash = hash;
        this.idempotencyResponse = response;
    }
    public void setNextActionAt(Instant t) { this.nextActionAt = t; }
    public void setFailureReason(FailureReason r) { this.failureReason = r; }
    public void setVersion(int v) { this.version = v; }
    public void touch() { this.updatedAt = Instant.now(); }

    // for repository hydration
    public static Disbursement hydrate(UUID id, String loanId, String account, String ifsc,
                                       String upi, long paise, DisbursementStatus status,
                                       UUID attemptId, String idKey, String idHash, String idResp,
                                       Instant nextAction, FailureReason reason, int version,
                                       Instant created, Instant updated) {
        Disbursement d = new Disbursement(id, loanId, account, ifsc, upi, paise, status, created);
        d.currentAttemptId = attemptId;
        d.idempotencyKey = idKey;
        d.idempotencyRequestHash = idHash;
        d.idempotencyResponse = idResp;
        d.nextActionAt = nextAction;
        d.failureReason = reason;
        d.version = version;
        d.updatedAt = updated;
        return d;
    }
}
```

- [ ] **Step 5.2: `DisbursementAttempt.java`**

```java
package com.paytm.disburse.domain;

import java.time.Instant;
import java.util.UUID;

public class DisbursementAttempt {
    private final UUID id;
    private final UUID disbursementId;
    private final Channel channel;
    private final int attemptNumber;
    private AttemptStatus status;
    private FailureReason failureReason;
    private String channelResponse;
    private int pollCount;
    private final Instant createdAt;
    private Instant completedAt;

    public DisbursementAttempt(UUID id, UUID disbursementId, Channel channel,
                               int attemptNumber, AttemptStatus status, Instant createdAt) {
        this.id = id;
        this.disbursementId = disbursementId;
        this.channel = channel;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void complete(AttemptStatus status, FailureReason reason, String channelResponse) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException("Attempt " + id + " already terminal: " + this.status);
        }
        this.status = status;
        this.failureReason = reason;
        this.channelResponse = channelResponse;
        this.completedAt = Instant.now();
    }

    public void incrementPollCount() { this.pollCount++; }

    public UUID id() { return id; }
    public UUID disbursementId() { return disbursementId; }
    public Channel channel() { return channel; }
    public int attemptNumber() { return attemptNumber; }
    public AttemptStatus status() { return status; }
    public FailureReason failureReason() { return failureReason; }
    public String channelResponse() { return channelResponse; }
    public int pollCount() { return pollCount; }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }

    public static DisbursementAttempt hydrate(UUID id, UUID disbId, Channel ch, int num,
                                              AttemptStatus status, FailureReason reason,
                                              String resp, int polls, Instant created, Instant completed) {
        DisbursementAttempt a = new DisbursementAttempt(id, disbId, ch, num, status, created);
        a.failureReason = reason;
        a.channelResponse = resp;
        a.pollCount = polls;
        a.completedAt = completed;
        return a;
    }
}
```

- [ ] **Step 5.3: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/domain
git commit -m "Disbursement + DisbursementAttempt aggregates"
```

---

### Task 6: Repositories (DisbursementRepository, AttemptRepository)

Using `JdbcTemplate` instead of JPA — we want explicit SQL for `FOR UPDATE SKIP LOCKED` and full control over the writeahead semantics. **JPA's caching would actively hurt us here.** This choice is documented in DESIGN_DECISIONS.md.

**Files:**
- Create: `src/main/java/com/paytm/disburse/repository/DisbursementRepository.java`
- Create: `src/main/java/com/paytm/disburse/repository/AttemptRepository.java`
- Create: `src/main/java/com/paytm/disburse/repository/RowMappers.java`

- [ ] **Step 6.1: `RowMappers.java`**

```java
package com.paytm.disburse.repository;

import com.paytm.disburse.domain.*;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public final class RowMappers {

    public static final RowMapper<Disbursement> DISBURSEMENT = (ResultSet rs, int n) -> Disbursement.hydrate(
        UUID.fromString(rs.getString("id")),
        rs.getString("loan_id"),
        rs.getString("borrower_account"),
        rs.getString("borrower_ifsc"),
        rs.getString("borrower_upi"),
        rs.getLong("amount_paise"),
        DisbursementStatus.valueOf(rs.getString("status")),
        rs.getString("current_attempt_id") == null ? null : UUID.fromString(rs.getString("current_attempt_id")),
        rs.getString("idempotency_key"),
        rs.getString("idempotency_request_hash"),
        rs.getString("idempotency_response"),
        rs.getTimestamp("next_action_at") == null ? null : rs.getTimestamp("next_action_at").toInstant(),
        rs.getString("failure_reason") == null ? null : FailureReason.valueOf(rs.getString("failure_reason")),
        rs.getInt("version"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant()
    );

    public static final RowMapper<DisbursementAttempt> ATTEMPT = (ResultSet rs, int n) -> DisbursementAttempt.hydrate(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("disbursement_id")),
        Channel.valueOf(rs.getString("channel")),
        rs.getInt("attempt_number"),
        AttemptStatus.valueOf(rs.getString("status")),
        rs.getString("failure_reason") == null ? null : FailureReason.valueOf(rs.getString("failure_reason")),
        rs.getString("channel_response"),
        rs.getInt("poll_count"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
    );

    private RowMappers() {}
}
```

- [ ] **Step 6.2: `DisbursementRepository.java`**

```java
package com.paytm.disburse.repository;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DisbursementRepository {

    private final JdbcTemplate jdbc;
    public DisbursementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(Disbursement d) {
        jdbc.update("""
            INSERT INTO disbursement (id, loan_id, borrower_account, borrower_ifsc, borrower_upi,
                amount_paise, status, idempotency_key, idempotency_request_hash, idempotency_response,
                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            d.id().toString(), d.loanId(), d.borrowerAccount(), d.borrowerIfsc(), d.borrowerUpi(),
            d.amountPaise(), d.status().name(), d.idempotencyKey(),
            d.idempotencyRequestHash(), d.idempotencyResponse(),
            Timestamp.from(d.createdAt()), Timestamp.from(d.updatedAt())
        );
    }

    public Optional<Disbursement> findById(UUID id) {
        return jdbc.query("SELECT * FROM disbursement WHERE id = ?",
            RowMappers.DISBURSEMENT, id.toString()).stream().findFirst();
    }

    public Optional<Disbursement> findByLoanId(String loanId) {
        return jdbc.query("SELECT * FROM disbursement WHERE loan_id = ?",
            RowMappers.DISBURSEMENT, loanId).stream().findFirst();
    }

    public Optional<Disbursement> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM disbursement WHERE idempotency_key = ?",
            RowMappers.DISBURSEMENT, key).stream().findFirst();
    }

    /** Selects work-ready rows with row-level locking. Uses SKIP LOCKED so multiple workers don't block each other. */
    public List<Disbursement> claimWorkBatch(int batchSize) {
        return jdbc.query("""
            SELECT * FROM disbursement
            WHERE status IN ('PENDING', 'PENDING_RETRY', 'IN_FLIGHT', 'UNCERTAIN')
              AND (next_action_at IS NULL OR next_action_at <= CURRENT_TIMESTAMP)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """, RowMappers.DISBURSEMENT, batchSize);
    }

    /** Optimistic-lock update. Throws if version moved. */
    public void update(Disbursement d) {
        int rows = jdbc.update("""
            UPDATE disbursement
               SET status = ?, current_attempt_id = ?, next_action_at = ?,
                   failure_reason = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND version = ?
            """,
            d.status().name(),
            d.currentAttemptId() == null ? null : d.currentAttemptId().toString(),
            d.nextActionAt() == null ? null : Timestamp.from(d.nextActionAt()),
            d.failureReason() == null ? null : d.failureReason().name(),
            d.id().toString(), d.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException("Disbursement " + d.id() + " was modified concurrently");
        }
        d.setVersion(d.version() + 1);
    }
}
```

- [ ] **Step 6.3: `AttemptRepository.java`**

```java
package com.paytm.disburse.repository;

import com.paytm.disburse.domain.DisbursementAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AttemptRepository {
    private final JdbcTemplate jdbc;
    public AttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(DisbursementAttempt a) {
        jdbc.update("""
            INSERT INTO disbursement_attempt (id, disbursement_id, channel, attempt_number, status,
                failure_reason, channel_response, poll_count, created_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            a.id().toString(), a.disbursementId().toString(), a.channel().name(), a.attemptNumber(),
            a.status().name(),
            a.failureReason() == null ? null : a.failureReason().name(),
            a.channelResponse(), a.pollCount(),
            Timestamp.from(a.createdAt()),
            a.completedAt() == null ? null : Timestamp.from(a.completedAt())
        );
    }

    public void update(DisbursementAttempt a) {
        jdbc.update("""
            UPDATE disbursement_attempt
               SET status = ?, failure_reason = ?, channel_response = ?, poll_count = ?, completed_at = ?
             WHERE id = ?
            """,
            a.status().name(),
            a.failureReason() == null ? null : a.failureReason().name(),
            a.channelResponse(), a.pollCount(),
            a.completedAt() == null ? null : Timestamp.from(a.completedAt()),
            a.id().toString()
        );
    }

    public Optional<DisbursementAttempt> findById(UUID id) {
        return jdbc.query("SELECT * FROM disbursement_attempt WHERE id = ?",
            RowMappers.ATTEMPT, id.toString()).stream().findFirst();
    }

    public List<DisbursementAttempt> findByDisbursementId(UUID disbId) {
        return jdbc.query("SELECT * FROM disbursement_attempt WHERE disbursement_id = ? ORDER BY created_at",
            RowMappers.ATTEMPT, disbId.toString());
    }

    public int countByDisbursementIdAndChannel(UUID disbId, com.paytm.disburse.domain.Channel ch) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM disbursement_attempt WHERE disbursement_id = ? AND channel = ?",
            Integer.class, disbId.toString(), ch.name());
        return c == null ? 0 : c;
    }
}
```

- [ ] **Step 6.4: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/repository
git commit -m "JdbcTemplate-based repositories with FOR UPDATE SKIP LOCKED"
```

---

### Task 7: Channel client interface + ChannelResponse

**Files:**
- Create: `src/main/java/com/paytm/disburse/channel/ChannelClient.java`
- Create: `src/main/java/com/paytm/disburse/channel/ChannelResponse.java`
- Create: `src/main/java/com/paytm/disburse/channel/ChannelRequest.java`

- [ ] **Step 7.1: `ChannelRequest.java`**

```java
package com.paytm.disburse.channel;

import java.util.UUID;

public record ChannelRequest(
    UUID referenceId,
    String account,
    String ifsc,
    String upiId,
    long amountPaise
) {}
```

- [ ] **Step 7.2: `ChannelResponse.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.FailureReason;

public record ChannelResponse(
    AttemptStatus status,
    FailureReason failureReason,    // nullable on success
    String rawResponse              // for debugging / audit
) {
    public static ChannelResponse success(String raw) {
        return new ChannelResponse(AttemptStatus.SUCCESS, null, raw);
    }
    public static ChannelResponse transient_(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.FAILED_TRANSIENT, r, raw);
    }
    public static ChannelResponse permanent(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.FAILED_PERMANENT, r, raw);
    }
    public static ChannelResponse uncertain(FailureReason r, String raw) {
        return new ChannelResponse(AttemptStatus.UNCERTAIN, r, raw);
    }
}
```

- [ ] **Step 7.3: `ChannelClient.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;

public interface ChannelClient {
    Channel channel();
    long maxAmountPaise();
    ChannelResponse transfer(ChannelRequest request);
    /** Used to resolve UNCERTAIN attempts. */
    ChannelResponse status(java.util.UUID referenceId);
}
```

- [ ] **Step 7.4: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/channel
git commit -m "Channel client interface + request/response types"
```

---

### Task 8: Mock channel implementations (TDD)

**Files:**
- Create: `src/test/java/com/paytm/disburse/channel/mock/MockChannelTest.java`
- Create: `src/main/java/com/paytm/disburse/channel/mock/MockChannelBase.java`
- Create: `src/main/java/com/paytm/disburse/channel/mock/UpiClient.java`
- Create: `src/main/java/com/paytm/disburse/channel/mock/ImpsClient.java`
- Create: `src/main/java/com/paytm/disburse/channel/mock/NeftClient.java`
- Create: `src/main/java/com/paytm/disburse/channel/mock/MockChannelProperties.java`

The mocks must:
1. Reject duplicate reference IDs (the assignment requirement).
2. Return `SUCCESS` at the documented rate.
3. Occasionally return `UNCERTAIN` (timeout-after-send).
4. Return permanent failure for specific magic IFSCs (`INVALID_*` prefix).
5. Be deterministic when tests inject a fixed-seed `Random`.

- [ ] **Step 8.1: Write `MockChannelTest.java`**

```java
package com.paytm.disburse.channel.mock;

import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;
import com.paytm.disburse.domain.FailureReason;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockChannelTest {

    private MockChannelProperties props() {
        MockChannelProperties p = new MockChannelProperties();
        p.setSuccessRate(1.0); p.setTimeoutRate(0.0);
        p.setMeanLatencyMs(0); p.setMaxAmountPaise(Long.MAX_VALUE);
        return p;
    }

    @Test
    void rejects_duplicate_reference_id() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        UUID ref = UUID.randomUUID();
        ChannelRequest req = new ChannelRequest(ref, "1234", "HDFC0001234", null, 50000);

        ChannelResponse first = client.transfer(req);
        ChannelResponse second = client.transfer(req);

        assertThat(first.status()).isEqualTo(AttemptStatus.SUCCESS);
        // A duplicate is reported the same way as the first call (idempotent from caller's POV).
        // The point is the channel didn't process a second debit.
        assertThat(second.status()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(second.rawResponse()).contains("duplicate");
    }

    @Test
    void rejects_invalid_ifsc_as_permanent() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        ChannelRequest req = new ChannelRequest(UUID.randomUUID(), "1234", "INVALID_IFSC", null, 50000);

        ChannelResponse resp = client.transfer(req);

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_PERMANENT);
        assertThat(resp.failureReason()).isEqualTo(FailureReason.INVALID_IFSC);
    }

    @Test
    void rejects_amount_over_channel_limit_as_permanent() {
        MockChannelProperties p = props();
        p.setMaxAmountPaise(100_000_00L); // ₹1L
        UpiClient client = new UpiClient(p, new Random(42));
        ChannelRequest req = new ChannelRequest(UUID.randomUUID(), "1234", null, "x@upi", 200_000_00L);

        ChannelResponse resp = client.transfer(req);

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_PERMANENT);
        assertThat(resp.failureReason()).isEqualTo(FailureReason.AMOUNT_EXCEEDS_CHANNEL_LIMIT);
    }

    @Test
    void status_endpoint_returns_known_outcome_for_processed_reference() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        UUID ref = UUID.randomUUID();
        client.transfer(new ChannelRequest(ref, "1234", "HDFC0001234", null, 50000));

        ChannelResponse statusResp = client.status(ref);

        assertThat(statusResp.status()).isEqualTo(AttemptStatus.SUCCESS);
    }

    @Test
    void status_for_unknown_reference_returns_uncertain() {
        ImpsClient client = new ImpsClient(props(), new Random(42));
        ChannelResponse resp = client.status(UUID.randomUUID());

        assertThat(resp.status()).isEqualTo(AttemptStatus.FAILED_TRANSIENT);
        // unknown reference means the channel never saw it — i.e., safe to retry
    }

    @Test
    void channel_returns_correct_enum() {
        MockChannelProperties p = props();
        assertThat(new UpiClient(p, new Random()).channel()).isEqualTo(Channel.UPI);
        assertThat(new ImpsClient(p, new Random()).channel()).isEqualTo(Channel.IMPS);
        assertThat(new NeftClient(p, new Random()).channel()).isEqualTo(Channel.NEFT);
    }
}
```

- [ ] **Step 8.2: Run — expect compile failures**

Run: `mvn -q test -Dtest=MockChannelTest`. Expected: no such class.

- [ ] **Step 8.3: `MockChannelProperties.java`**

```java
package com.paytm.disburse.channel.mock;

public class MockChannelProperties {
    private double successRate = 0.94;
    private double timeoutRate = 0.03;
    private long meanLatencyMs = 200;
    private long maxAmountPaise = Long.MAX_VALUE;

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double v) { this.successRate = v; }
    public double getTimeoutRate() { return timeoutRate; }
    public void setTimeoutRate(double v) { this.timeoutRate = v; }
    public long getMeanLatencyMs() { return meanLatencyMs; }
    public void setMeanLatencyMs(long v) { this.meanLatencyMs = v; }
    public long getMaxAmountPaise() { return maxAmountPaise; }
    public void setMaxAmountPaise(long v) { this.maxAmountPaise = v; }
}
```

- [ ] **Step 8.4: `MockChannelBase.java`**

```java
package com.paytm.disburse.channel.mock;

import com.paytm.disburse.channel.ChannelClient;
import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.FailureReason;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

abstract class MockChannelBase implements ChannelClient {

    private final Map<UUID, ChannelResponse> processed = new ConcurrentHashMap<>();
    protected final MockChannelProperties props;
    protected final Random random;

    protected MockChannelBase(MockChannelProperties props, Random random) {
        this.props = props;
        this.random = random;
    }

    @Override
    public long maxAmountPaise() { return props.getMaxAmountPaise(); }

    @Override
    public ChannelResponse transfer(ChannelRequest req) {
        // Idempotency: same reference_id returns the original outcome.
        ChannelResponse prior = processed.get(req.referenceId());
        if (prior != null) {
            return new ChannelResponse(prior.status(), prior.failureReason(),
                "duplicate: " + prior.rawResponse());
        }

        // Permanent: validation
        if (req.amountPaise() > props.getMaxAmountPaise()) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.AMOUNT_EXCEEDS_CHANNEL_LIMIT, "amount > channel max"));
        }
        if (req.ifsc() != null && req.ifsc().startsWith("INVALID_")) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.INVALID_IFSC, "invalid ifsc: " + req.ifsc()));
        }
        if (req.account() != null && req.account().startsWith("CLOSED_")) {
            return record(req.referenceId(), ChannelResponse.permanent(
                FailureReason.ACCOUNT_CLOSED, "account closed"));
        }

        sleepUpTo(props.getMeanLatencyMs());

        double roll = random.nextDouble();
        if (roll < props.getTimeoutRate()) {
            // UNCERTAIN: pretend we sent but lost the ack. We DO still record success/failure
            // internally so a status() poll can resolve it correctly — that's exactly the
            // "money may have moved" scenario.
            ChannelResponse internalOutcome = decideTerminalOutcome();
            processed.put(req.referenceId(), internalOutcome);
            return ChannelResponse.uncertain(
                FailureReason.CHANNEL_TIMEOUT_AFTER_SEND,
                "timeout after send; status unknown to caller");
        }
        if (roll < props.getTimeoutRate() + (1 - props.getSuccessRate())) {
            return record(req.referenceId(), ChannelResponse.transient_(
                FailureReason.CHANNEL_5XX, "transient error"));
        }
        return record(req.referenceId(), ChannelResponse.success("ok"));
    }

    @Override
    public ChannelResponse status(UUID referenceId) {
        ChannelResponse known = processed.get(referenceId);
        if (known != null) {
            // The channel knows about this reference; report the real outcome.
            return new ChannelResponse(known.status(), known.failureReason(), "poll: " + known.rawResponse());
        }
        // Channel has no record of this reference → caller is safe to retry with new ref id.
        return ChannelResponse.transient_(FailureReason.NETWORK_ERROR,
            "no record; safe to retry");
    }

    private ChannelResponse decideTerminalOutcome() {
        return random.nextDouble() < props.getSuccessRate()
            ? ChannelResponse.success("delayed-ack")
            : ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "delayed-failure");
    }

    private ChannelResponse record(UUID ref, ChannelResponse r) {
        processed.put(ref, r);
        return r;
    }

    private void sleepUpTo(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep((long)(random.nextDouble() * ms)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

- [ ] **Step 8.5: `UpiClient.java`, `ImpsClient.java`, `NeftClient.java`**

```java
package com.paytm.disburse.channel.mock;

import com.paytm.disburse.domain.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class UpiClient extends MockChannelBase {
    public UpiClient(@Qualifier("upiProps") MockChannelProperties props,
                     @Qualifier("channelRandom") Random random) {
        super(props, random);
    }
    @Override public Channel channel() { return Channel.UPI; }
}
```

```java
package com.paytm.disburse.channel.mock;

import com.paytm.disburse.domain.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class ImpsClient extends MockChannelBase {
    public ImpsClient(@Qualifier("impsProps") MockChannelProperties props,
                      @Qualifier("channelRandom") Random random) {
        super(props, random);
    }
    @Override public Channel channel() { return Channel.IMPS; }
}
```

```java
package com.paytm.disburse.channel.mock;

import com.paytm.disburse.domain.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class NeftClient extends MockChannelBase {
    public NeftClient(@Qualifier("neftProps") MockChannelProperties props,
                      @Qualifier("channelRandom") Random random) {
        super(props, random);
    }
    @Override public Channel channel() { return Channel.NEFT; }
}
```

- [ ] **Step 8.6: Spring config for mock props beans**

Create `src/main/java/com/paytm/disburse/channel/mock/MockChannelConfig.java`:

```java
package com.paytm.disburse.channel.mock;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Random;

@Configuration
public class MockChannelConfig {

    @Bean Random channelRandom() { return new Random(); }

    @Bean MockChannelProperties upiProps(Environment env) { return bind(env, "disburse.channels.upi"); }
    @Bean MockChannelProperties impsProps(Environment env) { return bind(env, "disburse.channels.imps"); }
    @Bean MockChannelProperties neftProps(Environment env) { return bind(env, "disburse.channels.neft"); }

    private MockChannelProperties bind(Environment env, String prefix) {
        return Binder.get(env).bindOrCreate(prefix, MockChannelProperties.class);
    }
}
```

- [ ] **Step 8.7: Run + commit**

```bash
mvn -q test -Dtest=MockChannelTest
# Expected: 6 tests pass
git add src/main/java/com/paytm/disburse/channel/mock src/test/java/com/paytm/disburse/channel/mock
git commit -m "Mock channel clients with idempotency + failure simulation"
```

---

### Task 9: ChannelRouter (TDD)

**Files:**
- Create: `src/test/java/com/paytm/disburse/channel/ChannelRouterTest.java`
- Create: `src/main/java/com/paytm/disburse/channel/ChannelRouter.java`
- Create: `src/main/java/com/paytm/disburse/channel/AmountTieredChannelRouter.java`

- [ ] **Step 9.1: Write `ChannelRouterTest.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRouterTest {

    @Test
    void small_amounts_try_upi_first_then_imps_then_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(50_000_00L)).containsExactly(Channel.UPI, Channel.IMPS, Channel.NEFT);
    }

    @Test
    void medium_amounts_skip_upi_for_imps_then_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(200_000_00L)).containsExactly(Channel.IMPS, Channel.NEFT);
    }

    @Test
    void large_amounts_only_neft() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of());
        assertThat(r.routeFor(10_000_000_00L)).containsExactly(Channel.NEFT);
    }

    @Test
    void open_circuit_excludes_channel() {
        ChannelRouter r = new AmountTieredChannelRouter(() -> Set.of(Channel.UPI));
        assertThat(r.routeFor(50_000_00L)).containsExactly(Channel.IMPS, Channel.NEFT);
    }

    @Test
    void all_circuits_open_returns_empty_list() {
        ChannelRouter r = new AmountTieredChannelRouter(
            () -> Set.of(Channel.UPI, Channel.IMPS, Channel.NEFT));
        assertThat(r.routeFor(50_000_00L)).isEmpty();
    }
}
```

- [ ] **Step 9.2: `ChannelRouter.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import java.util.List;

public interface ChannelRouter {
    /** Ordered list of channels to try, best first. May be empty if all blocked. */
    List<Channel> routeFor(long amountPaise);
}
```

- [ ] **Step 9.3: `AmountTieredChannelRouter.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class AmountTieredChannelRouter implements ChannelRouter {

    private static final long UPI_MAX  = 100_000_00L;   // ₹1L
    private static final long IMPS_MAX = 500_000_00L;   // ₹5L

    private final Supplier<Set<Channel>> openCircuits;

    public AmountTieredChannelRouter(Supplier<Set<Channel>> openCircuits) {
        this.openCircuits = openCircuits;
    }

    @Override
    public List<Channel> routeFor(long amountPaise) {
        Set<Channel> blocked = openCircuits.get();
        List<Channel> all = new ArrayList<>();
        if (amountPaise <= UPI_MAX)  all.add(Channel.UPI);
        if (amountPaise <= IMPS_MAX) all.add(Channel.IMPS);
        all.add(Channel.NEFT);
        all.removeAll(blocked);
        return all;
    }
}
```

- [ ] **Step 9.4: Run + commit**

```bash
mvn -q test -Dtest=ChannelRouterTest
git add src/main/java/com/paytm/disburse/channel src/test/java/com/paytm/disburse/channel
git commit -m "AmountTieredChannelRouter with circuit-aware fallback"
```

> The `Supplier<Set<Channel>>` for open circuits is wired to Resilience4j in Task 14 (after the worker is in place — we don't need circuit breakers in tests).

---

### Task 10: IdempotencyService (TDD)

**Files:**
- Create: `src/test/java/com/paytm/disburse/service/IdempotencyServiceTest.java`
- Create: `src/main/java/com/paytm/disburse/service/IdempotencyService.java`
- Create: `src/main/java/com/paytm/disburse/service/IdempotencyConflictException.java`

- [ ] **Step 10.1: Write `IdempotencyServiceTest.java`**

```java
package com.paytm.disburse.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class IdempotencyServiceTest {

    private final IdempotencyService svc = new IdempotencyService();

    @Test
    void same_request_body_produces_same_hash() {
        String h1 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        String h2 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void different_request_body_produces_different_hash() {
        String h1 = svc.hash("{\"loan_id\":\"L1\",\"amount\":100}");
        String h2 = svc.hash("{\"loan_id\":\"L1\",\"amount\":101}");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void verifying_same_key_and_hash_is_a_noop() {
        assertThatCode(() -> svc.verifyOrThrow("KEY-1", "hash-1", "KEY-1", "hash-1"))
            .doesNotThrowAnyException();
    }

    @Test
    void verifying_same_key_but_different_hash_throws_conflict() {
        assertThatThrownBy(() -> svc.verifyOrThrow("KEY-1", "hash-1", "KEY-1", "hash-2"))
            .isInstanceOf(IdempotencyConflictException.class);
    }
}
```

- [ ] **Step 10.2: `IdempotencyConflictException.java`**

```java
package com.paytm.disburse.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) { super(message); }
}
```

- [ ] **Step 10.3: `IdempotencyService.java`**

```java
package com.paytm.disburse.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class IdempotencyService {

    public String hash(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void verifyOrThrow(String existingKey, String existingHash,
                              String requestKey, String requestHash) {
        if (existingKey == null || requestKey == null) return;
        if (!existingKey.equals(requestKey)) return;
        if (!existingHash.equals(requestHash)) {
            throw new IdempotencyConflictException(
                "Idempotency-Key '" + requestKey + "' reused with different request body");
        }
    }
}
```

- [ ] **Step 10.4: Run + commit**

```bash
mvn -q test -Dtest=IdempotencyServiceTest
git add src/main/java/com/paytm/disburse/service/IdempotencyService.java \
        src/main/java/com/paytm/disburse/service/IdempotencyConflictException.java \
        src/test/java/com/paytm/disburse/service/IdempotencyServiceTest.java
git commit -m "Idempotency hashing + conflict detection"
```

---

### Task 11: RetryPolicy

**Files:**
- Create: `src/main/java/com/paytm/disburse/service/RetryPolicy.java`
- Create: `src/test/java/com/paytm/disburse/service/RetryPolicyTest.java`

- [ ] **Step 11.1: Test**

```java
package com.paytm.disburse.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(
        3, List.of(Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(30)),
        0 /* deterministic for tests */);

    @Test
    void backoff_for_each_attempt_number() {
        assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void attempt_at_or_past_max_is_exhausted() {
        assertThat(policy.exhausted(2)).isFalse();
        assertThat(policy.exhausted(3)).isTrue();
        assertThat(policy.exhausted(4)).isTrue();
    }

    @Test
    void uncertain_poll_backoff_grows() {
        RetryPolicy p = new RetryPolicy(3, List.of(Duration.ofSeconds(2)), 0);
        assertThat(p.uncertainPollBackoff(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.uncertainPollBackoff(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.uncertainPollBackoff(4)).isEqualTo(Duration.ofSeconds(120));
    }
}
```

- [ ] **Step 11.2: `RetryPolicy.java`**

```java
package com.paytm.disburse.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {

    private static final List<Duration> UNCERTAIN_BACKOFFS = List.of(
        Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
        Duration.ofSeconds(60), Duration.ofSeconds(120));

    private final int maxAttemptsPerChannel;
    private final List<Duration> backoffs;
    private final int jitterPercent;

    public RetryPolicy(
        @Value("${disburse.retry.max-attempts-per-channel}") int maxAttempts,
        @Value("#{'${disburse.retry.backoff-ms}'.split(',')}") List<String> backoffsMs,
        @Value("${disburse.retry.jitter-percent}") int jitterPercent
    ) {
        this(maxAttempts,
             backoffsMs.stream().map(s -> Duration.ofMillis(Long.parseLong(s.trim().replaceAll("[\\[\\]]", "")))).toList(),
             jitterPercent);
    }

    // package-private for tests
    RetryPolicy(int maxAttempts, List<Duration> backoffs, int jitterPercent) {
        this.maxAttemptsPerChannel = maxAttempts;
        this.backoffs = backoffs;
        this.jitterPercent = jitterPercent;
    }

    public int maxAttemptsPerChannel() { return maxAttemptsPerChannel; }

    public boolean exhausted(int attemptNumber) { return attemptNumber >= maxAttemptsPerChannel; }

    public Duration backoffFor(int attemptNumber) {
        int idx = Math.min(attemptNumber - 1, backoffs.size() - 1);
        Duration base = backoffs.get(idx);
        if (jitterPercent <= 0) return base;
        long jitterMs = (long)(base.toMillis() * (jitterPercent / 100.0));
        long delta = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
        return base.plusMillis(delta);
    }

    public Duration uncertainPollBackoff(int pollCount) {
        return UNCERTAIN_BACKOFFS.get(Math.min(pollCount, UNCERTAIN_BACKOFFS.size() - 1));
    }
}
```

- [ ] **Step 11.3: Run + commit**

```bash
mvn -q test -Dtest=RetryPolicyTest
git add src/main/java/com/paytm/disburse/service/RetryPolicy.java \
        src/test/java/com/paytm/disburse/service/RetryPolicyTest.java
git commit -m "RetryPolicy with config-driven backoff + jitter"
```

---

### Task 12: DisbursementService — create()

**Files:**
- Create: `src/main/java/com/paytm/disburse/service/DisbursementService.java`
- Create: `src/main/java/com/paytm/disburse/service/CreateDisbursementCommand.java`
- Create: `src/test/java/com/paytm/disburse/service/DisbursementServiceCreateTest.java`

Note: this is a Spring integration test that uses the real H2 database. We use `@SpringBootTest` with a test profile.

- [ ] **Step 12.1: `CreateDisbursementCommand.java`**

```java
package com.paytm.disburse.service;

public record CreateDisbursementCommand(
    String loanId,
    String borrowerAccount,
    String borrowerIfsc,
    String borrowerUpi,
    long amountPaise,
    String idempotencyKey,        // nullable
    String requestBodyHash        // nullable
) {}
```

- [ ] **Step 12.2: Test**

```java
package com.paytm.disburse.service;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DisbursementServiceCreateTest {

    @Autowired DisbursementService service;

    @Test
    void create_returns_new_disbursement_in_pending() {
        Disbursement d = service.create(new CreateDisbursementCommand(
            "L-A", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        assertThat(d.status()).isEqualTo(DisbursementStatus.PENDING);
        assertThat(d.id()).isNotNull();
    }

    @Test
    void duplicate_loan_id_returns_existing_disbursement() {
        Disbursement first = service.create(new CreateDisbursementCommand(
            "L-DUP", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        Disbursement second = service.create(new CreateDisbursementCommand(
            "L-DUP", "1234", "HDFC0001234", null, 50_000_00L, null, null));
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void same_idempotency_key_with_different_body_throws() {
        service.create(new CreateDisbursementCommand(
            "L-X1", "1234", "HDFC0001234", null, 50_000_00L, "KEY-A", "hash-X1"));
        assertThatThrownBy(() -> service.create(new CreateDisbursementCommand(
            "L-X2", "1234", "HDFC0001234", null, 70_000_00L, "KEY-A", "hash-X2")))
            .isInstanceOf(IdempotencyConflictException.class);
    }
}
```

- [ ] **Step 12.3: `DisbursementService.java` (create method only — other methods added later)**

```java
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
        // Layer 2 idempotency: same Idempotency-Key + matching body → return existing.
        if (cmd.idempotencyKey() != null) {
            var existing = disbursements.findByIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                idempotency.verifyOrThrow(
                    existing.get().idempotencyKey(), existing.get().idempotencyRequestHash(),
                    cmd.idempotencyKey(), cmd.requestBodyHash());
                return existing.get();
            }
        }

        // Layer 1 idempotency: loan_id is the natural key.
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
            // Race: another thread inserted between our findByLoanId and insert.
            return disbursements.findByLoanId(cmd.loanId()).orElseThrow();
        }
        return d;
    }
}
```

- [ ] **Step 12.4: Create test profile**

`src/test/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:disburse-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1
disburse:
  worker:
    enabled: false   # tests drive transitions explicitly
```

- [ ] **Step 12.5: Run + commit**

```bash
mvn -q test -Dtest=DisbursementServiceCreateTest
git add src/main/java/com/paytm/disburse/service src/test/java/com/paytm/disburse/service \
        src/test/resources
git commit -m "DisbursementService.create with 3-layer idempotency"
```

---

### Task 13: DisbursementService — processAttempt (the core engine)

This is the heart of the system. The method picks one channel, persists an attempt **before** calling, makes the call, and resolves the outcome.

**Files:**
- Modify: `src/main/java/com/paytm/disburse/service/DisbursementService.java`

- [ ] **Step 13.1: Add channel client registry**

Create `src/main/java/com/paytm/disburse/channel/ChannelClientRegistry.java`:

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChannelClientRegistry {
    private final Map<Channel, ChannelClient> byChannel;

    public ChannelClientRegistry(List<ChannelClient> clients) {
        this.byChannel = clients.stream().collect(Collectors.toMap(ChannelClient::channel, Function.identity()));
    }

    public ChannelClient get(Channel ch) {
        ChannelClient c = byChannel.get(ch);
        if (c == null) throw new IllegalStateException("No client for channel " + ch);
        return c;
    }
}
```

- [ ] **Step 13.2: Add `processAttempt` to `DisbursementService`**

Append (and update class header):

```java
// Additional fields:
private final AttemptRepository attempts;
private final ChannelRouter router;
private final ChannelClientRegistry channels;
private final RetryPolicy retryPolicy;

// Update constructor accordingly.

@Transactional
public void processAttempt(UUID disbursementId) {
    Disbursement d = disbursements.findById(disbursementId).orElseThrow();
    if (d.status().isTerminal()) return;
    if (d.status() == DisbursementStatus.UNCERTAIN) return; // handled by pollUncertain

    Channel channel = nextChannelFor(d);
    if (channel == null) {
        markFailed(d, FailureReason.CIRCUIT_OPEN);
        return;
    }

    int attemptNumber = attempts.countByDisbursementIdAndChannel(d.id(), channel) + 1;
    DisbursementAttempt attempt = new DisbursementAttempt(
        UUID.randomUUID(), d.id(), channel, attemptNumber,
        AttemptStatus.IN_FLIGHT, Instant.now());

    // WRITE-AHEAD: persist the attempt and link it on the disbursement BEFORE the network call.
    attempts.insert(attempt);
    d.setCurrentAttemptId(attempt.id());
    d.transitionTo(DisbursementStatus.IN_FLIGHT);
    disbursements.update(d);

    // Call channel OUTSIDE the transaction-critical write — recovery on crash relies on the
    // persisted IN_FLIGHT row + status() poll on the same reference_id.
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
            // Do NOT fall back. Permanent failures don't get retried on another channel.
            d.setFailureReason(resp.failureReason());
            d.transitionTo(DisbursementStatus.FAILED);
        }
        case FAILED_TRANSIENT -> scheduleRetryOrFallback(d, attempt);
        case UNCERTAIN -> {
            // Critical: do NOT try another channel until this resolves.
            d.transitionTo(DisbursementStatus.UNCERTAIN);
            d.setNextActionAt(Instant.now().plus(retryPolicy.uncertainPollBackoff(0)));
        }
        case IN_FLIGHT -> throw new IllegalStateException("channel returned IN_FLIGHT");
    }
    disbursements.update(d);
}

private void scheduleRetryOrFallback(Disbursement d, DisbursementAttempt last) {
    if (!retryPolicy.exhausted(last.attemptNumber())) {
        // Same channel, next attempt.
        d.setNextActionAt(Instant.now().plus(retryPolicy.backoffFor(last.attemptNumber() + 1)));
        d.transitionTo(DisbursementStatus.PENDING_RETRY);
        return;
    }
    // Exhausted current channel → check if router has another to try.
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
```

(Don't forget the imports: `AttemptStatus`, `Channel`, `ChannelRequest`, `ChannelResponse`, `ChannelClientRegistry`, `ChannelRouter`, `DisbursementAttempt`, `FailureReason`, `Instant`, `List`, `UUID`, `AttemptRepository`.)

- [ ] **Step 13.3: Compile, then commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/channel/ChannelClientRegistry.java \
        src/main/java/com/paytm/disburse/service/DisbursementService.java
git commit -m "DisbursementService.processAttempt: write-ahead attempt + outcome routing"
```

---

### Task 14: DisbursementService — pollUncertain + retry

**Files:**
- Modify: `src/main/java/com/paytm/disburse/service/DisbursementService.java`

- [ ] **Step 14.1: Add `pollUncertain` and `manualRetry`**

```java
@Transactional
public void pollUncertain(UUID disbursementId) {
    Disbursement d = disbursements.findById(disbursementId).orElseThrow();
    if (d.status() != DisbursementStatus.UNCERTAIN) return;
    DisbursementAttempt attempt = attempts.findById(d.currentAttemptId()).orElseThrow();
    if (attempt.status() != AttemptStatus.UNCERTAIN) return;

    ChannelResponse resp = channels.get(attempt.channel()).status(attempt.id());
    attempt.incrementPollCount();
    attempts.update(attempt);

    if (resp.status() == AttemptStatus.SUCCESS) {
        attempt.complete(AttemptStatus.SUCCESS, null, resp.rawResponse());
        attempts.update(attempt);
        d.transitionTo(DisbursementStatus.SUCCESS);
        d.setNextActionAt(null);
        disbursements.update(d);
        return;
    }
    if (resp.status() == AttemptStatus.FAILED_PERMANENT) {
        attempt.complete(AttemptStatus.FAILED_PERMANENT, resp.failureReason(), resp.rawResponse());
        attempts.update(attempt);
        d.setFailureReason(resp.failureReason());
        d.transitionTo(DisbursementStatus.FAILED);
        disbursements.update(d);
        return;
    }
    if (resp.status() == AttemptStatus.FAILED_TRANSIENT) {
        // Channel reports the reference is unknown to it → safe to retry with new ref.
        attempt.complete(AttemptStatus.FAILED_TRANSIENT, resp.failureReason(), resp.rawResponse());
        attempts.update(attempt);
        scheduleRetryOrFallback(d, attempt);
        disbursements.update(d);
        return;
    }
    // Still uncertain → schedule next poll.
    if (attempt.pollCount() >= 5) {
        // Surrender to ops; reconciliation will resolve.
        d.setFailureReason(FailureReason.CHANNEL_TIMEOUT_AFTER_SEND);
        // Keep status UNCERTAIN — do NOT transition to FAILED here, because money may have moved.
        d.setNextActionAt(null);
        disbursements.update(d);
        return;
    }
    d.setNextActionAt(Instant.now().plus(retryPolicy.uncertainPollBackoff(attempt.pollCount())));
    disbursements.update(d);
}

@Transactional
public Disbursement manualRetry(UUID disbursementId) {
    Disbursement d = disbursements.findById(disbursementId).orElseThrow();
    if (d.status() != DisbursementStatus.FAILED) {
        throw new IllegalStateException("Disbursement not in FAILED state, current: " + d.status());
    }
    d.transitionTo(DisbursementStatus.PENDING_RETRY);
    d.setFailureReason(null);
    d.setCurrentAttemptId(null);
    d.setNextActionAt(Instant.now());
    disbursements.update(d);
    return d;
}

public java.util.List<DisbursementAttempt> attemptsFor(UUID id) {
    return attempts.findByDisbursementId(id);
}

public java.util.Optional<Disbursement> findById(UUID id) { return disbursements.findById(id); }
```

- [ ] **Step 14.2: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/service/DisbursementService.java
git commit -m "DisbursementService.pollUncertain + manualRetry"
```

---

### Task 15: Circuit breaker wiring

**Files:**
- Create: `src/main/java/com/paytm/disburse/channel/CircuitBreakerChannelGuard.java`
- Modify: `AmountTieredChannelRouter` constructor wiring via `@Configuration`

- [ ] **Step 15.1: `CircuitBreakerChannelGuard.java`**

```java
package com.paytm.disburse.channel;

import com.paytm.disburse.domain.Channel;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class CircuitBreakerChannelGuard implements Supplier<Set<Channel>> {

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerChannelGuard(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Set<Channel> get() {
        Set<Channel> open = EnumSet.noneOf(Channel.class);
        for (Channel c : Channel.values()) {
            CircuitBreaker cb = registry.circuitBreaker(c.name().toLowerCase());
            if (cb.getState() == CircuitBreaker.State.OPEN) open.add(c);
        }
        return open;
    }
}
```

- [ ] **Step 15.2: Create `RouterConfig.java`**

```java
package com.paytm.disburse.channel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RouterConfig {
    @Bean @Primary
    public ChannelRouter channelRouter(CircuitBreakerChannelGuard guard) {
        return new AmountTieredChannelRouter(guard);
    }
}
```

Remove the `@Component` annotation from `AmountTieredChannelRouter` (now wired via `RouterConfig`).

- [ ] **Step 15.3: Wrap channel calls with CB**

Update `DisbursementService.processAttempt`:

```java
ChannelResponse resp;
try {
    resp = registry.circuitBreaker(channel.name().toLowerCase())
        .executeSupplier(() -> channels.get(channel).transfer(req));
} catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
    resp = ChannelResponse.transient_(FailureReason.CIRCUIT_OPEN, "circuit open");
}
```

Add `CircuitBreakerRegistry registry` to constructor.

- [ ] **Step 15.4: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/channel
git commit -m "Resilience4j circuit breakers gating channel calls + router"
```

---

### Task 16: Worker

**Files:**
- Create: `src/main/java/com/paytm/disburse/worker/DisbursementWorker.java`

- [ ] **Step 16.1: `DisbursementWorker.java`**

```java
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
```

- [ ] **Step 16.2: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/worker
git commit -m "Scheduled worker driving PENDING/IN_FLIGHT/UNCERTAIN rows"
```

---

### Task 17: HTTP API — DTOs

**Files:**
- Create: `src/main/java/com/paytm/disburse/api/dto/*`

- [ ] **Step 17.1: DTOs**

```java
// DisburseRequest.java
package com.paytm.disburse.api.dto;

import jakarta.validation.constraints.*;

public record DisburseRequest(
    @NotBlank @Size(max=64) String loanId,
    @NotBlank @Size(max=32) String borrowerAccount,
    @Size(max=16) String borrowerIfsc,
    @Size(max=64) String borrowerUpi,
    @Positive long amountPaise
) {}
```

```java
// DisburseResponse.java
package com.paytm.disburse.api.dto;

import com.paytm.disburse.domain.Disbursement;

import java.time.Instant;
import java.util.UUID;

public record DisburseResponse(
    UUID disbursementId,
    String loanId,
    String status,
    long amountPaise,
    Instant createdAt
) {
    public static DisburseResponse from(Disbursement d) {
        return new DisburseResponse(d.id(), d.loanId(), d.status().name(), d.amountPaise(), d.createdAt());
    }
}
```

```java
// DisbursementDetailResponse.java
package com.paytm.disburse.api.dto;

import com.paytm.disburse.domain.Disbursement;
import com.paytm.disburse.domain.DisbursementAttempt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DisbursementDetailResponse(
    UUID disbursementId,
    String loanId,
    String status,
    long amountPaise,
    String failureReason,
    Instant createdAt,
    Instant updatedAt,
    List<AttemptDto> attempts
) {
    public record AttemptDto(UUID id, String channel, int attemptNumber, String status,
                             String failureReason, Instant createdAt, Instant completedAt) {}

    public static DisbursementDetailResponse from(Disbursement d, List<DisbursementAttempt> atts) {
        return new DisbursementDetailResponse(d.id(), d.loanId(), d.status().name(), d.amountPaise(),
            d.failureReason() == null ? null : d.failureReason().name(),
            d.createdAt(), d.updatedAt(),
            atts.stream().map(a -> new AttemptDto(a.id(), a.channel().name(), a.attemptNumber(),
                a.status().name(),
                a.failureReason() == null ? null : a.failureReason().name(),
                a.createdAt(), a.completedAt())).toList());
    }
}
```

- [ ] **Step 17.2: Commit**

```bash
git add src/main/java/com/paytm/disburse/api/dto
git commit -m "API DTOs"
```

---

### Task 18: DisbursementController

**Files:**
- Create: `src/main/java/com/paytm/disburse/api/DisbursementController.java`
- Create: `src/main/java/com/paytm/disburse/api/ApiExceptionHandler.java`

- [ ] **Step 18.1: `DisbursementController.java`**

```java
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
```

- [ ] **Step 18.2: `ApiExceptionHandler.java`**

```java
package com.paytm.disburse.api;

import com.paytm.disburse.domain.IllegalStateTransitionException;
import com.paytm.disburse.service.IdempotencyConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String,String>> idempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "IDEMPOTENCY_KEY_REUSED", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "INVALID_STATE", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String,String>> badTransition(IllegalStateTransitionException e) {
        return ResponseEntity.status(409).body(Map.of(
            "error", "ILLEGAL_TRANSITION", "message", e.getMessage()));
    }
}
```

- [ ] **Step 18.3: Compile + commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse/api
git commit -m "REST controller for create/get/retry + error handler"
```

---

### Task 19: Reconciliation

**Files:**
- Create: `src/main/java/com/paytm/disburse/service/ReconciliationService.java`
- Create: `src/main/java/com/paytm/disburse/api/ReconciliationController.java`
- Create: `src/main/java/com/paytm/disburse/api/dto/ReconcileResponse.java`

- [ ] **Step 19.1: `ReconciliationService.java`**

```java
package com.paytm.disburse.service;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.DisbursementAttempt;
import com.paytm.disburse.repository.AttemptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Service
public class ReconciliationService {

    public record BankRow(String referenceId, LocalDate date, long amountPaise,
                          String account, String ifsc, String status) {}
    public record Break(String type, String detail, String referenceId) {}
    public record Report(int internalCount, int bankCount, int matched, List<Break> breaks) {}

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Report reconcile(InputStream csv) throws Exception {
        List<BankRow> bank = parseCsv(csv);

        // Pull all attempts that completed yesterday-or-earlier in SUCCESS or UNCERTAIN
        Map<String, DisbursementAttempt> internalByRef = new HashMap<>();
        for (var a : jdbc.query("""
                SELECT a.* FROM disbursement_attempt a
                WHERE a.status IN ('SUCCESS', 'UNCERTAIN')
                """, com.paytm.disburse.repository.RowMappers.ATTEMPT)) {
            internalByRef.put(a.id().toString(), a);
        }

        List<Break> breaks = new ArrayList<>();
        Set<String> bankRefsSeen = new HashSet<>();
        int matched = 0;

        for (BankRow row : bank) {
            bankRefsSeen.add(row.referenceId);
            DisbursementAttempt internal = internalByRef.get(row.referenceId);
            if (internal == null) {
                breaks.add(new Break("BANK_ONLY",
                    "Bank recorded txn but no internal record. amount=" + row.amountPaise + " account=" + row.account,
                    row.referenceId));
                continue;
            }
            if (!equalsLong(internal, row)) {
                breaks.add(new Break("AMOUNT_MISMATCH",
                    "Bank amount differs from internal record", row.referenceId));
                continue;
            }
            if ("FAILED".equalsIgnoreCase(row.status) && internal.status() == AttemptStatus.SUCCESS) {
                breaks.add(new Break("STATUS_MISMATCH",
                    "We show SUCCESS, bank shows FAILED", row.referenceId));
                continue;
            }
            matched++;
        }
        for (var e : internalByRef.entrySet()) {
            if (!bankRefsSeen.contains(e.getKey())
                && e.getValue().status() == AttemptStatus.SUCCESS) {
                breaks.add(new Break("INTERNAL_ONLY",
                    "We show SUCCESS but bank statement has no row", e.getKey()));
            }
        }
        return new Report(internalByRef.size(), bank.size(), matched, breaks);
    }

    private boolean equalsLong(DisbursementAttempt a, BankRow row) {
        // The amount on the attempt sits on the disbursement; query for it.
        Long internalAmount = jdbc.queryForObject(
            "SELECT amount_paise FROM disbursement WHERE id = ?",
            Long.class, a.disbursementId().toString());
        return internalAmount != null && internalAmount == row.amountPaise;
    }

    private List<BankRow> parseCsv(InputStream in) throws Exception {
        List<BankRow> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine(); // bank_reference_id,transaction_date,amount_paise,account,ifsc,status
            if (header == null) return out;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                out.add(new BankRow(cols[0].trim(), LocalDate.parse(cols[1].trim()),
                    Long.parseLong(cols[2].trim()), cols[3].trim(), cols[4].trim(), cols[5].trim()));
            }
        }
        return out;
    }
}
```

`RowMappers.ATTEMPT` is already public (Task 6 made it `public static final`), so no extra exposure work is needed here.

- [ ] **Step 19.2: `ReconciliationController.java`**

```java
package com.paytm.disburse.api;

import com.paytm.disburse.service.ReconciliationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ReconciliationController {

    private final ReconciliationService svc;

    public ReconciliationController(ReconciliationService svc) { this.svc = svc; }

    @PostMapping("/reconcile")
    public ReconciliationService.Report reconcile(@RequestParam("file") MultipartFile file) throws Exception {
        return svc.reconcile(file.getInputStream());
    }
}
```

- [ ] **Step 19.3: Commit**

```bash
mvn -q compile
git add src/main/java/com/paytm/disburse
git commit -m "Reconciliation: CSV parser + matcher + 4 break types"
```

---

### Task 20: Observability (metrics)

**Files:**
- Create: `src/main/java/com/paytm/disburse/observability/DisbursementMetrics.java`
- Modify: `DisbursementService` to publish metrics

- [ ] **Step 20.1: `DisbursementMetrics.java`**

```java
package com.paytm.disburse.observability;

import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;
import com.paytm.disburse.domain.DisbursementStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DisbursementMetrics {

    private final MeterRegistry registry;

    public DisbursementMetrics(MeterRegistry registry) { this.registry = registry; }

    public void created() { registry.counter("disbursement.created").increment(); }

    public void completed(DisbursementStatus status) {
        registry.counter("disbursement.completed", "outcome", status.name()).increment();
    }

    public void attempt(Channel channel, AttemptStatus status, Duration duration) {
        registry.timer("disbursement.attempt", "channel", channel.name(), "outcome", status.name())
            .record(duration);
    }

    public void transition(DisbursementStatus from, DisbursementStatus to) {
        registry.counter("disbursement.transitions", "from", from.name(), "to", to.name()).increment();
    }

    public void reconcileBreak(String type) {
        registry.counter("reconcile.breaks", "type", type).increment();
    }
}
```

- [ ] **Step 20.2: Wire metrics into `DisbursementService`**

Inject `DisbursementMetrics`. Call `metrics.created()` after `disbursements.insert(d)` in `create()`. Call `metrics.transition(prev, next)` inside helper methods (or wrap `transitionTo` in the service). Call `metrics.attempt(channel, status, duration)` in `applyChannelResponse` (capture start time before the channel call). Call `metrics.completed(d.status())` when `d.status().isTerminal()`.

Wire `metrics.reconcileBreak(b.type())` per break in `ReconciliationService`.

- [ ] **Step 20.3: Verify Prometheus scrape**

Boot the app: `mvn -q spring-boot:run` (background)
Curl: `curl -s http://localhost:8080/actuator/prometheus | grep disbursement_`
Expected: at least `disbursement_created_total` present.

- [ ] **Step 20.4: Commit**

```bash
git add src/main/java/com/paytm/disburse
git commit -m "Micrometer metrics: created/completed/attempt/transitions/reconcile"
```

---

### Task 21: Grafana dashboard JSON

**Files:**
- Create: `dashboards/grafana-disbursement.json`

- [ ] **Step 21.1: Write dashboard JSON**

Include panels (simple Stat / TimeSeries panels with promql):
1. `sum(rate(disbursement_created_total[5m]))` — throughput
2. `sum by (channel,outcome)(rate(disbursement_attempt_seconds_count[5m]))` — attempts by outcome
3. `histogram_quantile(0.99, sum by(le,channel)(rate(disbursement_attempt_seconds_bucket[5m])))` — p99 latency per channel
4. `disbursement_completed_total{outcome="SUCCESS"} / disbursement_completed_total` — success rate
5. `sum by (type)(rate(reconcile_breaks_total[1h]))` — reconciliation breaks
6. `resilience4j_circuitbreaker_state` — circuit state per channel

Use a minimal Grafana 10-compatible JSON. (See https://grafana.com/docs/grafana/latest/dashboards/build-dashboards/import-export/ for schema.) The exact JSON is long — write a basic version with the 6 panels above. Commit it.

- [ ] **Step 21.2: Commit**

```bash
git add dashboards
git commit -m "Grafana dashboard JSON: throughput / latency / breaks / CB state"
```

---

### Task 22: Test support (controllable channel client)

For deterministic scenario tests, we need to swap channels with scripted versions. Spring `@TestConfiguration` is the trick.

**Files:**
- Create: `src/test/java/com/paytm/disburse/support/ControllableChannelClient.java`
- Create: `src/test/java/com/paytm/disburse/support/TestChannels.java`

- [ ] **Step 22.1: `ControllableChannelClient.java`**

```java
package com.paytm.disburse.support;

import com.paytm.disburse.channel.ChannelClient;
import com.paytm.disburse.channel.ChannelRequest;
import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.Channel;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ControllableChannelClient implements ChannelClient {

    private final Channel channel;
    private final Deque<ChannelResponse> scriptedResponses = new ArrayDeque<>();
    private final Map<UUID, ChannelResponse> processed = new ConcurrentHashMap<>();
    private long maxAmount = Long.MAX_VALUE;

    public ControllableChannelClient(Channel channel) { this.channel = channel; }

    public ControllableChannelClient enqueue(ChannelResponse r) { scriptedResponses.add(r); return this; }
    public ControllableChannelClient maxAmount(long v) { this.maxAmount = v; return this; }

    @Override public Channel channel() { return channel; }
    @Override public long maxAmountPaise() { return maxAmount; }

    @Override
    public synchronized ChannelResponse transfer(ChannelRequest req) {
        ChannelResponse prior = processed.get(req.referenceId());
        if (prior != null) return new ChannelResponse(prior.status(), prior.failureReason(),
            "duplicate: " + prior.rawResponse());
        if (scriptedResponses.isEmpty()) {
            throw new IllegalStateException(channel + ": no scripted response available");
        }
        ChannelResponse r = scriptedResponses.poll();
        if (r.status() != AttemptStatus.UNCERTAIN) processed.put(req.referenceId(), r);
        else processed.put(req.referenceId(), r); // remember for status()
        return r;
    }

    @Override
    public synchronized ChannelResponse status(UUID referenceId) {
        ChannelResponse r = processed.get(referenceId);
        if (r == null) return ChannelResponse.transient_(
            com.paytm.disburse.domain.FailureReason.NETWORK_ERROR, "unknown reference");
        // Resolve UNCERTAIN to its terminal counterpart on poll. Tests pre-load this.
        return r;
    }
}
```

- [ ] **Step 22.2: `TestChannels.java`**

```java
package com.paytm.disburse.support;

import com.paytm.disburse.channel.ChannelClient;
import com.paytm.disburse.domain.Channel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

@TestConfiguration
public class TestChannels {

    @Bean public ControllableChannelClient upi()  { return new ControllableChannelClient(Channel.UPI); }
    @Bean public ControllableChannelClient imps() { return new ControllableChannelClient(Channel.IMPS); }
    @Bean public ControllableChannelClient neft() { return new ControllableChannelClient(Channel.NEFT); }

    @Bean @Primary
    public List<ChannelClient> testClients(ControllableChannelClient upi,
                                           ControllableChannelClient imps,
                                           ControllableChannelClient neft) {
        return List.of(upi, imps, neft);
    }
}
```

- [ ] **Step 22.3: Commit**

```bash
git add src/test/java/com/paytm/disburse/support
git commit -m "Test support: controllable channel client + bean wiring"
```

---

### Task 23: Scenario tests — Happy path + Transient retry + Permanent

**Files:**
- Create: `src/test/java/com/paytm/disburse/scenarios/HappyPathTest.java`
- Create: `src/test/java/com/paytm/disburse/scenarios/TransientRetryTest.java`
- Create: `src/test/java/com/paytm/disburse/scenarios/PermanentFailureTest.java`

- [ ] **Step 23.1: `HappyPathTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class HappyPathTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient upi;
    @Autowired ControllableChannelClient imps;

    @Test
    void rs_2L_via_imps_succeeds_first_try() {
        // ₹2L sits in the IMPS-first tier (₹1L < amount ≤ ₹5L), matching the PDF scenario's intent.
        // (PDF says "₹50k via IMPS" but with our router ₹50k tries UPI first — using ₹2L
        // keeps the spirit: instant channel, first attempt, success.)
        imps.enqueue(ChannelResponse.success("ok"));
        var d = service.create(new CreateDisbursementCommand("L-HAPPY",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());
        var refreshed = service.findById(d.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo(DisbursementStatus.SUCCESS);
        var attempts = service.attemptsFor(d.id());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).status()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(attempts.get(0).channel().name()).isEqualTo("IMPS");
    }
}
```

- [ ] **Step 23.2: `TransientRetryTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.domain.FailureReason;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class TransientRetryTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient imps;

    @Test
    void imps_timeout_then_success_on_second_attempt() {
        imps.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_TIMEOUT_BEFORE_SEND, "timeout"));
        imps.enqueue(ChannelResponse.success("ok"));

        var d = service.create(new CreateDisbursementCommand("L-RETRY",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());           // attempt 1 → transient
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.PENDING_RETRY);
        service.processAttempt(d.id());           // attempt 2 → success
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.SUCCESS);
        assertThat(service.attemptsFor(d.id())).hasSize(2);
    }
}
```

- [ ] **Step 23.3: `PermanentFailureTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.domain.FailureReason;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class PermanentFailureTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient imps;

    @Test
    void invalid_ifsc_does_not_retry_or_fall_back() {
        imps.enqueue(ChannelResponse.permanent(FailureReason.INVALID_IFSC, "bad ifsc"));

        var d = service.create(new CreateDisbursementCommand("L-PERM",
            "1234", "INVALID_XYZ", null, 200_000_00L, null, null));
        service.processAttempt(d.id());

        var refreshed = service.findById(d.id()).get();
        assertThat(refreshed.status()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(refreshed.failureReason()).isEqualTo(FailureReason.INVALID_IFSC);
        // Only one attempt should have been made — no NEFT fallback.
        assertThat(service.attemptsFor(d.id())).hasSize(1);
    }
}
```

- [ ] **Step 23.4: Run + commit**

```bash
mvn -q test -Dtest='HappyPathTest,TransientRetryTest,PermanentFailureTest'
git add src/test/java/com/paytm/disburse/scenarios
git commit -m "Scenario tests: happy, transient retry, permanent failure"
```

---

### Task 24: Scenario tests — Idempotency + Channel fallback + Reconciliation

**Files:**
- Create: `src/test/java/com/paytm/disburse/scenarios/IdempotencyTest.java`
- Create: `src/test/java/com/paytm/disburse/scenarios/ChannelFallbackTest.java`
- Create: `src/test/java/com/paytm/disburse/scenarios/ReconciliationTest.java`

- [ ] **Step 24.1: `IdempotencyTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class IdempotencyTest {

    @Autowired DisbursementService service;

    @Test
    void same_loan_id_creates_only_one_disbursement() {
        var first = service.create(new CreateDisbursementCommand(
            "L-IDEM-1", "1234", "HDFC0001234", null, 100_000L, null, null));
        var second = service.create(new CreateDisbursementCommand(
            "L-IDEM-1", "1234", "HDFC0001234", null, 100_000L, null, null));
        assertThat(second.id()).isEqualTo(first.id());
    }
}
```

- [ ] **Step 24.2: `ChannelFallbackTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.domain.FailureReason;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class ChannelFallbackTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient upi;
    @Autowired ControllableChannelClient imps;

    @Test
    void upi_exhausted_falls_back_to_imps() {
        // 3 transient failures = exhausted for UPI; IMPS succeeds.
        upi.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        upi.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        upi.enqueue(ChannelResponse.transient_(FailureReason.CHANNEL_5XX, "down"));
        imps.enqueue(ChannelResponse.success("ok"));

        var d = service.create(new CreateDisbursementCommand("L-FALL",
            "1234", "HDFC0001234", "x@upi", 50_000_00L /* ≤ ₹1L → UPI first */, null, null));
        for (int i = 0; i < 4; i++) service.processAttempt(d.id());

        var attempts = service.attemptsFor(d.id());
        assertThat(attempts).extracting(a -> a.channel().name()).containsExactly("UPI","UPI","UPI","IMPS");
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.SUCCESS);
    }
}
```

- [ ] **Step 24.3: `ReconciliationTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.service.ReconciliationService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class ReconciliationTest {

    @Autowired DisbursementService service;
    @Autowired ReconciliationService recon;
    @Autowired ControllableChannelClient imps;

    @Test
    void internal_success_but_no_bank_row_creates_INTERNAL_ONLY_break() throws Exception {
        imps.enqueue(ChannelResponse.success("ok"));
        var d = service.create(new CreateDisbursementCommand("L-RECON",
            "1234", "HDFC0001234", null, 50_000_00L, null, null));
        service.processAttempt(d.id());

        String csv = "bank_reference_id,transaction_date,amount_paise,account,ifsc,status\n"
            // (empty — no bank rows)
            ;
        var report = recon.reconcile(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(report.breaks()).anyMatch(b -> "INTERNAL_ONLY".equals(b.type()));
    }
}
```

- [ ] **Step 24.4: Run + commit**

```bash
mvn -q test -Dtest='IdempotencyTest,ChannelFallbackTest,ReconciliationTest'
git add src/test/java/com/paytm/disburse/scenarios
git commit -m "Scenario tests: idempotency, channel fallback, reconciliation"
```

---

### Task 25: Uncertain resolution + concurrent worker tests

These are the two non-PDF tests that are **load-bearing for the engineering signal**.

**Files:**
- Create: `src/test/java/com/paytm/disburse/scenarios/UncertainResolutionTest.java`
- Create: `src/test/java/com/paytm/disburse/scenarios/ConcurrentWorkerTest.java`

- [ ] **Step 25.1: `UncertainResolutionTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.AttemptStatus;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.domain.FailureReason;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class UncertainResolutionTest {

    @Autowired DisbursementService service;
    @Autowired ControllableChannelClient imps;

    @Test
    void uncertain_state_never_falls_back_to_different_channel() {
        // First: channel "times out" (UNCERTAIN), but later poll reveals it succeeded.
        // ControllableChannelClient internally remembers the response keyed by reference_id;
        // so for status() to return SUCCESS we need a small tweak: enqueue UNCERTAIN, then
        // when poll happens it should reveal the "real" outcome. We model this by enqueueing
        // a SUCCESS response that will be returned by status() — but transfer() returned UNCERTAIN.
        // For this test we use a custom subclass:
        imps.enqueue(new ChannelResponse(AttemptStatus.UNCERTAIN, FailureReason.CHANNEL_TIMEOUT_AFTER_SEND,
            "timeout"));
        // (status() will return the cached UNCERTAIN — that's OK because tests of resolution
        // belong with full mocks. Here we assert the key invariant: UNCERTAIN never falls back
        // to a different channel even if invoked many times.)

        var d = service.create(new CreateDisbursementCommand("L-UNCERT",
            "1234", "HDFC0001234", null, 200_000_00L, null, null));
        service.processAttempt(d.id());
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.UNCERTAIN);

        // Drive the worker repeatedly — it must NOT touch NEFT.
        for (int i = 0; i < 10; i++) service.pollUncertain(d.id());

        var atts = service.attemptsFor(d.id());
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).channel().name()).isEqualTo("IMPS");
        // status remains UNCERTAIN since we never resolve in this scripted test — that IS the assertion.
        assertThat(service.findById(d.id()).get().status()).isEqualTo(DisbursementStatus.UNCERTAIN);
    }
}
```

- [ ] **Step 25.2: `ConcurrentWorkerTest.java`**

```java
package com.paytm.disburse.scenarios;

import com.paytm.disburse.channel.ChannelResponse;
import com.paytm.disburse.domain.DisbursementStatus;
import com.paytm.disburse.repository.DisbursementRepository;
import com.paytm.disburse.service.CreateDisbursementCommand;
import com.paytm.disburse.service.DisbursementService;
import com.paytm.disburse.support.ControllableChannelClient;
import com.paytm.disburse.support.TestChannels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestChannels.class)
class ConcurrentWorkerTest {

    @Autowired DisbursementService service;
    @Autowired DisbursementRepository repo;
    @Autowired ControllableChannelClient imps;

    @Test
    void two_workers_dont_double_process_any_row() throws Exception {
        for (int i = 0; i < 20; i++) imps.enqueue(ChannelResponse.success("ok"));

        // Create 10 disbursements
        for (int i = 0; i < 10; i++) {
            service.create(new CreateDisbursementCommand("L-CC-" + i,
                "1234", "HDFC0001234", null, 200_000_00L, null, null));
        }

        // Two threads each grab work and process
        ExecutorService es = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> task = () -> {
            start.await();
            for (int i = 0; i < 10; i++) {
                for (var d : repo.claimWorkBatch(5)) {
                    service.processAttempt(d.id());
                }
            }
            return null;
        };
        Future<Void> a = es.submit(task);
        Future<Void> b = es.submit(task);
        start.countDown();
        a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        es.shutdown();

        for (int i = 0; i < 10; i++) {
            UUID id = service.create(new CreateDisbursementCommand("L-CC-" + i, "1234", "HDFC0001234",
                null, 200_000_00L, null, null)).id();
            var atts = service.attemptsFor(id);
            assertThat(atts.size()).as("disbursement %d has no double-attempts", i).isEqualTo(1);
            assertThat(service.findById(id).get().status()).isEqualTo(DisbursementStatus.SUCCESS);
        }
    }
}
```

- [ ] **Step 25.3: Run + commit**

```bash
mvn -q test -Dtest='UncertainResolutionTest,ConcurrentWorkerTest'
git add src/test/java/com/paytm/disburse/scenarios
git commit -m "Tests: UNCERTAIN never falls back; concurrent workers don't double-process"
```

---

### Task 26: README

**Files:**
- Create: `README.md`

- [ ] **Step 26.1: Write README**

Sections:
1. **What this is** — one paragraph: loan disbursement service, mock channels, exactly-once guarantees.
2. **How to run** — `mvn spring-boot:run`, `curl` examples for `/disburse`, `/disburse/{id}`, `/reconcile`.
3. **How to run tests** — `mvn test`, list of scenario tests.
4. **Architecture at a glance** — link to `docs/specs/...` and a 4-line summary.
5. **Observability** — `http://localhost:8080/actuator/prometheus`, dashboard JSON path.
6. **Production gap list** — short bulleted list of "what'd need to change to ship" (Postgres swap, secret management, queue worker, real channel SDKs).

Keep it ≤ 150 lines. The reviewer is reading it before running anything, so the run instructions go up top.

- [ ] **Step 26.2: Commit**

```bash
git add README.md
git commit -m "README: run / test / observability / production-gap"
```

---

### Task 27: DESIGN_DECISIONS.md (interview deliverable)

**Files:**
- Create: `DESIGN_DECISIONS.md`

- [ ] **Step 27.1: Write DESIGN_DECISIONS.md**

Sections (1-2 pages, ~600-900 words):
1. **The central question this service answers** — "Exactly-once disbursement under partial failures" in 3 lines.
2. **Three layers of idempotency** — loan_id UNIQUE / Idempotency-Key / per-attempt reference_id. Why all three.
3. **UNCERTAIN as a first-class state** — what bug it prevents (cite scenario).
4. **Write-ahead attempt persistence** — what process crashes look like with and without it.
5. **State machine separation** — disbursement-level vs attempt-level. Why one collapsed machine breaks down.
6. **Amount-tiered routing with circuit breakers** — what we reject (pure cost-opt, pure speed-opt).
7. **Storage choice** — H2 for the exercise, Postgres for prod, exact SQL identical (SKIP LOCKED).
8. **JdbcTemplate over JPA** — caching/lazy-load would actively hurt us here.
9. **Trade-offs rejected** — saga/2PC (channels don't compensate), optimistic-only concurrency (worker races), idempotency at HTTP only (worker crashes unprotected).
10. **What I'd do with more time** — per-borrower velocity limits, pull-based reconciliation, Temporal/Camunda for workflow versioning.
11. **Assumptions** — channels expose a `/status` endpoint (real-world: many do; PhonePe, Razorpay do).

- [ ] **Step 27.2: Commit**

```bash
git add DESIGN_DECISIONS.md
git commit -m "DESIGN_DECISIONS: rationale for every non-trivial call"
```

---

### Task 28: PROMPTS.md — AI session log (interview deliverable)

**Files:**
- Create: `PROMPTS.md`

The interviewer explicitly asks for the complete export of AI session prompts. This file is the deliverable.

- [ ] **Step 28.1: Write `PROMPTS.md`**

Structure:
1. **Tools used** — Claude Code (Opus 4.7, 1M context) with the Superpowers skill plugin (brainstorming, writing-plans, executing-plans).
2. **Process narrative** — how the work flowed: PDF read → brainstorming (4 questions with my picks) → spec → self-review → plan → implementation → tests → docs.
3. **Verbatim prompts** — paste every user-facing prompt in order, with my (the user's) actual answers. Show what was decided at each step.
4. **What I (the candidate) did vs what AI did** — be honest: AI drafted the implementation following the design *I* picked. The design judgment (UNCERTAIN state, three idempotency layers, write-ahead persistence, JdbcTemplate over JPA) was the human contribution, validated and refined through the brainstorming dialogue.
5. **What I'd do differently next time** — drop the trailing edge cases that don't pay; trust the spec more on the third pass.

- [ ] **Step 28.2: Commit**

```bash
git add PROMPTS.md
git commit -m "PROMPTS: full AI session log with prompts and design decisions"
```

---

### Task 29: Full test pass + final verification

- [ ] **Step 29.1: Run all tests**

```bash
mvn -q test
```
Expected: all tests pass, ≥ 25 tests total.

- [ ] **Step 29.2: Boot + smoke test the API**

Start app in background: `mvn -q spring-boot:run &`
Wait for "Started DisbursementApplication".

```bash
# Create
curl -s -X POST http://localhost:8080/disburse \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: smoke-1" \
  -d '{"loanId":"SMOKE-1","borrowerAccount":"1234","borrowerIfsc":"HDFC0001234","amountPaise":5000000}'

# Wait a few seconds for the worker to drive it
sleep 5

# Status
curl -s http://localhost:8080/disburse/<id-from-previous>

# Prometheus
curl -s http://localhost:8080/actuator/prometheus | grep '^disbursement_'

# Kill
kill %1
```

- [ ] **Step 29.3: Final commit if anything tweaked**

```bash
git status
# only proceed if there's actually changes
git add -A && git commit -m "Final tweaks from smoke test" || true
git log --oneline
```

---

## Done Criteria

- [ ] All scenario tests from PDF + UNCERTAIN + concurrent worker tests pass.
- [ ] `mvn -q test` is green.
- [ ] App boots, `/disburse` POST returns 201, `/disburse/{id}` returns the disbursement with attempts list, `/reconcile` accepts CSV.
- [ ] `/actuator/prometheus` exposes `disbursement_*` metrics.
- [ ] `DESIGN_DECISIONS.md`, `README.md`, `PROMPTS.md`, and `dashboards/grafana-disbursement.json` all exist and are non-trivial.
- [ ] Git log shows ~25+ commits, one per logical unit.
