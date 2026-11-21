# General Code Review Criteria (`code-review-criteria-general.md`)

Standard engineering specifications for cross-language code reviews.

---

## Overview & Profile Activation
- **Module Prefix:** `GEN-xx`
- **Active Section Counts:** `quick-pr` (3 sections), `standard-backend` (4 sections), `architecture-and-saga` (4 sections)
- **Focus:** Language-agnostic code quality, Google engineering review standards, clean code principles, security boundaries, deterministic testing, observability, and performance.

---

## Section GEN-S1: Google-Style Review Discipline & Communication
- **Profiles:** `quick-pr` (Required), `standard-backend` (Required), `architecture-and-saga` (Required)
- **Scope & Triggers:**
    - **Apply when:** Reviewing any pull request or changelist.
    - **Skip / Noise when:** Formatting rules are already enforced by automated linters/formatters (do not manually bikeshed formatting).

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GEN-01` | `std-google-small-cls` | Small, single-purpose diff scope | PR/CL touches multiple files or mixes feature work with refactoring | Change focuses on one cohesive task; unrelated refactors or reformatting are split into separate PRs. | Diff bundles feature logic with broad unrelated cleanup, unrequested rewrites, or noisy reformatting. | `minor` | `dylext.recent_changes, diff_hunk` |
| `GEN-02` | `std-google-clarity-over-cleverness`, `qual-readability-cognitive-load` | Clarity over cleverness & low cognitive load | Non-trivial logic, conditionals, or data transformations | Control flow and idioms are straightforward and immediately understandable without holding excessive mental state. | Clever bit hacks, nested ternaries, or dense one-liners obscure intent and increase cognitive load. | `minor` | `dylext.get_digest, dylext.expand, diff_hunk` |
| `GEN-03` | `std-google-why-comments` | Why-focused comments & stale comment pruning | Public APIs, tricky invariants, workarounds, or modified commented blocks | Comments concisely explain why non-obvious decisions or constraints exist; redundant or outdated comments are removed. | Comments merely restate what the code does, contradict updated logic, or rely on PR discussion instead of clear code. | `nit` | `dylext.expand, sestacon.retrieve, diff_hunk` |
| `GEN-04` | `std-google-design-vs-style`, `std-google-code-health-trajectory` | Design vs. style separation & constructive code health | All review feedback and incremental edits to legacy code | Substantive correctness/design issues are separated from non-blocking `"Nit:"` polish; comments target code with concrete fixes and leave touched files healthier. | Style preferences block merges, feedback targets the author rather than code, or valid incremental fixes are blocked on unrelated legacy rewrites. | `nit` | `diff_hunk, sestacon.retrieve` |

---

## Section GEN-S2: Clean Code, Naming & Function Contracts
- **Profiles:** `quick-pr` (Required), `standard-backend` (Required), `architecture-and-saga` (Required)
- **Scope & Triggers:**
    - **Apply when:** Adding or modifying production classes, functions, method signatures, or constants.
    - **Skip / Noise when:** Reviewing auto-generated code or trivial boilerplate getters/setters.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GEN-05` | `clean-kiss-simplicity` | KISS & minimal indirection | Introducing new abstractions, wrappers, helpers, or control flows | Implementation uses the simplest direct structure that satisfies current requirements without extra layers. | Over-engineered mini-frameworks, multi-layer pass-through wrappers, or speculative indirection complicate simple logic. | `minor` | `dylext.outline, dylext.get_digest, diff_hunk` |
