# Requirements and scope

Start here, then read [architecture](02-architecture-and-design.md), [API](03-api-reference.md), [data model](04-data-model.md), [application components and responsibilities](05-services-and-components.md), [execution flows](06-business-flows-and-execution.md), and [local setup / Postman testing](07-local-setup-and-manual-testing.md).

## Business context and vocabulary

The [original assignment](../README.md#the-domain) asks for one studio's membership purchase API with seeded plans and in-memory storage. A caller discovers plans, purchases a membership for a member name, and retrieves the purchase. The payment provider is an outbound collaborator. The reviewer/operator can additionally use the implemented local fault harness and inspect recovery logs.

| Term | Meaning |
| --- | --- |
| Plan | Seeded product, price in integer cents, active flag, and optional class credits / finite capacity |
| Purchase | Successful membership purchase with a snapshot of the selected plan |
| Unlimited membership | Plan category subject to one active/pending membership per normalized member name |
| Class pack | Plan category with a credit quantity; multiple purchases are permitted |
| Reservation | Capacity held while payment is unresolved |
| Saga operation | Implemented extension: persisted progress across multiple local transactions and payment |
| Compensation | Releasing held resources after a definitive failure |
| Recovery | Resuming or safely abandoning incomplete operations |
| Client idempotency | One logical purchase per client key and canonical request |
| Provider idempotency | Stable key used to reconcile/retry the external charge; requires provider support |

## Functional requirements

**Core** means requested by the original assignment. **Extension** means later agreed work, implemented beyond that brief. Test names below refer to classes in [the integration source set](../src/integrationTest/java/com/glofox/memberships/).

| ID | Scope | Testable requirement | Evidence |
| --- | --- | --- | --- |
| FR-01 | Core | List all four supplied plans, including inactive Legacy Plan, with applicable price/credits/capacity | `ReadApiTests`, `PlanSeedingTests` |
| FR-02 | Core | Accept member name, plan ID and calendar start date; reject blank names, malformed/missing fields and unknown/inactive plans | `PurchaseApiTests` |
| FR-03 | Core | Charge through the supplied gateway port and return 201 with an identified purchase and amount on success | `PurchaseApiTests` |
| FR-04 | Core | Retrieve a successful purchase by ID; return a not-found error for an unknown ID | `ReadApiTests` |
| FR-05 | Core | Never sell more than the finite plan's available slots, including simultaneous requests | `PurchaseConcurrencyTests` |
| FR-06 | Core | Prevent a second active unlimited membership for the same member while permitting multiple packs | `PurchaseApiTests`, `PurchaseConcurrencyTests` |
| FR-07 | Core | A definitive payment failure leaves no successful purchase and restores held capacity | `PaymentFailureApiTests` |
| FR-08 | Extension | Require a client idempotency key; replay same semantic request, reject mismatch and concurrent in-progress duplicates | `IdempotencyApiTests` |
| FR-09 | Extension | List purchase pages with bounded pagination | `ReadApiTests` |
| FR-10 | Extension | Persist orchestration progress; retain resources on ambiguous payment; compensate definitive failure; finalize confirmed success without recharging | `PurchaseSagaRecoveryTests` |
| FR-11 | Extension | Create new in-progress idempotency and initial saga atomically; terminalize idempotency only when safe | `PurchaseInitializationAtomicityTests`, `PurchaseSagaRecoveryTests` |
| FR-12 | Extension | Automatically recover eligible operations with a disableable cron, scheduler lock and independent per-operation leases | `PurchaseRecoverySchedulerTests`, `RecoveryClaimTests` |
| FR-13 | Extension | Provide local, configuration-gated payment/inventory/activation fault simulation | `TestHarnessEnabledTests` |
| FR-14 | Extension | Return correlation IDs and consistent errors; provide health/readiness/liveness checks | `CorrelationIdApiTests`, `ActuatorHealthApiTests` |
| FR-15 | Extension | Retry eligible optimistic conflicts in fresh transactions; cache static plan metadata while reading mutable purchases/inventory directly | `OptimisticLockRetryPolicyTests`, `PurchaseReadConsistencyTests` |

Plan creation/editing, a member registry, credit redemption and membership cancellation are not required or implemented.

## Integrity and non-functional constraints

| ID | Constraint / invariant | Classification |
| --- | --- | --- |
| INV-01 | Monetary amounts use integer cents, never floating point | Core requirement |
| INV-02 | Dates are calendar dates; a purchase cannot use an inactive plan | Core requirement |
| INV-03 | Finite available capacity never becomes negative or oversold | Core requirement |
| INV-04 | A member may have only one active unlimited membership; pending claims also block duplicates | Core plus implemented extension for in-flight work |
| INV-05 | A successful purchase is created only after payment success is established | Core requirement; persisted outcome strengthens recovery |
| INV-06 | Definitive failure requires resource release; an ambiguous result must not be mistaken for a decline | Core failure rule plus agreed recovery extension |
| INV-07 | Repeating a key and the same canonical request must not create another logical purchase | Extension |
| INV-08 | A recoverable saga keeps idempotency in progress; failed records cannot arbitrarily become completed | Extension |
| NFR-01 | Concurrency protection must work between instances sharing one database | Agreed engineering constraint; only H2 verification is present |
| NFR-02 | Payment calls occur outside local database transactions | Implemented engineering constraint |
| NFR-03 | Recovery/retry work is bounded and failure is observable | Extension; no numeric service SLA is specified |
| NFR-04 | Code is navigable and independently testable; unit and integration suites are separate | Agreed maintainability constraint |

No throughput, availability, response-time SLA, multi-region topology or production capacity target is specified. The README's exercise timebox is not a runtime SLA.

## Assumptions, scope and exclusions

- **Assumption:** one studio; a trimmed, lowercased member name identifies membership uniqueness. There is no member ID or identity service.
- **Assumption / limitation:** `ACTIVE` persists until changed externally; there is no expiry calculation, cancellation API, or calendar-based unlimited eligibility. Pack credits are stored but never redeemed.
- **Implementation choice:** H2/JPA replaces the skeleton's Java in-memory store while remaining an in-memory demo database. Data is lost when the JVM exits.
- **Implemented extension:** snapshots, client/provider keys, persisted saga, local caching, observability and ShedLock scheduling. These are not original core requirements.
- **Demo only:** local harness/admin controls are unauthenticated and intended for trusted local use. A test ledger is not a real payment provider.
- **Known limitations:** the static catalogue cache is local; default independent H2 processes do not share locks/data or ledger records; unresolved manual-review operations have no public re-drive endpoint.
- **Future enhancement:** a real payment adapter with provider idempotency/status reconciliation, production database migrations and verification, recovery metrics/alerts and operational controls. See [design limitations](02-architecture-and-design.md#limitations-and-review-caveats).
