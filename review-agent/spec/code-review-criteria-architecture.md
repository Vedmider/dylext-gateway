# Architecture Review Criteria (`code-review-criteria-architecture.md`)

Standardized evaluation specification for system architecture, distributed consistency, domain isolation, and failure resilience.

---

## Overview & Profile Activation
- **Module Prefix:** `ARC-xx`
- **Active Section Counts:** `design-review` (1 pre-implementation design gate `ARC-S0`), `quick-pr` (0–1 sections), `standard-backend` (2 sections, or 3 with conditional triggers before auto-escalation), `architecture-and-saga` (4–5 sections)
- **Focus:** Pure system architecture, distributed consistency, bounded contexts, state transitions, and fault isolation—strictly separated from language syntax or style nits.

---

## Section ARC-S0: Pre-Implementation Design & ADR Review Gate
- **Profiles:** `design-review` (Required for design docs/ADRs/specs), `architecture-and-saga` (Conditional when PR includes design doc/ADR)
- **Apply when:** Reviewing a design document, RFC, ADR, or technical specification prior to implementation.
- **Skip / Noise when:** Reviewing pure code diffs where no design doc/ADR is submitted.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-00` | `arch-design-doc-completeness` | Design doc problem statement, non-goals, >=2 alternatives & ADR alignment | Submitting or updating a design document, RFC, ADR, or technical spec | Document states explicit problem, stakeholders, non-goals, >=2 evaluated alternatives with rejection reasons, quality-attribute trade-offs, and justified ADR/layer exceptions. | Missing problem statement, non-goals, or rejected alternatives; unstated trade-offs; or unexplained violations of existing ADRs/boundaries. Finding message must name which sub-condition failed (problem statement / non-goals / alternatives / trade-offs / ADR alignment). | `blocker` | `sestacon.retrieve, diff_hunk` |

---

## Section ARC-S0b: Acceptance Criteria & Feature-Goal Traceability
- **Profiles:** `design-review` (Required for design docs/ADRs/specs), `architecture-and-saga` (Conditional when PR includes design doc/ADR)
- **Apply when:** Reviewing a design document, RFC, ADR, or technical specification prior to implementation.
- **Skip / Noise when:** Reviewing pure code diffs where no design doc/ADR is submitted.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-00e` | `arch-design-doc-completeness`, `qual-testability-determinism` | Testable acceptance criteria & feature-goal coverage | Submitting or updating a design document, RFC, ADR, or technical spec | Design specifies concrete functional/non-functional acceptance criteria and observable invariants that implementation tests can verify. | Vague aspirational goals with no verifiable acceptance criteria, leaving test suites to verify implementation details rather than intended behavior. | `major` | `sestacon.retrieve, diff_hunk` |

---

## Section ARC-S1: Distributed Workflows, Sagas & Event Consistency
- **Profiles:** `architecture-and-saga` (Required), `standard-backend` (Conditional when `diff.touches_messaging == true`), `quick-pr` (Skip)
- **Apply when:** Mutations span multiple bounded contexts, microservices, message brokers (Kafka, RabbitMQ), or transactional outbox tables.
- **Skip / Noise when:** Changes are strictly single-database CRUD or read-only query endpoints.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-01` | `arch-saga-consistency` | Saga compensation & state progression | Multi-service or multi-aggregate mutations without 2PC | Every forward step defines an explicit idempotent compensating action or persisted saga state machine for partial failure recovery. | Chained remote mutations assume all downstream calls succeed with no rollback or compensation state. | `blocker` | `dylext.impact_of, sestacon.retrieve, diff_hunk` |
| `ARC-02` | `arch-transactional-outbox` | Transactional outbox & atomic event write | Publishing domain events or webhooks alongside a database state mutation | State mutation and outbox/event record commit within the same atomic database transaction before relay dispatch. | Dual-write to DB and message broker or HTTP endpoint outside a single atomic transaction. | `blocker` | `dylext.expand, diff_hunk` |
| `ARC-03` | `arch-event-idempotency` | Consumer idempotency & out-of-order safety | Adding or modifying async event consumers, retry topologies, or DLQs | Consumers enforce idempotency keys, unique DB constraints, or monotonic version checks against duplicate or out-of-order deliveries. | Consumers blindly apply increments, state jumps, or duplicate side effects on every delivered message. | `blocker` | `dylext.expand, diff_hunk` |

---

## Section ARC-S2: Layer Boundaries, Hexagonal Isolation & Anti-Corruption
- **Profiles:** `standard-backend` (Required), `architecture-and-saga` (Required), `quick-pr` (Skip)
- **Apply when:** Adding or modifying domain entities, use cases, ports/adapters, external API/SDK clients, or cross-package imports.
- **Skip / Noise when:** Changes only touch isolated test fixtures or trivial internal field mappers.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-04` | `arch-hexagonal-boundaries` | Hexagonal inward dependency direction | Touching domain models, use cases, ports/adapters, or cross-layer imports | Domain core and use cases depend only on domain abstractions; frameworks, HTTP/gRPC DTOs, and persistence clients stay at outer adapters. | Domain entities or core use cases import transport DTOs, web controllers, or raw persistence SDKs (flag when a domain-layer file imports a transport/web/persistence package). | `major` | `dylext.outline, diff_hunk` |
| `ARC-05` | `pattern-adapter-translation` | Anti-corruption layer (ACL) translation | Integrating third-party SDKs, external REST/gRPC payloads, or legacy database schemas | External SDK types and wire contracts translate into internal domain/application models at integration boundaries. | Third-party wire models or external SDK types leak directly across internal domain services. | `major` | `dylext.outline, dylext.get_digest, diff_hunk` |

