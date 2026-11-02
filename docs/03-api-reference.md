# API reference

Machine-readable reference: [Swagger/OpenAPI specification](openapi.yaml), with schemas, examples, allowed values and validation bounds. See the [setup guide](07-local-setup-and-manual-testing.md#swaggeropenapi-reference) for viewing instructions.

Contents: [conventions](#conventions) · [plans](#plans) · [purchases](#purchases) · [idempotency](#idempotency) · [harness](#local-test-harness) · [health](#health-and-probes) · [errors](#error-catalogue) · [examples](#curl-examples).

## Conventions

Default base URL: `http://localhost:8080`. Business routes are under `/v1`; internal harness routes are under `/internal/v1`; there is no separate membership/member CRUD API. JSON is camelCase, amounts are integer cents, dates are ISO calendar dates and IDs are UUIDs. JSON requests use `Content-Type: application/json`. No authentication is configured.

All routes pass through the correlation filter: optional `X-Correlation-ID` must match `[A-Za-z0-9._-]{1,100}`. The accepted/generated value is returned in that response header. Invalid input returns 400 and a generated replacement ID. Business errors have:

```json
{"code":"PLAN_NOT_FOUND","message":"Plan was not found","correlationId":"example-request"}
```

Messages may differ; branch on `code`. Validation and malformed JSON errors use generic messages. Actuator uses its native health representation, not the business error DTO.

## Plans

### `GET /v1/plans`

Returns 200 with all plans, including inactive ones, sorted by ID ascending. No parameters or required headers. Catalogue fields are cached; `remainingSlots` is read dynamically and is available capacity excluding reservations.

```json
[
  {"id":"11111111-1111-1111-1111-111111111111","name":"Monthly Unlimited","type":"unlimited","active":true,"priceCents":8000,"credits":null,"remainingSlots":null},
  {"id":"22222222-2222-2222-2222-222222222222","name":"10-Class Pack","type":"pack","active":true,"priceCents":5000,"credits":10,"remainingSlots":null},
  {"id":"33333333-3333-3333-3333-333333333333","name":"Launch Promo Pack","type":"pack","active":true,"priceCents":2500,"credits":5,"remainingSlots":5},
  {"id":"44444444-4444-4444-4444-444444444444","name":"Legacy Plan","type":"unlimited","active":false,"priceCents":6000,"credits":null,"remainingSlots":null}
]
```

The last slot count is a fresh-start example and changes with activity. No create, single-plan lookup, update, or reset route exists.

## Purchases

### `POST /v1/memberships/purchases`

Required header: `Idempotency-Key` (validation below). Optional correlation header as above.

```json
{"memberName":"Example Member","planId":"22222222-2222-2222-2222-222222222222","startDate":"2026-11-02"}
```

| Field | Validation |
| --- | --- |
| `memberName` | Nonblank string; at most 160 characters. Original spelling is returned; trimmed/lowercased form is used for uniqueness/hash |
| `planId` | Non-null UUID, must reference an active plan |
| `startDate` | Non-null parsable `LocalDate`; no past/future date constraint is declared |

Successful creation or successful idempotent replay returns **201** with `Location: /v1/memberships/purchases/{id}` and:

```json
{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","memberName":"Example Member","planId":"22222222-2222-2222-2222-222222222222","planName":"10-Class Pack","planType":"pack","priceCents":5000,"credits":10,"startDate":"2026-11-02","status":"active","paymentTransactionId":"sim-txn-example","createdAt":"2026-11-02T12:00:00Z"}
```

To buy Monthly Unlimited, use the same POST with `planId` `11111111-1111-1111-1111-111111111111` and a fresh member/key. The response has `planType: "unlimited"`, `priceCents: 8000` and `credits: null`. Postman folder **02 - Successful Purchase** includes both pack and unlimited examples and their GET requests.

IDs/transaction/timestamp above are illustrative, not fixed. Side effects: idempotency/saga initialization; resource claims if needed; provider attempt/outcome; on success purchase snapshot and completed workflow. Failed payments leave audit/workflow rows but no successful purchase. HTTP error does not always mean terminal failure: 503 unknown outcome or 409 optimistic exhaustion can leave recoverable work. See [payment outcomes and recovery](06-business-flows-and-execution.md#payment-outcomes-and-compensation).

Typical failures: 400 validation/key errors, 404 unknown plan, 409 inactive/duplicate/unavailable inventory/idempotency/optimistic conflicts, 402 decline, 502 permanent provider failure, 503 transient/unknown/interrupted provider work, and 500 unexpected or injected activation failure. The catalogue below distinguishes codes.

### `GET /v1/memberships/purchases/{id}`

UUID path parameter; no required headers or body. Returns 200 with the purchase schema above, 404 `PURCHASE_NOT_FOUND` when absent, or 400 `INVALID_PARAMETER` for a malformed UUID. Only successful purchases are exposed; an operation ID with no finalized purchase returns 404. There is no HTTP saga-status lookup. Every call reads the authoritative database; there is no application purchase cache.

### `GET /v1/memberships/purchases?page=0&limit=20`

Optional integer `page` is zero-based and defaults to 0; optional `limit` defaults to 20 and must be 1–100. Negative page yields 400 `INVALID_PAGE`; invalid limit yields 400 `INVALID_LIMIT`; non-integer input yields 400 `INVALID_PARAMETER`. Sort is fixed `createdAt DESC, id DESC`; no sort parameter is implemented.

Returns 200:

```json
{"content":[],"page":0,"limit":20,"totalElements":0,"totalPages":0,"hasMore":false}
```

`content` uses the purchase schema. Requesting a page beyond the result set returns an empty page rather than a not-found error. Listing has no business write effects and queries the database on every request; no page cache is registered.

## Idempotency

Only the purchase POST requires `Idempotency-Key`. The key is trimmed, required/nonblank, at most 255 characters. No additional character regex or TTL is defined. Its scope is the entire application/studio, not a member-specific namespace.

Canonical payload hash covers trimmed/lowercased member name, plan UUID and start date, encoded as length-prefixed fields before SHA-256. Whitespace/case changes in member name therefore replay the first request and return its original display name.

| Existing record | Same canonical payload | Different payload |
| --- | --- | --- |
| None | Initialize record and saga atomically; execute purchase | Not applicable |
| `IN_PROGRESS` | 409 `IDEMPOTENCY_IN_PROGRESS` | 409 `IDEMPOTENCY_KEY_REUSED` |
| `COMPLETED` | 201, same purchase ID/snapshot | 409 `IDEMPOTENCY_KEY_REUSED` |
| `FAILED` | Replay stored HTTP status/code/message; response gets current correlation ID | 409 `IDEMPOTENCY_KEY_REUSED` |

Concurrent same-key initialization is protected by unique constraints; a losing transaction rolls back before resolving the winner. Definitive plan-validation failures may have a terminal failed record without a saga. MVC validation rejected before handler invocation does not create such a record.

After a timeout/ambiguous response, retry **the identical logical request with the same key**. Do not use a fresh key to discover the result: that represents another purchase. While recovery is pending, 409 in-progress is expected. There is no guaranteed completion deadline; unresolved manual-review records remain in progress. Changing harness settings does not reopen a terminal failed key.

## Local test harness

These routes exist only with `app.test-harness.enabled=true` (the `local` profile sets it). They are unauthenticated demo/admin controls. Never expose them to an untrusted network. The previous unversioned `/internal/admin/...` paths are no longer mapped.

| Method/path | Result |
| --- | --- |
| `GET /internal/v1/admin/test-harness/settings` | 200 array of setting views; order is not a contract |
| `GET /internal/v1/admin/test-harness/settings/{key}` | 200 one view; unknown allow-list key returns 400 `UNKNOWN_TEST_HARNESS_SETTING` |
| `PUT /internal/v1/admin/test-harness/settings/{key}` | 200 updated view; invalid value returns 400 `INVALID_TEST_HARNESS_VALUE` |

PUT body and optional header:

```json
{"value":"DECLINED","enabled":true}
```

`X-Admin-Identity: local-reviewer` is optional audit text, not authentication. A view contains `key`, `value`, `enabled`, `description`, `updatedAt` (ISO instant or null for unset default), and `updatedBy` (string or null). Each description includes the accepted values or inclusive numeric range, generated from the same rule used to validate PUT requests. API responses use the current definition even for existing rows with an older stored description. Example unset view:

```json
{"key":"payment.behavior","value":"NORMAL","enabled":false,"description":"Payment provider behavior. Allowed values: NORMAL, SUCCESS, DECLINED, TRANSIENT_ERROR, PERMANENT_ERROR, TIMEOUT_BEFORE_PROCESSING, TIMEOUT_AFTER_PROCESSING.","updatedAt":null,"updatedBy":null}
```

Strings are trimmed; booleans normalize to lowercase and other values to uppercase. Unset/disabled settings use defaults. PUT writes a versioned setting in a local retry transaction; it does not reset purchases or capacity. Request record has no `@Valid`; supplied types still must deserialize, and primitive missing `enabled` becomes false.

| Key | Default | Allowed values |
| --- | --- | --- |
| `test-harness.enabled` | `true` | `true`, `false`; runtime switch inside the enabled harness |
| `payment.behavior` | `NORMAL` | `NORMAL`, `SUCCESS`, `DECLINED`, `TRANSIENT_ERROR`, `PERMANENT_ERROR`, `TIMEOUT_BEFORE_PROCESSING`, `TIMEOUT_AFTER_PROCESSING` |
| `payment.delay-ms` | `0` | Integer 0–10000; applies to non-NORMAL payment injection |
| `payment.transient-error-count` | `0` | Integer 0–10, enabled counter bounds injected transient failures |
| `inventory.behavior` | `NORMAL` | `NORMAL`, `UNAVAILABLE`, `TRANSIENT_ERROR` |
| `inventory.failure-count` | `0` | Integer 0–10 |
| `membership.behavior` | `NORMAL` | `NORMAL`, `ACTIVATION_ERROR` |
| `membership.failure-count` | `0` | Integer 0–10 |

Disabled counter means no finite counter is configured: a selected fault can repeat indefinitely. An enabled zero counter suppresses that counter-controlled fault. Some counter changes participate in surrounding local transactions and can roll back with failure; do not assume every configured one-shot counter commits independently. Payment simulator ledger writes do commit independently. There is no settings DELETE, inventory reset, or recovery invocation endpoint.

## Health and probes

`GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/livez`, and `/readyz` return the native health status, for example `200 {"status":"UP"}` on a healthy running application. Component details are hidden; other Actuator endpoints are not exposed. Probe groups follow Boot availability state; no extra database contributor was explicitly added to readiness. Overall health includes auto-configured contributors such as the DataSource. An unhealthy/down health result can return 503; a readiness UP result is not a business-invariant check.

## Error catalogue

Built from domain constants, thrown exceptions and `ApiExceptionHandler`. Generic unexpected exceptions become 500 `INTERNAL_ERROR`; not every infrastructure failure is classified as a payment outage.

| HTTP | Code | Trigger / exposure |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Missing/invalid validated fields or unreadable JSON/body |
| 400 | `INVALID_PARAMETER` | Invalid path/query format |
| 400 | `INVALID_CORRELATION_ID` | Rejected correlation header |
| 400 | `INVALID_PAGE`, `INVALID_LIMIT` | Pagination bounds |
| 400 | `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_IDEMPOTENCY_KEY` | Missing/blank or oversized key |
| 400 | `UNKNOWN_TEST_HARNESS_SETTING`, `INVALID_TEST_HARNESS_VALUE` | Internal setting validation |
| 402 | `PAYMENT_DECLINED` | Definitive decline, after compensation succeeds |
| 404 | `PLAN_NOT_FOUND`, `PURCHASE_NOT_FOUND` | Unknown business resource |
| 404 | `NOT_FOUND` | Unmapped resource/route handled by MVC |
| 404 | `PURCHASE_SAGA_NOT_FOUND` | Internal saga lookup failure; no public saga route |
| 409 | `PLAN_INACTIVE` | Inactive selected plan |
| 409 | `INVENTORY_EXHAUSTED` | Conditional reservation affected no row |
| 409 | `ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS` | Duplicate unlimited claim |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `IDEMPOTENCY_IN_PROGRESS` | Mismatched or still-processing duplicate |
| 409 | `OPTIMISTIC_LOCK_RETRY_EXHAUSTED` | Three eligible local attempts exhausted |
| 500 | `MEMBERSHIP_ACTIVATION_FAILED` | Local harness injected activation failure; confirmed payment remains recoverable |
| 500 | `INTERNAL_ERROR` | Unclassified exception; generic public message |
| 502 | `PAYMENT_PROVIDER_ERROR` | Permanent technical provider failure |
| 503 | `PAYMENT_PROVIDER_UNAVAILABLE`, `PAYMENT_RETRY_INTERRUPTED` | Exhausted synchronous transient provider retry or interrupted backoff |
| 503 | `PAYMENT_OUTCOME_UNKNOWN` | Synchronous ambiguous outcome or simulated provider key/amount inconsistency; resources retained |
| 503 | `INVENTORY_TEMPORARILY_UNAVAILABLE` | Harness reservation fault |
| 503 (stored failure) | `PURCHASE_INTERRUPTED_BEFORE_RESERVATION`, `PURCHASE_INTERRUPTED_BEFORE_PAYMENT` | Recovery abandons safe pre-payment work; visible on later same-key replay |
| No direct status | `PAYMENT_RECONCILIATION_REQUIRED` | Persisted manual-review marker; POST retries still return in-progress |

## curl examples

Postman is the primary manual workflow: [setup guide](07-local-setup-and-manual-testing.md). These are minimal API examples:

```bash
curl -i http://localhost:8080/v1/plans
curl -i -X POST http://localhost:8080/v1/memberships/purchases \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: documentation-example-001' \
  -d '{"memberName":"Example Member","planId":"22222222-2222-2222-2222-222222222222","startDate":"2026-11-02"}'
```

Reuse that key only for the same logical request. To retrieve a generated purchase, substitute its returned UUID for `<purchaseId>` in `/v1/memberships/purchases/<purchaseId>`.
