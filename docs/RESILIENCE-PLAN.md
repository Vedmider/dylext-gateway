# Gateway resilience hardening — implementation plan

Source: the distributed-systems patterns proposal (`partitioning-sharding-proposal`, ingested into
Sestacon). Only the patterns that actually fit a thin 2-provider reverse proxy are included; Saga,
Transactional Outbox, Kafka delivery semantics and keyset pagination are **out of scope** (no event
bus, no multi-service transactions, no OFFSET paging in the stack).

## Full coverage ledger (every pattern in the source — so nothing is lost)

| # | Pattern in the doc | Decision | Where | Rationale |
|---|---|---|---|---|
| 1 | Saga (choreography/orchestration) | N/A | — | No multi-service business transactions; two-app split is deliberately decoupled, no cross-service writes. |
| 2 | Transactional Outbox + idempotency | N/A | — | No event bus; Dylext writes Postgres only and publishes nothing → no dual-write to reconcile. |
| 3 | Kafka delivery semantics (acks/ISR/DLQ, exactly-once) | N/A | — | No Kafka / event streaming anywhere in the stack. |
| 4 | **Rate limiting / throttling (token bucket)** | **DO NOW** | gateway (Phase C) | Single ingress; protects providers (esp. Sestacon `/chat`+pgvector) from bursts/runaway agents. Missing today. |
| 5 | **Retries + exponential backoff + jitter** | **DO NOW** | gateway (Phase A) | Retries exist but backoff is linear with **no jitter** → synchronized-retry risk. |
| 6 | **Bulkhead (resource isolation)** | **DO NOW** | gateway (Phase B) | One shared HttpClient today; a slow Sestacon can starve Dylext. Circuit breaker is only half the isolation. |
| 7 | CAP / PACELC consistency model | **DO NOW (doc)** | dylext docs (Phase D) | Freshness-on-read = PACELC "EC"; Sestacon = AP-ish. Make the posture explicit. |
| 8 | Keyset (cursor) pagination vs OFFSET | DEFER (when needed) | dylext | No `OFFSET` in the stack today (verified); only relevant if deep pagination is added to search/impact/recent_changes. |
| 9 | Backpressure (reactive streams) | DEFER (when needed) | — | Blocking Spring MVC, not WebFlux; only relevant if large responses are streamed. |
| 10 | Idempotency keys for retried writes | DEFER (when needed) | gateway | X-Request-Id already stamped; wire request-id dedup only if a retriable write path appears. |

## Status checklist (DO-NOW items)
- [ ] Phase A — retry exponential backoff + jitter (gateway)
- [ ] Phase B — per-provider bulkhead (gateway)
- [ ] Phase C — rate limiting / token bucket (gateway)
- [ ] Phase D — PACELC consistency note (dylext `docs/ARCHITECTURE.md`)
- [ ] `mvn verify` green in dylext-gateway; live smoke through :18070; defaults inert

The gateway is the single ingress, so it is the right place for ingress protection. It already has:
retries (idempotent+bodyless), a per-provider circuit breaker, request-size cap, X-Request/Trace-Id,
structured access log, per-provider health. Missing: rate limiting, resource isolation (bulkhead), and
retry jitter. All additions are **additive, per-provider (mirroring `CircuitBreakerRegistry`), flag/limit
gated, and default to inert** so existing throughput is unchanged until an operator tunes them.

## Phase A — Retry backoff: exponential + full jitter  [smallest]
- Today: `backoff(attempt)` sleeps `backoffMs * attempt` (linear, no jitter) — synchronized retry risk.
- Change: delay = `random(0, min(cap, base * 2^(attempt-1)))` (full jitter). Pure `computeBackoffMs(attempt)`
  for testability; `backoff` just sleeps it.
- Config: `gateway.retry.backoff-ms` (base, exists), `gateway.retry.backoff-cap-ms` (new, default 2000),
  `gateway.retry.jitter` (new, default true).
- Default: ON (improves existing behaviour; bounded by cap). Risk: low.
- Tests: bounds of `computeBackoffMs` across attempts; jitter=false reproduces capped-exponential.

## Phase B — Per-provider bulkhead (resource isolation)
- Why: a slow Sestacon (`/chat` LLM latency) must not consume all gateway capacity and starve Dylext.
- `Bulkhead` = a `Semaphore(maxConcurrent)` with a bounded `tryAcquire(acquireTimeoutMs)`; `BulkheadRegistry`
  creates one per provider (like `CircuitBreakerRegistry`).
- In `proxyTo`: acquire a permit before sending; **release in `finally`**; on rejection → `503` with a
  clear "<provider> at capacity" message + a `gateway.bulkhead.rejected` metric.
- Config: `gateway.bulkhead.max-concurrent` (0 = unlimited/off, default 0),
  `gateway.bulkhead.acquire-timeout-ms` (default 0 = fast-reject).
- Default: inert (0). Risk: none when off; when on, bounded wait then fast reject.
- Tests: `BulkheadTest` (acquire/release, reject when full, timeout path); proxy returns 503 when full.

## Phase C — Rate limiting / throttling (token bucket, per provider)
- Why: protect providers from a runaway agent or burst (esp. Sestacon's expensive path).
- `TokenBucket` (capacity=burst, refill=rps, lazy time-based refill, injectable clock) + `RateLimiterRegistry`
  per provider.
- In `proxyTo` (after module match, before breaker): `tryConsume(1)`; if empty → `429 Too Many Requests`
  with `Retry-After` + a `gateway.rate-limit.rejected` metric.
- Config: `gateway.rate-limit.requests-per-second` (0 = unlimited/off, default 0),
  `gateway.rate-limit.burst` (default = rps, min 1).
- Default: inert (0). Token bucket allows short bursts (proposal's stated advantage over fixed window).
- Tests: `TokenBucketTest` (burst then refill over a fake clock, never negative); proxy returns 429 when drained.

## Order in `proxyTo` (cheapest reject first)
`match route → rate-limit (429) → circuit breaker (503 open) → bulkhead acquire (503 full) → send(+retry) → release`.

## Phase D — Document the consistency posture (Dylext repo, docs only)
- Add a short PACELC note to `dylext/docs/ARCHITECTURE.md`: Dylext freshness-on-read = **PACELC "EC"**
  (consistency over latency; pays a HEAD-check/sync on each static read; epoch tags make it explicit);
  Sestacon retrieval is **AP-ish** (eventually consistent after ingest, no read-your-writes).

## Verification
- `mvn verify` in `dylext-gateway` (all existing + new unit tests green).
- Live smoke through :18070 with each feature enabled (429 on drain, 503 on bulkhead-full, jitter bounded).
- Defaults inert → existing e2e/probe throughput unaffected.