| `GEN-06` | `clean-yagni-scope` | YAGNI & no speculative scope | Adding config flags, unused parameters, hooks, or generic extension points | Every parameter, flag, branch, and interface has an active caller or documented immediate requirement. | Dead code, unused feature toggles, or speculative extension hooks built for hypothetical future use cases. | `minor` | `dylext.impact_of, dylext.search_symbols, diff_hunk` |
| `GEN-07` | `clean-dry-abstraction` | DRY without premature coupling | Duplicated multi-line logic across 2+ places or newly extracted shared helpers | Shared helpers consolidate genuine single domain invariants while leaving coincidental similarity decoupled. | Copy-pasted business rules risk divergent bug fixes, or forced shared helpers couple unrelated domains. | `minor` | `dylext.search_symbols, dylext.get_digest, diff_hunk` |
| `GEN-07a` | `qual-reusability-modularity`, `clean-dry-abstraction` | Cross-module structural reuse & duplication threshold. Cross-module reuse is major because it is the only DRY variant where the fix (adopting the existing module) is cheaper than the violation and the violation blocks a future refactor. | Introducing a new class, function, or helper structurally similar to existing symbols across modules | Reuses an existing module/helper via `dylext.search_symbols` + `get_digest`, or documents why a new symbol is distinct rather than duplicating an existing one. | New symbol duplicates existing cross-module logic above configured similarity/fan-out threshold without reuse or explicit rationale. | `major` | `dylext.search_symbols, dylext.get_digest, diff_hunk` |
| `GEN-08` | `clean-solid-design`, `pattern-god-class-decomposition`, `clean-cohesive-functions` | SOLID responsibilities & cohesive functions | Defining classes, interfaces, or multi-step service/handler methods | Classes follow SRP and interface segregation; functions operate at one cohesive abstraction level with balanced dependencies. | God classes accumulating unrelated dependencies, fat interfaces, or functions mixing I/O, parsing, and domain logic. | `major` | `dylext.outline, dylext.impact_of, diff_hunk` |
| `GEN-09` | `clean-semantic-naming-signatures`, `clean-command-query-separation`, `clean-shallow-nesting-guard-clauses`, `clean-magic-values-config` | Semantic naming, CQS, guard clauses & named constants | Adding/renaming methods, booleans, queries, thresholds, or nested conditionals | Names convey exact behavior/units; `get`/`find`/`is` queries have zero hidden mutations; guard clauses keep nesting `<= 3` levels; magic literals are named constants. | Ambiguous names, hidden DB/network writes inside getters, deep `if/else` pyramids, or unexplained magic numbers/wall-clock assumptions. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |

---

## Section GEN-S3: Security, Error Handling & Deterministic Testing
- **Profiles:** `quick-pr` (Required), `standard-backend` (Required), `architecture-and-saga` (Required)
- **Scope & Triggers:**
    - **Apply when:** Handling external inputs, authn/authz, error paths, time/randomness, or unit/integration tests.
    - **Skip / Noise when:** Reviewing static documentation or purely cosmetic renames.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GEN-10` | `qual-security-input-validation` | Input validation, authz & secret/PII hygiene | Accepting external inputs, queries, file paths, tokens, or permission checks | Untrusted inputs are validated at trust boundaries, authn/authz is enforced, queries are parameterized, and secrets/PII are never logged. | Missing input validation, SQL/command/path/SSRF injection risks, missing authz checks, or secrets/PII leaked in logs or errors. | `blocker` | `dylext.expand, diff_hunk` |
| `GEN-11` | `qual-error-discipline` (see `JAV-04`) | Explicit error handling & context preservation | Error handling blocks, fallback paths, and boundary error translation | Errors are handled explicitly with actionable context, preserved root causes, and clean domain error mapping. | Swallowed errors, silent catch-all suppression, or generic error responses stripping root diagnostic context. | `major` | `dylext.expand, diff_hunk` |
| `GEN-12` | `std-google-test-coverage`, `qual-testability-determinism` | Deterministic test execution, acceptance-criteria traceability & edge-case coverage | Adding or modifying production logic, bug fixes, or error paths | Fast, deterministic unit/integration tests verify happy paths, boundary conditions (empty/null/max/concurrency), and failure modes using injected clocks/doubles; every acceptance criterion declared under `ARC-00e` for the corresponding design doc is traceable to at least one deterministic test that would fail if that criterion regressed. | Missing tests for changed behavior, boundary/error paths, or flaky tests coupled to real clocks/network; an `ARC-00e` acceptance criterion with no corresponding test; logic-correctness review findings must cite the specific failing input or state. | `major` | `dylext.impact_of, diff_hunk, sestacon.retrieve` |
| `GEN-18` | `std-google-test-coverage`, `qual-testability-determinism` | Meaningful assertions & behavioral test coupling (no over-mocking) | Adding or modifying unit, slice, or integration test cases | Tests assert observable outputs/state transitions with explicit assertions and mock only external collaborators. | Assertion-free tests ("smoke-only"), mocking the subject under test, or brittle call-order verification coupled to private internals. | `major` | `dylext.expand, diff_hunk` |

---

## Section GEN-S4: Readability, Modularity, Observability, Performance & Config Hygiene
- **Profiles:** `standard-backend` (Required), `architecture-and-saga` (Required), `quick-pr` (Skip)
- **Scope & Triggers:**
    - **Apply when:** Modifying backend services, data access, public APIs/schemas, shared libraries, hot execution paths, build manifests (`pom.xml`, `build.gradle`, `go.mod`, `package.json`), or runtime config/IaC files (`application.yml`, Helm, Kubernetes, Terraform).
    - **Skip / Noise when:** Reviewing small isolated bug fixes covered by `quick-pr`.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GEN-13` | `qual-reusability-modularity`, `qual-maintainability-evolvability`, `pattern-law-of-demeter`, `pattern-parameter-objects-primitives` | Modularity, Law of Demeter & backwards compatibility | Changing public contracts/schemas, shared utilities, or multi-parameter signatures | Shared modules expose decoupled parameter-object contracts without train-wreck getter chains; API/schema changes preserve backwards compatibility. | Deep object graph chaining (`a.getB().getC()`), long primitive parameter lists, or breaking contract changes without versioning. | `minor` | `dylext.search_symbols, dylext.get_digest, dylext.impact_of, diff_hunk` |
