# Submission notes

To view the interactive design overview, open [docs/00-overview.html](docs/00-overview.html) as a local file in your browser, or [view it through HTML Preview](http://htmlpreview.github.io/?https://github.com/neerajsu/glofox-java-memberships-task/blob/main/docs/00-overview.html). The local page works offline and requires no application server.

## Time spent

4.5 hours total, covering design, implementation, and testing.

Reviewer documentation: [requirements and scope](docs/01-requirements.md), [architecture and transactions](docs/02-architecture-and-design.md), [API reference](docs/03-api-reference.md), [data model](docs/04-data-model.md), [application components and responsibilities](docs/05-services-and-components.md), and [execution/recovery flows](docs/06-business-flows-and-execution.md).

The [Swagger/OpenAPI specification](docs/openapi.yaml) covers every implemented endpoint, headers, schemas, errors, and allowed parameter/setting values. See [how to view it](docs/07-local-setup-and-manual-testing.md#swaggeropenapi-reference) in Swagger Editor or another OpenAPI client.

## Assumptions

- The service is for one studio and uses the supplied seed plans.
- H2 is the only configured database. Data is in-memory and does not survive application restarts.
- Membership uniqueness is enforced for active/pending unlimited memberships using a [normalized member name](docs/03-api-reference.md#purchases): leading/trailing spaces are removed and letters are converted to lowercase, so `" Alice "` and `"alice"` identify the same member. Class packs can coexist.
- If a payment request times out, the service may not know whether the payment went through. It keeps any reserved plan slot or pending unlimited-membership claim while background recovery retries the payment using the same reference, so an already processed payment is not charged again. Once the outcome is confirmed, it completes the purchase or releases the reservation/claim after a confirmed failure. See [payment outcomes and recovery](docs/06-business-flows-and-execution.md#payment-outcomes-and-compensation).
- The service is designed for multiple pods sharing one database. The supplied in-memory H2 setup must first be replaced with a shared database such as PostgreSQL, including the required driver, connection configuration, schema migrations and any database-adapter changes. Database constraints, transactions and shared recovery locks are intended to handle concurrency and race conditions across pods; this must be verified against PostgreSQL in a multi-pod deployment, since the current tests use H2. See [concurrency and consistency](docs/02-architecture-and-design.md#concurrency-and-consistency).

## API / design choices

- JSON uses camelCase. Purchase amounts are integer cents and dates are calendar dates.
- Purchase creation requires `Idempotency-Key`; repeated requests replay a completed purchase, while an in-progress key returns 409.
- Definitive payment declines return 402. Provider outages return 503. Errors contain `code`, `message`, and `correlationId`.
- A supplied `X-Correlation-ID` must contain 1 to 100 letters, digits, dots, underscores, or hyphens. Missing values are generated and returned in the response header.
- Spring Boot Actuator exposes `/actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness`, plus `/livez` and `/readyz` on the application port. Health details are hidden and no other actuator endpoints are exposed.
- Optimistic-lock conflicts use Spring Framework 7 `RetryTemplate` and are retried up to three times in fresh transactions with short jittered backoff; payment and saga recovery retries remain separate. Exhaustion returns HTTP 409 with `OPTIMISTIC_LOCK_RETRY_EXHAUSTED`.
- Only static plan catalogue metadata uses local Spring Cache. Inventory and all purchase-by-ID/page reads query the database on every request; purchase writes/finalization need no cache eviction.
- The default and fault-simulation payment paths share a DB-backed provider ledger, available with the harness disabled. Same key/amount replays one transaction; a unique-key collision is resolved in a fresh transaction. Amount mismatch is rejected as an unknown provider inconsistency, not a decline.
- New idempotency records and their initial sagas are created atomically after plan validation. Recoverable saga states retain `IN_PROGRESS`; terminal failure is recorded with a definitive failed/compensated state.
- Saga recovery is invoked automatically every 15 seconds by a configurable, gated ShedLock JDBC scheduler. Manual invocation remains available; per-saga claims and leases remain independent. Defaults: batch 10, inactivity 90 seconds, scheduler lock five minutes, provider wait five seconds, batch admission deadline two minutes. See [recovery execution](docs/06-business-flows-and-execution.md#recovery-worker-and-locks), [lock/timing design and limitations](docs/02-architecture-and-design.md#scheduler-and-lease-coordination), and the [configuration guide](docs/07-local-setup-and-manual-testing.md#configuration-and-profiles).
- The optional database-backed test harness is disabled by default and enabled in the `local` profile. Its versioned unauthenticated admin API at `/internal/v1/admin/test-harness/settings` is intended only for trusted local/demo use.

## Tests

Start with the [local setup and manual testing guide](docs/07-local-setup-and-manual-testing.md). It explains startup, profiles, automated test commands, Postman import/run order, expected results, and scenarios that require integration tests. Import the [Postman Collection v2.1](postman/Glofox-Memberships.postman_collection.json) and optional [local environment](postman/Glofox-Memberships-Local.postman_environment.json); the guide describes the fresh-database prerequisite and how to verify scheduled recovery.

- Unit tests live in `src/test/java` and use the same Java package as each production class under test. Coverage includes idempotency decisions and persistence state, payment/saga state transitions and stores, purchase validation/finalization/compensation, inventory reservation and release behavior, plan catalog/query/seeding behavior, correlation/error mapping, and test-harness setting/ledger behavior. Repository collaborators are mocked where appropriate.
- Application/database integration tests live in `src/integrationTest/java` and cover API behavior, concurrency, optimistic-lock retry transactions, cache behavior, payment failures, idempotency, saga recovery crash windows, harness behavior, and correlation IDs.
- Run unit tests with `./gradlew test`, integration tests with `./gradlew integrationTest`, and both with `./gradlew check`.
- Latest full verification (2026-11-02): `./gradlew check bootJar --rerun-tasks` passed with **71 unit tests and 73 integration tests**, zero failures/errors/skips. Unit and integration suites were also run separately. Coverage includes forced provider-key insert races, outer-transaction rollback survival, amount-mismatch classification, uncached committed changes and delayed-read/finalization regressions. Final review found and fixed a timestamp precision mismatch: generated purchase timestamps now match H2's microsecond precision, so creation, GET and idempotent replay return the same snapshot. A new integration test reproduces and guards that behavior. See [transaction boundaries](docs/02-architecture-and-design.md#transaction-boundaries) and [read consistency](docs/02-architecture-and-design.md#read-caching) for the design.
- Packaged-app checks passed **41 HTTP smoke assertions** across default and `local` profiles, covering health/probes, hidden Actuator details, harness gating/settings, required headers, purchases, replay, uniqueness, finite inventory and decline cleanup. Actual scheduled recovery completed a timeout-after-processing purchase using the default 90-second inactivity gate and 15-second cron; subsequent POST replay and GET returned the same snapshot. OpenAPI/Postman schema checks, documentation links and Postman script validation also passed. These checks did not use the Postman GUI/Runner; follow the [manual testing guide](docs/07-local-setup-and-manual-testing.md) to run the collection yourself.

## Known gaps / what I would do next

See [design limitations and review caveats](docs/02-architecture-and-design.md#limitations-and-review-caveats) for remaining deployment and real-provider limitations. Purchase reads are uncached, and simulated provider idempotency is enforced by the existing ledger and database uniqueness.

- **Cancellation stretch goal:** Implement `POST /v1/memberships/purchases/{id}/cancel` to soft-cancel a purchase and restore promo inventory when applicable.
- **Recovery operations:** Scheduled recovery is implemented. Production still needs recovery metrics, alerts for manual-review/exhausted operations, pause/re-drive controls, and budget validation against real provider and database timeouts.
- **Production database configuration:** The exercise uses H2. Its consistency mechanisms are database-level and compatible with PostgreSQL, but need PostgreSQL integration verification and schema migrations when adopting a production database.
- **Catalogue cache scope:** Only static plan metadata is cached locally. A future catalogue-write feature would need an invalidation policy; mutable purchase and inventory reads already bypass application caching.
- **Production payment integration:** The supplied gateway is simulated. A real provider adapter needs provider-specific idempotency, timeout/status reconciliation, webhook verification, and operational handling of unresolved outcomes.

## AI tools used (optional)

- OpenAI Codex was used as a coding collaborator. The implementation and tests were reviewed and adapted in the repository.