---

## Section ARC-S3: State Machines & CQRS Separation
- **Profiles:** `architecture-and-saga` (Required), `quick-pr` (Conditional when `diff.touches_state_machine == true`), `standard-backend` (Conditional when `diff.touches_state_machine == true` or `diff.touches_cqrs == true`)
- **Apply when:** Introducing or modifying entity lifecycle statuses, state transition logic, command handlers, or read projections.
- **Skip / Noise when:** Changes involve stateless utility functions or static reference lookup tables.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-06` | `arch-state-machine-transitions` | Explicit state machine transitions | Modifying entity status fields, approval flows, or lifecycle transitions | Transitions are guarded by explicit allowed-state rules/tables rejecting illegal jumps and concurrent race conditions. | Arbitrary public status setters allow invalid transitions (e.g., `CANCELLED` to `SHIPPED`) without invariant checks. | `major` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `ARC-07` | `arch-cqrs-boundaries`, `pattern-rich-domain-model` | CQRS command/query separation & domain invariants | Modifying command handlers, read projections, or core aggregate mutations | Command paths enforce domain invariants and state mutations; query paths remain side-effect-free and shaped for read latency. | Query/GET endpoints mutate state or leak heavy write-model joins; commands scatter domain invariants across anemic scripts. | `major` | `dylext.outline, dylext.impact_of, diff_hunk` |

---

## Section ARC-S4: Resiliency, Schema Migrations & Rollout Safety
- **Profiles:** `standard-backend` (Required), `architecture-and-saga` (Required), `quick-pr` (Skip)
- **Apply when:** Code makes outbound HTTP/gRPC calls, queries databases/caches, processes queued/streamed workloads, modifies database schemas/migrations (Flyway, Liquibase, SQL DDL), or introduces/modifies database sharding/partitioning and distributed caching layers.
- **Skip / Noise when:** Code performs pure in-process CPU or memory computations without network or schema changes.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ARC-08` | `arch-resiliency-fault-isolation` | Outbound timeouts, bounded retries & circuit breaking | Adding or modifying outbound network, database, or downstream service calls | Outbound calls configure explicit connect/read timeouts, bounded jittered retries on transient faults, and circuit breakers/bulkheads. | Missing network timeouts, unbounded retries amplifying downstream outages, or unisolated calls exhausting worker threads. | `major` | `dylext.expand, sestacon.retrieve, diff_hunk` |
| `ARC-09` | `arch-zero-downtime-migrations`, `qual-maintainability-evolvability` | Zero-downtime schema migrations & expand-and-contract DDL | Adding or modifying SQL DDL, Flyway, or Liquibase migration scripts | Additive backward-compatible DDL (nullable columns, `CONCURRENTLY` indexes) using expand-and-contract across rolling deploys. | Destructive DDL (`DROP`/`RENAME` column/table), non-null column without default, or table-locking index creation in a single release. | `blocker` | `sestacon.retrieve, diff_hunk` |
| `ARC-10` | `arch-data-partitioning-sharding` | Data partitioning, shard-key justification & cross-shard query bounds | Introducing or modifying database sharding, partitioning, or distributed table keys | Partitioning is evaluated before sharding; shard key aligns with primary query patterns; cross-shard queries and scatter-gather limits are explicitly enumerated. | Arbitrary shard key causing hot partitions or unbounded cross-shard fan-out queries without justification. | `blocker` | `sestacon.retrieve, dylext.outline, diff_hunk` |
| `ARC-11` | `arch-caching-ttl-invalidation` | Cache TTL derivation, invalidation strategy & bounded staleness | Introducing or modifying distributed caches (Redis, Memcached) or local cache layers | TTL is derived from data mutation frequency, cache invalidation/eviction strategy is explicit, and maximum staleness bounds are documented and safe. | Unbounded cache without TTL/eviction, arbitrary magic TTLs, or stale reads breaking consistency invariants. | `blocker` | `sestacon.retrieve, dylext.expand, diff_hunk` |

---

## Section 7: Evidence Adapter Contract

> Adapter contract: see code-review-criteria.md §7.
