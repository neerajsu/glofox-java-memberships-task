# 08 — Design Decisions and Trade-offs

## Purpose

This document explains **why** the Glofox membership-purchase application was designed the way it was. It records the principal alternatives considered and the trade-offs behind the selected approach. It is not a class inventory, defect log, or implementation walkthrough.

## Constraints and assumptions

- The assignment should run locally with minimal setup, **without Docker or separately deployed infrastructure** such as Kafka, Redis, or a workflow engine.
- The assignment provides an `AlwaysSucceedingPaymentGateway`. Failure scenarios still need to be reproducible and testable.
- The solution supports unlimited memberships and finite class packs, with correct behavior under concurrent purchases.
- Purchase requests need durable idempotency and recovery from interrupted operations.
- A synchronous HTTP response is desirable when the purchase can finish promptly; interrupted or uncertain work may finish later through recovery.
- H2 is used for local demonstration. Production deployment and PostgreSQL validation are outside the delivered scope.
- Favor clear, production-oriented boundaries without building infrastructure the assignment does not require.

The alternatives below are context-specific; the chosen option is not claimed to be universally best.

---

## Architecture and orchestration

### ADR-01 — Modular monolith

**Alternatives**

| Option | Pros | Cons |
|---|---|---|
| Microservices | Independent deployment and scaling | Network failures, operational overhead, distributed consistency |
| Modular monolith | One deployment, local transactions, clear logical boundaries | Components cannot be deployed independently |

**Decision:** Use one Spring Boot application with separate logical areas for plans, memberships, inventory, payment, and recovery.

**Why:** It meets the local-run requirement while keeping responsibilities clear. These are **logical components**, not separately deployed services.

### ADR-02 — Persisted synchronous saga

| Option | Pros | Cons |
|---|---|---|
| Single transaction around everything | Straightforward rollback model | Cannot atomically include an external payment provider; holds locks too long |
| Message-driven saga | Loose coupling, asynchronous processing | Broker, messaging contracts, more operational complexity |
| Persisted synchronous saga | Immediate result when possible; durable progress and recovery | Explicit state transitions, compensation and recovery required |

**Decision:** Coordinate purchase steps synchronously, persisting saga state between important boundaries.

**Why:** A purchase can usually finish in one request, but the system can resume if execution is interrupted.

### ADR-03 — Fluent purchase orchestration

| Option | Pros | Cons |
|---|---|---|
| Large procedural orchestration method | Few abstractions | Harder to scan as the workflow grows |
| Fluent, step-oriented flow | Business order is immediately visible | Requires a small flow abstraction |
| General-purpose workflow engine | Rich workflow capabilities | Too much infrastructure and complexity |

**Decision:** Use a fluent sequence: reserve resources → prepare payment → charge → finalize.

**Why:** It makes the main business process easy to review while keeping step implementation separate.

### ADR-04 — Reserve resources before payment

| Option | Pros | Cons |
|---|---|---|
| Charge first | No reservation for declined charges | May charge for an unavailable membership; refund/void needed |
| Reserve first | Check eligibility and capacity before charging | Reservations must be compensated after definitive failures |

**Decision:** Validate and reserve before invoking payment.

**Why:** Avoid unnecessary refunds when inventory or membership eligibility is unavailable.

### ADR-05 — Short local transactions

| Option | Pros | Cons |
|---|---|---|
| Long database transaction | Simpler apparent atomicity | Locks span provider latency; cannot roll back an external charge |
| Distributed two-phase commit | Coordinated commits where supported | Payment gateway does not participate; operationally heavy |
| Short independent transactions | Durable progress; shorter lock duration | Requires recovery between committed steps |

**Decision:** Persist each critical saga stage in a short local transaction; call the payment gateway **outside** a database transaction. Finalization is transactional.

**Why:** Protect database consistency without pretending the external charge is part of a local commit.

---

## Consistency and concurrency

### ADR-06 — Atomic finite-inventory reservation

| Option | Pros | Cons |
|---|---|---|
| In-process synchronization | Easy in one JVM | Does not protect multiple instances |
| Pessimistic row locks | Serializes competing updates | Blocking and potential contention |
| Atomic conditional database update | Prevents capacity going below zero; short operation | Database-specific care and tests required |

**Decision:** Reserve finite inventory through a database-enforced conditional update.

**Why:** The database, rather than an application-instance lock, protects the last available slot.

### ADR-07 — Database-enforced unlimited-membership uniqueness

| Option | Pros | Cons |
|---|---|---|
| Read-then-check in application | Simple | Two concurrent requests can both pass the check |
| Distributed application lock | Can serialize requests | Extra infrastructure and lease complexity |
| Unique database claim | Safe across instances | Conflicts must be translated into business outcomes |

