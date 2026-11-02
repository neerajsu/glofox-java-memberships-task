# Local setup and manual testing

Contents: [prerequisites](#prerequisites) · [profiles](#configuration-and-profiles) · [startup](#start-the-application) · [automated tests](#automated-tests) · [Swagger/OpenAPI](#swaggeropenapi-reference) · [Postman](#import-and-run-in-postman) · [scenarios](#scenario-matrix) · [limits](#scenarios-requiring-automated-tests) · [troubleshooting](#troubleshooting-and-safety).

## Prerequisites

- JDK **21 or later**, with `JAVA_HOME`/PATH selecting it. The build compiles with `options.release=21`; it does not configure an automatically downloaded Java toolchain.
- Use the supplied Gradle wrapper (`./gradlew`, or `gradlew.bat` on Windows). It declares Gradle 9.5.1. Dependency/distribution downloads require network access on first use; no separate Gradle installation is required.
- No external database installation, container, Redis, payment credentials or authentication token is required. The configured database is in-memory H2.
- Postman Desktop, or Postman Web with the **Desktop Agent** selected/running on the machine that can reach the service. A cloud agent cannot reach your machine's localhost. See [official agent documentation](https://learning.postman.com/v11/docs/getting-started/basics/about-postman-agent). If the service runs in WSL/container/remote development, use an address reachable from the agent.

## Configuration and profiles

| Setting/profile | Actual behavior |
| --- | --- |
| Default / no active profile | Port 8080, in-memory H2, harness disabled, recovery scheduler enabled |
| `local` | Same database/port, enables `app.test-harness.enabled=true`; required for collection setup/fault scenarios |
| `test` (integration resources only) | Random H2 database, schema create-drop, harness and scheduler disabled, zero recovery age for deterministic tests |
| Database | `jdbc:h2:mem:memberships;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=1000`; no persistent data across JVM exit |
| Schema | Hibernate `update` for normal/local use; `schema.sql` creates ShedLock table; idempotent seed runner inserts missing plan IDs and finite inventory |
| Payment | Default `AlwaysSucceedingPaymentGateway`; local AOP settings inject scenarios into that adapter. No real provider selection/credentials are configured |
| Read caching | Static plan catalogue only; inventory and purchase-by-ID/page reads query the database |
| Database waits | Hikari checkout 1000ms; JDBC query 1s; JPA query hint 1000ms; retry transaction timeout 1s; default H2 lock wait 1000ms |

Recovery properties (`app.saga.recovery.*`):

| Property | Default / validation |
| --- | --- |
| `enabled` | true; false removes scheduled execution, not programmatic recovery |
| `cron` | `*/15 * * * * *`, six fields, UTC |
| `batch-size` | 10; 1–10 |
| `minimum-age` | PT90S; enabled scheduling requires at least 90s inactivity |
| `lock-at-most-for` | PT5M; must cover batch timeout + provider timeout + one-minute finishing margin |
| `provider-timeout` | PT5S; positive and no more than five seconds, recovery calls only |
| `batch-timeout` | PT2M; positive and no more than two minutes; stops admitting another saga |

Per-saga lease is three minutes in code. `lockAtLeastFor` is omitted. The age is deliberately greater than the originally proposed 30 seconds because synchronous simulation can delay each payment attempt. See [recovery execution](06-business-flows-and-execution.md#recovery-worker-and-locks) and [lock/timing rationale and limitations](02-architecture-and-design.md#scheduler-and-lease-coordination). Internal settings routes are versioned under `/internal/v1/admin/test-harness/settings`; the old unversioned routes are not mapped. Re-import the updated collection if you have an earlier copy. Do not disable scheduling if you want to demonstrate the collection's recovery completion.

## Start the application

From the repository root:

```bash
java -version
./gradlew --version
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows equivalent:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

Default (no demo/admin harness): `./gradlew bootRun`. Environment-based alternative for local: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` on a POSIX shell. No additional environment variables are mandatory.

Base URL is `http://localhost:8080`. Look for successful application startup and `event=application.seed.plans.completed`; use the collection's **Overall Health** request to confirm `UP`, then **Read Harness Settings** to confirm the local profile. H2/schema/seed rows initialize automatically. No H2 web console is configured.

On a new JVM the finite promo has **five** slots; other plans have no finite-capacity row. Inactive Legacy Plan is intentionally visible. There is no create-plan or inventory-reset request. To restore the entire local demo dataset, stop the application JVM and restart it with the same command. This also clears settings, purchases, claims, recovery work and caches. Changing settings alone does not reset business data.

Optional overrides are ordinary Boot command-line properties, for example:

```bash
./gradlew bootRun --args='--spring.profiles.active=local --server.port=8081'
./gradlew bootRun --args='--spring.profiles.active=local --app.saga.recovery.enabled=false'
```

Adjust Postman's `baseUrl` for a changed port. The second command deliberately prevents automatic recovery; use it only for scenarios that do not require it.

## Automated tests

```bash
./gradlew test
./gradlew integrationTest
./gradlew check
```

Unit tests are in `src/test/java`, in the same Java package as their production subject, with mocked collaborators where useful. Integration tests and their profile resources are in `src/integrationTest/java` and `src/integrationTest/resources`; they exercise HTTP, real H2 transactions, concurrency, caches, recovery and ShedLock. `check` runs both suites. The latest verification result is recorded in [SUBMISSION.md](../SUBMISSION.md#tests). Payment-ledger tests cover normal-mode replay, amount mismatch, forced concurrent insert collisions, H2 uniqueness and survival of outer transaction rollback. Purchase-read tests cover direct committed changes and a delayed read that completes after finalization.

Targeted commands, if investigating a scenario:

```bash
./gradlew integrationTest --tests '*PurchaseConcurrencyTests'
./gradlew integrationTest --tests '*PurchaseSagaRecoveryTests'
./gradlew integrationTest --tests '*PurchaseRecoverySchedulerTests'
```

## Swagger/OpenAPI reference

The [Swagger/OpenAPI specification](openapi.yaml) documents all implemented public, internal harness, and health endpoints in OpenAPI 3.0.3 format. It includes required headers, request/response schemas, error codes, examples for pack/unlimited/promo purchases, and all eight harness setting keys with their allowed values and defaults. Numeric parameters include inclusive bounds; names, UUIDs, dates and headers describe their validation rules rather than listing unbounded values.

Open [Swagger Editor](https://editor.swagger.io/), choose **File → Import file**, and select `docs/openapi.yaml`. You can also import this file into an OpenAPI-compatible viewer or Postman as an API definition. The application does not host Swagger UI or `/v3/api-docs`; this is a static reference. The supplied Postman collection below remains the scripted scenario harness.

For a harness PUT, select the path `key` and its matching request-body example. `value` is a **JSON string**, even for numbers and booleans (for example, `{"value":"2","enabled":true}`). The operation description lists every key/value pairing; the schema alternatives expose finite choices as enums and describe numeric string ranges. OpenAPI cannot enforce the dependency between a path key and a body value; the server validates that pairing. Unset or disabled overrides use the defaults. Harness endpoints require the `local` profile or `app.test-harness.enabled=true`.

The specification uses `http://localhost:8080` as its server URL; change it in your client for another host/port. Browser-based API execution also depends on browser localhost/CORS rules; use Postman Desktop/Desktop Agent or the [curl examples](03-api-reference.md#curl-examples) to call the local service.

## Import and run in Postman

Deliverables:

- [Glofox-Memberships.postman_collection.json](../postman/Glofox-Memberships.postman_collection.json) — Collection **v2.1**, eight folders / 48 requests with descriptions, pre-request scripts and assertions.
- [Glofox-Memberships-Local.postman_environment.json](../postman/Glofox-Memberships-Local.postman_environment.json) — optional local environment containing only `baseUrl`, default `http://localhost:8080`; no secrets.

1. Start a **fresh JVM in the local profile**, leaving scheduling enabled. A fresh database matters for the inventory folder.
2. Open Postman Desktop, or select Desktop Agent in the web application.
3. Choose **Import** and import the collection JSON above.
4. Import the environment JSON, select **Glofox Memberships Local**, and verify `baseUrl`. Alternatively use the collection's `baseUrl` variable without an environment. Avoid another selected environment shadowing it.
5. Run **00 - Health and Setup** first. It checks health and restores the harness runtime switch/payment SUCCESS/zero delay/normal inventory and activation settings. **Read Harness Settings** shows descriptions with every allowed value or inclusive numeric range to help construct manual PUT requests. It does **not** recreate inventory or erase purchases.
6. Run **01 - Plans / List Plans**. It discovers supplied plan IDs by their returned names, storing `classPackPlanId`, `limitedPlanId`, `unlimitedPlanId`, `inactivePlanId`. Do not fill a fictional member ID; there is no member entity/API.
7. Use **Runner** to execute folders **02–06** in order, or run the full collection with one iteration. Do not enable parallel collection execution for this sequential dataset. Every genuinely new purchase generates a fresh GUID key and synthetic member name; replay scenarios deliberately restore saved keys/payloads.
8. The final payment scenario stores `recoveryKey`/`recoveryPayload` after a simulated timeout-after-processing and restores payment SUCCESS. **07 - Saga Recovery** may initially return 409 pending. Keep the application running; allow at least 90 seconds of inactivity plus the next 15-second tick, then rerun folder 07 with those same saved variables. Recovery errors/backoff can take longer; there is no completion SLA.
9. Inspect each request's response, **Test Results**, and Runner summary. Assertions check statuses, error codes, identities and key snapshot fields. Postman Console only prints the static pending-recovery guidance, not keys/member data.
10. A pending recovery check is explicitly accepted as 409 `IDEMPOTENCY_IN_PROGRESS`; that is **not a passing demonstration of recovery completion**. The **Get Recovered Purchase** request is skipped until the check receives 201 and stores `recoveredPurchaseId`. After completion, rerunning folder 07 must replay the same purchase ID and GET it successfully.
11. To rerun the entire inventory scenario, stop/restart the JVM and rerun setup/discovery. For a same-key replay test, preserve its scenario variables while the same application data still exists. After database reset, those old keys no longer represent stored operations.

Collection state is stored in collection variables; the optional environment should contain only `baseUrl`. `purchaseId` is extracted from successful class-pack responses; folder 02 stores its unlimited response separately in `unlimitedPurchaseId`. Idempotency tests use `idempotencyKey`, `idempotencyPayload`, `idempotentPurchaseId`; recovery uses its separate saved key/payload/ID. `currentKey`/`currentPayload` are request working values overwritten by scripts. Most generated values must not be manually populated; run the preceding scenario instead.

The collection format is validated against the [official v2.1 JSON Schema](https://schema.postman.com/collection/json/v2.1.0/draft-07/collection.json), and scripts are syntax-checked. This is not a claim that the collection was imported or executed in a Postman GUI. See validation notes in the completion report for exactly what was run.

## Scenario matrix

| Scenario | Actual folder / request | Preconditions | Expected result |
| --- | --- | --- | --- |
| Health/profile | 00 / Overall Health; Read Harness Settings | Running local profile | 200 UP; 200 settings array |
| Plan discovery | 01 / List Plans | Fresh or existing app | Four plans, including inactive Legacy; IDs saved |
| Successful pack | 02 / Purchase Class Pack; Get Created Purchase | Discovered pack ID; SUCCESS behavior | 201 active snapshot then 200 same ID |
| Successful unlimited | 02 / Purchase Unlimited Membership; Get Unlimited Purchase | Discovered unlimited ID; fresh member/key; SUCCESS behavior | 201 unlimited snapshot with null credits, then 200 same ID |
| Sequential capacity exhaustion | 03 / Consume Promo Slot 1–5; Reject Exhausted Promo; Verify Zero Remaining Slots | Fresh JVM with exactly five available promo slots; run once in order | Five 201s; sixth request 409 `INVENTORY_EXHAUSTED`; slots remain zero |
| Unlimited uniqueness | 04 / Purchase Unlimited Membership; Reject Duplicate Unlimited Claim | Fresh synthetic member; second key differs | First 201, normalized-name duplicate 409 `ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS` |
| Idempotent replay | 05 / Create Idempotent Purchase; Same Key - Same Payload | Initial successful request | 201 on replay with same original ID |
| Key mismatch | 05 / Same Key - Different Payload | Original key; only startDate changes | 409 `IDEMPOTENCY_KEY_REUSED` |
| Decline and cleanup | 06 / Definitive Payment Decline; Purchase After Decline Releases Claim | DECLINED → SUCCESS as ordered | 402 `PAYMENT_DECLINED`; new key for same member later succeeds 201 |
| Permanent technical error | 06 / Permanent Provider Error | PERMANENT_ERROR | 502 `PAYMENT_PROVIDER_ERROR`; no successful purchase |
| Transient retries | 06 / Transient Failures Then Success | Enabled counter=2, TRANSIENT_ERROR | 201 after two injected transient failures; one purchase |
| Ambiguous payment | 06 / Prepare Ambiguous Payment; Immediate In-Progress Retry | TIMEOUT_AFTER_PROCESSING, fresh member/key | 503 `PAYMENT_OUTCOME_UNKNOWN`, immediate same-key 409 pending; ledger/claim retained |
| Scheduled recovery verification | 07 / Check Scheduled Recovery Result; Get Recovered Purchase | Previous ambiguous operation; SUCCESS restored; scheduler enabled and age/due gates elapsed | Initially may be 409 pending; after recovery, 201 same operation then GET 200 |

The collection does not prove database row counts or financial effects via hidden endpoints. For example, successful re-purchase after decline is HTTP evidence of claim cleanup; integration tests verify rows/reservations and charge counts directly.

## Scenarios requiring automated tests

Ordinary sequential Runner requests cannot prove simultaneous last-slot or unlimited-claim safety. No public API exposes forced version conflicts, process crash hooks, manual recovery trigger or saga re-drive. Use these actual tests:

| Behavior | Evidence |
| --- | --- |
| Simultaneous last-slot / duplicate unlimited claims | [PurchaseConcurrencyTests](../src/integrationTest/java/com/glofox/memberships/concurrency/PurchaseConcurrencyTests.java) |
| Concurrent same-key initialization / one saga | [IdempotencyApiTests](../src/integrationTest/java/com/glofox/memberships/idempotency/IdempotencyApiTests.java) |
| Initialization write failure rolls back both records; same key can retry | [PurchaseInitializationAtomicityTests](../src/integrationTest/java/com/glofox/memberships/membership/service/purchase/PurchaseInitializationAtomicityTests.java) |
| Provider success before local outcome / compensation failure / interrupted workflow | [PurchaseSagaRecoveryTests](../src/integrationTest/java/com/glofox/memberships/membership/saga/recovery/PurchaseSagaRecoveryTests.java) |
| Real version conflicts and fresh retry transactions | [OptimisticLockRetryPolicyTests](../src/integrationTest/java/com/glofox/memberships/common/retry/OptimisticLockRetryPolicyTests.java) |
| GET during payment, late read after commit, and failed finalization visibility | [PurchaseReadConsistencyTests](../src/integrationTest/java/com/glofox/memberships/membership/api/PurchaseReadConsistencyTests.java) |
| Default payment replay, amount validation, concurrent inserts and outer rollback | [AlwaysSucceedingPaymentGatewayTests](../src/integrationTest/java/com/glofox/memberships/payment/AlwaysSucceedingPaymentGatewayTests.java) |
| Per-saga age gate, contention and abandoned lease reclaim | [RecoveryClaimTests](../src/integrationTest/java/com/glofox/memberships/membership/saga/recovery/RecoveryClaimTests.java) |
| Competing scheduler calls using JDBC/DB time | [PurchaseRecoverySchedulerTests](../src/integrationTest/java/com/glofox/memberships/membership/saga/recovery/scheduling/PurchaseRecoverySchedulerTests.java) |
| Harness fault modes and confirmed-payment activation failure | [TestHarnessEnabledTests](../src/integrationTest/java/com/glofox/memberships/testharness/TestHarnessEnabledTests.java) |

These tests use controlled calls, database state and latches rather than ordinary manual timing. Default H2 does not survive JVM termination; restarting it is a dataset reset, not a demonstration of durable crash recovery.

## Troubleshooting and safety

- **Cannot connect / port conflict:** confirm JVM startup and port; choose `--server.port=8081` and change `baseUrl` if needed. In Web Postman verify Desktop Agent routing; another machine's localhost is a different host.
- **Admin routes 404:** start `local`, or explicitly enable `app.test-harness.enabled=true`. `test-harness.enabled` in the settings table cannot register beans when the outer application gate is disabled.
- **Unset variables / malformed UUID:** run discovery and the preceding purchase. Remove shadowing environment variables. A collection key saved from a different application dataset is not a result lookup for the current dataset.
- **Unexpected inventory conflicts:** the five slots are already consumed/reserved. There is no reset API. Restart the JVM for a clean full run; resetting settings does not release pending work.
- **Duplicate unlimited conflict:** the normalized name already owns a pending/active claim. Use a genuinely new synthetic member for a new scenario, or reset the full demo dataset. Do not change idempotency keys for an unresolved logical purchase.
- **Recovery remains pending:** verify scheduler enabled and logs for its operation; minimum age, due/backoff and lease all apply. Repeated request alone does not invoke recovery. Exhausted/manual-review operations need operator intervention not exposed by the API.
- **Invalid scheduler configuration:** cron has six fields; enabled minimum age must be ≥90s, batch 1–10, provider/batch timeouts within configured bounds, and lock duration sufficiently long. Startup validation intentionally rejects unsafe combinations.
- **Declines/faults persist:** run folder 00 to restore settings. A terminal failed key still replays its stored failure after settings change; use a new key only for a new logical request.
- **Known concerns:** see [design caveats](02-architecture-and-design.md#limitations-and-review-caveats), including real-provider integration and database durability. The former purchase-cache race is removed by using direct database reads; simulated key/amount replay is now enforced by the ledger. No documentation scenario claims exactly-once recovery.

All admin/harness requests are trusted local/demo operations. No secrets are included in either JSON file. Keep those routes out of untrusted deployments and do not test against real financial infrastructure with this simulated workflow.