| `GEN-14` | `qual-observability-telemetry` | Observability (structured logs, metrics & tracing) | Adding request handlers, background jobs, error paths, or external integrations | Critical paths and failures emit structured logs with correlation/trace context and metrics without PII leaks or log spam. | Silent failures, missing trace/correlation IDs across boundaries, or high-volume log spam inside tight loops. | `major` | `dylext.expand, sestacon.retrieve, diff_hunk` |
| `GEN-15` | `qual-performance-complexity`, `pattern-creational-factories`, `pattern-strategy-dispatch` | Performance complexity, sync-chain depth & appropriate design patterns | Iterating collections, querying databases/caches, or selecting varying algorithms | Algorithmic complexity, batching, indexes, and bounded synchronous call-chain depth fit production scale; Factory, Strategy, or Builder patterns cleanly replace sprawling conditionals. | Quadratic loops, N+1 queries, missing indexes, unbounded sync call chains, or forced/awkward pattern usage. | `major` | `dylext.impact_of, dylext.get_digest, diff_hunk` |
| `GEN-16` | `qual-supply-chain-dependencies`, `qual-security-input-validation` | Dependency versions, CVE exposure & build manifest hygiene | Modifying `pom.xml`, `build.gradle`, `go.mod`, `package.json`, or lockfiles | Dependencies use pinned release versions with no known critical CVEs, justified transitive footprint, and compatible licenses. | Unpinned/SNAPSHOT dependencies in production builds, known vulnerable versions, or heavy unused libraries. | `major` | `diff_hunk, sestacon.retrieve` |
| `GEN-17` | `clean-magic-values-config`, `qual-maintainability-evolvability` | Configuration, feature-flag & deployment manifest safety | Modifying `application.yml`/properties, feature flags, Helm charts, Kubernetes manifests, or Terraform | Config defaults are safe, secrets resolve dynamically from external secret managers / Vault / KMS rather than plain config or hardcoded credentials, actuator/admin ports stay restricted, and stale flags are cleaned up. | Hardcoded environment URLs/credentials, plain text secrets in config instead of secret-manager / Vault / KMS resolution, publicly exposed actuator/debug endpoints, or unbounded resource/timeout defaults. | `major` | `diff_hunk, sestacon.retrieve` |

---

## Section 7: Evidence Adapter Contract

Specifies tool call invocation parameters and expected output data structures for automated code review adapters.

> Adapter contract: see `code-review-criteria.md` §7.