**Decision:** Use persisted membership claims with database uniqueness enforcement.

**Why:** Membership eligibility must remain correct under concurrent requests.

### ADR-08 — Durable API idempotency

| Option | Pros | Cons |
|---|---|---|
| No idempotency | Minimal code | Client retries can duplicate purchases |
| In-memory key cache | Fast | Lost on restart; not shared across instances |
| Persisted idempotency operation | Survives restart; supports replay and recovery | Additional state lifecycle |

**Decision:** Require a purchase idempotency key and persist the operation together with saga initialization.

**Why:** A repeated request should refer to the same logical purchase, not start another charge.

### ADR-09 — Optimistic locking with bounded retry

| Option | Pros | Cons |
|---|---|---|
| Pessimistic locking by default | Prevents concurrent writes while lock is held | Blocking, contention, deadlock risk |
| Optimistic locking without retry | Low normal-case overhead | Transient conflicts fail immediately |
| Optimistic locking with retry | Efficient when conflicts are uncommon; resolves short races | Extra database work when contention occurs |

**Decision:** Use optimistic locking where appropriate and retry conflicts a bounded number of times in **fresh transactions**.

**Why:** For the expected workload, ordinary row conflicts should be relatively infrequent; paying for a short retry is preferable to locking every update. This is a workload assumption, not a guarantee. **Finite inventory is independently protected by atomic conditional updates.**

---

## Payment and failure handling

### ADR-10 — Specified gateway plus deterministic fault injection

| Option | Pros | Cons |
|---|---|---|
| Random failures inside the supplied gateway | Little setup | Unpredictable and hard to reproduce |
| Multiple failing gateway implementations | Explicit scenarios | More implementations and wiring |
| `@FaultInjectable` AOP and configurable test harness | Repeatable failure scenarios without changing normal gateway behavior | Additional aspect and harness code |

**Decision:** Retain the assignment's `AlwaysSucceedingPaymentGateway` behind `PaymentGateway`; use `@FaultInjectable` and a configurable test harness to simulate declines, outages and ambiguous outcomes.

**Why:** The normal gateway honors the assignment contract, while the full purchase path can be tested deterministically. The gateway interface also isolates future provider integration.

### ADR-11 — Distinct payment failure outcomes

| Option | Pros | Cons |
|---|---|---|
| One generic payment error | Simple | Cannot distinguish safe retry, compensation and reconciliation |
| Decline / transient / permanent / unknown classifications | Correct state-specific handling | More explicit outcome paths |

**Decision:** Classify provider outcomes and return distinct machine-readable API errors.

**Why:** A decline is definitive, an outage may be retryable, and an **unknown** result may already have charged the customer. Unknown outcomes require reconciliation rather than immediate compensation.

### ADR-12 — Bounded provider retries

| Option | Pros | Cons |
|---|---|---|
| No retries | Lowest latency | Brief outages become purchase failures |
| Unbounded retries | May eventually succeed | Unbounded latency and resource use |
| Bounded backoff with jitter | Handles short disruptions with a predictable budget | Longer outages still fail or require recovery |

**Decision:** Retry only transient provider failures, with up to **three total attempts** and short bounded backoff with jitter. Reuse the same provider idempotency key.

**Why:** Balance resilience with request latency and avoid creating a second logical charge.

### ADR-13 — Outcome-aware compensation

| Option | Pros | Cons |
|---|---|---|
| Compensate every payment error | Easy cleanup | Unsafe if the charge actually succeeded |
| Never compensate automatically | Avoids premature release | Leaves resources reserved after definitive failures |
| Compensate definitive failures; reconcile unknown outcomes | Matches financial uncertainty | More recovery states |

**Decision:** Release reservations and claims for definitive failures; retain and reconcile ambiguous payments.

**Why:** Do not undo a purchase simply because the application cannot determine whether the customer was charged.

### ADR-14 — Payment success before finalization failure

| Option | Pros | Cons |
|---|---|---|
| Refund immediately | Ends financial exposure | Unnecessary reversal for a recoverable database error |
| Restart entire purchase | Conceptually simple | Risks repeating completed operations |
| Persist payment success and retry finalization | Avoids recharging and unnecessary refunds | Requires durable recovery and monitoring |

**Decision:** Once payment success is durably recorded, recover **finalization only**, without charging again.

**Why:** A temporary activation failure should not trigger another payment or automatic refund. Permanent failures need operational attention.

---

## Recovery and infrastructure

### ADR-15 — Database-driven saga recovery

| Option | Pros | Cons |
|---|---|---|
| No background recovery | Minimal implementation | Interrupted purchases can remain unfinished |
| Broker-driven recovery | Event-driven, scalable | Additional messaging infrastructure |
| Scheduled database recovery | Uses existing durable state; simple local setup | Polling, claims and retries must be managed |

**Decision:** Scan and resume eligible incomplete sagas using persisted state.

**Why:** The database already stores what recovery needs; a broker is not necessary for this assignment.

### ADR-16 — ShedLock plus per-saga recovery claims

| Option | Pros | Cons |
|---|---|---|
| One scheduler with no coordination | Simple | Duplicate work across instances |
| External distributed lock service | Flexible | Additional infrastructure |
| JDBC ShedLock plus per-saga claims | Uses existing database; coordinates batches and individual work | Leases and execution bounds require care |

**Decision:** Use ShedLock to coordinate scheduled runs and database claims to coordinate recovery of individual sagas.

**Why:** Scheduler-level exclusivity and per-operation ownership solve different problems.

### ADR-17 — H2 for local execution; PostgreSQL as production target

| Option | Pros | Cons |
|---|---|---|
| Require PostgreSQL locally | Realistic SQL and locking | External setup for reviewer |
| Embedded H2 | Starts with the application | Not identical to PostgreSQL concurrency behavior |
| H2 demo with PostgreSQL-oriented design | Easy evaluation; keeps production path open | PostgreSQL still needs its own validation |

**Decision:** Use Spring Data JPA and embedded H2 for the delivered application, with a design intended to support PostgreSQL.

**Why:** Meet the no-external-infrastructure constraint. **PostgreSQL compatibility and production readiness are not claimed as fully verified.**

### ADR-18 — No Kafka, ZooKeeper, Outbox or external workflow engine

| Option | Pros | Cons |
|---|---|---|
| Kafka with Outbox/Inbox | Durable asynchronous delivery and integration | Broker, consumers, schema and operational work |
| External workflow engine | Managed orchestration features | Separate service and deployment complexity |
| Synchronous saga plus database recovery | Minimal local dependencies; adequate for current scope | More direct coupling and custom recovery logic |

**Decision:** Do not add messaging or workflow infrastructure without a requirement that needs it.

**Why:** The assignment should run locally without Docker or separately provisioned services. This is a **scope choice**, not a claim that Kafka is unsuitable for larger systems. Modern Kafka also does **not** require ZooKeeper; it can use KRaft.

### ADR-19 — Selective caching

| Option | Pros | Cons |
|---|---|---|
| Cache all read models | Lower database reads | Stale inventory or purchase state |
| No caching | Simple consistency | Repeated reads of stable plan details |
| Cache catalogue only | Useful optimization with fewer correctness risks | Need clear cache boundaries |

**Decision:** Cache relatively stable plan-catalogue information; read consistency-sensitive inventory and purchase state from durable storage.

**Why:** A stale availability or purchase result can affect correctness, unlike a temporarily stale catalogue description.

---

## API and scope

### ADR-20 — Structured API errors

| Option | Pros | Cons |
|---|---|---|
| Plain-text errors | Simple | Clients must parse messages |
| Structured `code`, `message`, `correlationId` | Stable client handling and traceability | Centralized error mapping required |

**Decision:** Return a consistent error body with machine-readable codes and a correlation ID.

**Why:** Clients can distinguish payment decline from gateway outage and support teams can trace failures.

### ADR-21 — Internal compensation, not a public cancellation API

| Option | Pros | Cons |
|---|---|---|
| Add purchase cancellation | More user control | Needs state-specific race, void/refund and authorization rules |
| Implement failure compensation only | Focuses on required reliability behavior | No customer-initiated cancellation |

**Decision:** Keep compensation internal to failed purchase handling; do not expose `POST /v1/memberships/purchases/{id}/cancel` within the assignment scope.

**Why:** Cancelling an unfinished purchase and cancelling an active membership are separate business features requiring explicit requirements.

---

## Summary: the decisions that matter most

1. **Modular monolith:** clear boundaries without deployment overhead.
2. **Persisted synchronous saga:** immediate completion when possible, recovery when interrupted.
3. **Reserve before charge:** reduce unnecessary refunds.
4. **Short transactions:** never hold database locks across a provider call.
5. **Database-enforced concurrency:** atomic inventory updates, unique claims and durable idempotency.
6. **Deterministic payment simulation:** honor `AlwaysSucceedingPaymentGateway` while testing failures through `@FaultInjectable`.
7. **Outcome-aware payment handling:** distinguish definitive failure from uncertain charge.
8. **Recovery after confirmed payment:** finish activation without charging again.
9. **No external infrastructure:** meet local-run scope without sacrificing durable state.

For detailed component responsibilities, runtime sequences, or database structures, consult the corresponding architecture, components, execution-flow and data-model documents. This file intentionally records **decisions**, not every implementation detail.
