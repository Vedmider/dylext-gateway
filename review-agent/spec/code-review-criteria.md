# Canonical Code Review Criteria Framework — Master Index & Profile Matrix

This document establishes the canonical specification structure, review profile contracts, dynamic section routing rules, dimension traceability matrix, extensibility guidelines, and AI review agent execution standards for the modular code review specification framework.

---

## 1. Specification Metadata & Severity Contract

### Framework Metadata

- **Spec Version:** 1.6.0
- **Suite ID:** `canonical-code-review-criteria`
- **Default Profile:** `standard-backend`
- **Core Module Files:**
    1. `code-review-criteria-architecture.md` (Prefix: `ARC-xx`, Rules: `ARC-00..ARC-11` [including `ARC-00e`]) — Pure system architecture, distributed consistency, state machines, resiliency, and zero-downtime schema migrations.
    2. `code-review-criteria-general.md` (Prefix: `GEN-xx`, Rules: `GEN-01..GEN-18` [including `GEN-07a`]) — Google review discipline, clean code, naming/CQS, security, testing, observability, performance, dependencies, and config hygiene.
    3. `code-review-criteria-java.md` (Prefix: `JAV-xx`, Rules: `JAV-01..JAV-11`) — Java types, null/Optional safety, collections/streams, exceptions, resource lifecycles, Spring DI/transactions, concurrency, Virtual Threads/reactive safety, and JVM/GC efficiency.
    4. `code-review-criteria-golang.md` (Prefix: `GOL-xx`, Rules: `GOL-01..GOL-12`) — Go idioms, error wrapping, context lifecycles, goroutine safety, interface design, package naming, slice/defer memory hygiene, zero values, and table-driven testing based on Effective Go, Go Code Review Comments, and Google Go Style Guide.
    5. **Future Language Packs (Extensible):** `code-review-criteria-python.md` (Prefix: `PYT-xx`), `code-review-criteria-typescript.md` (Prefix: `TS-xx`).

### Severity Level Definitions

| Severity | Rank | Blocks Merge | Definition & Impact Criteria |
| :--- | :--- | :--- | :--- |
| **blocker** | Rank 1 | **Yes** | Data corruption, security breach, outage risk, broken concurrency/transaction boundary, or destructive non-zero-downtime DDL. |
| **major** | Rank 2 | **Yes** | Architectural drift, missing resiliency/tests, hidden side effects, vulnerable dependencies/config, or measurable performance flaw. |
| **minor** | Rank 3 | **No** | Maintainability friction, naming/signature ambiguity, or suboptimal design pattern usage. |
| **nit** | Rank 4 | **No** | Non-blocking polish, minor style alignment, or Boy Scout cleanup (prefix review comments with `Nit:`). |

---

## 2. Review Profile Definitions & Section Budget Enforcement

### Auto-Escalation & Section Budget Rule

Every profile enforces a hard active-section cap:

1. `quick-pr`: **4–5 sections** (auto-escalates to `standard-backend` if `diff.loc > 150` or `>= 2` architectural triggers fire).
2. `standard-backend`: **6–7 sections** (auto-escalates to `architecture-and-saga` if `diff.loc > 600` or `>= 2` deep triggers fire among `touches_messaging`, `touches_state_machine`, `touches_ddl`, or `touches_spring + hot_path`).
3. `architecture-and-saga`: **8–10 sections** (full evaluation, unbounded LOC).

### Profile 0: `design-review` (Type A — Pre-Implementation Design / ADR Gate)

- **Target Section Budget:** 1–2 sections (`ARC-S0` + conditional `ARC-S1`/`ARC-S4` when distributed/infra decisions are proposed)
- **Severity Floor:** `nit`
- **Required Sections:**
    - `ARC-S0`: Pre-Implementation Design & ADR Review Gate
- **Skip Triggers:** Pure code diffs with no design doc, RFC, or ADR submitted.

### Profile 1: `quick-pr`

- **Target Section Budget:** 4–5 sections (4 non-Java/non-Go / 5 with Java or Go Core)
- **Max Diff Size:** `<= 150 LOC`
- **Severity Floor:** `minor` (omit `nit` findings unless explicitly requested)
- **Required Sections:**
    - `GEN-S1`: Google-Style Review Discipline & Communication
    - `GEN-S2`: Clean Code, Naming & Function Contracts
    - `GEN-S3`: Security, Error Handling & Deterministic Testing
- **Conditional Architectural Sections:**
    - `ARC-S3`: State Machines & CQRS Separation (when `diff.touches_state_machine == true` and total sections `<= 5`; otherwise auto-escalate profile)
- **Conditional Language Sections:**
    - `JAV-S1`: Java Types, Optional/Null Discipline, Collections & Exceptions (when `diff.languages` contains `"java"`)
    - `GOL-S1`: Go Idioms, Error Handling, Naming & Interface Design (when `diff.languages` contains `"golang"`)
- **Skip Triggers:** Skip entire evaluation when `diff.only_docs_or_comments == true` OR `diff.is_generated_only == true`

### Profile 2: `standard-backend`

- **Target Section Budget:** 6–7 sections (6 non-Java/non-Go / 7 with Java or Go)
- **Max Diff Size:** `<= 600 LOC`
- **Severity Floor:** `nit`
- **Required Sections:**
    - `GEN-S1`: Google-Style Review Discipline & Communication
    - `GEN-S2`: Clean Code, Naming & Function Contracts
    - `GEN-S3`: Security, Error Handling & Deterministic Testing
    - `GEN-S4`: Readability, Modularity, Observability, Performance & Config Hygiene
    - `ARC-S2`: Layer Boundaries, Hexagonal Isolation & Anti-Corruption
    - `ARC-S4`: Resiliency, Schema Migrations & Rollout Safety
- **Conditional Sections (1 slot -> 7 total; if 2+ triggers fire simultaneously, auto-escalate to `architecture-and-saga`):**
    - `JAV-S2`: Concurrency, Virtual Threads, JVM/GC & Spring Runtime Hygiene (priority when `diff.languages` contains `"java"` and (`diff.touches_spring == true` or `diff.hot_path == true`))
    - `JAV-S1`: Java Types, Optional/Null Discipline, Collections & Exceptions (when `diff.languages` contains `"java"` and `JAV-S2` is not active)
    - `ARC-S1`: Distributed Workflows, Sagas & Event Consistency (when non-Java/non-Go diff and `diff.touches_messaging == true`)
    - `GOL-S2`: Go Concurrency, Contexts, Memory & Performance Hygiene (priority when `diff.languages` contains `"golang"` and (`diff.touches_concurrency == true` or `diff.hot_path == true`))
    - `GOL-S1`: Go Idioms, Error Handling, Naming & Interface Design (when `diff.languages` contains `"golang"` and `GOL-S2` is not active)
- **Skip Triggers:** Skip evaluation when `diff.is_generated_only == true`

### Profile 3: `architecture-and-saga`

- **Target Section Budget:** 8–10 sections (8 base / 9–10 with Java or Go)
- **Max Diff Size:** Unbounded (deep review for distributed workflows, sagas, state machines, concurrency, and high-blast-radius changes)
- **Severity Floor:** `nit`
- **Required Sections (8 base sections):**
    - `ARC-S1`: Distributed Workflows, Sagas & Event Consistency
    - `ARC-S2`: Layer Boundaries, Hexagonal Isolation & Anti-Corruption
    - `ARC-S3`: State Machines & CQRS Separation
    - `ARC-S4`: Resiliency, Schema Migrations & Rollout Safety
    - `GEN-S1`: Google-Style Review Discipline & Communication
    - `GEN-S2`: Clean Code, Naming & Function Contracts
    - `GEN-S3`: Security, Error Handling & Deterministic Testing
    - `GEN-S4`: Readability, Modularity, Observability, Performance & Config Hygiene
- **Conditional Language Sections (2 slots -> 9–10 total):**
    - `JAV-S1`: Java Types, Optional/Null Discipline, Collections & Exceptions (when `diff.languages` contains `"java"`)
    - `JAV-S2`: Concurrency, Virtual Threads, JVM/GC & Spring Runtime Hygiene (when `diff.languages` contains `"java"`)
    - `GOL-S1`: Go Idioms, Error Handling, Naming & Interface Design (when `diff.languages` contains `"golang"`)
    - `GOL-S2`: Go Concurrency, Contexts, Memory & Performance Hygiene (when `diff.languages` contains `"golang"`)
- **Skip Triggers:** None (full evaluation mandatory)

---

## 3. Dynamic Section Selection Matrix

| Module | Section ID & Name | `design-review` (1–2) | `quick-pr` (4–5) | `standard-backend` (6–7) | `architecture-and-saga` (8–10) | Selection Trigger & Escalation Rule |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **ARC** | `ARC-S0`: Pre-Implementation Design & ADR Gate | **Required** | **Skip** | **Skip** | **Conditional** | Conditional on ADR/design doc submitted |
| **ARC** | `ARC-S1`: Distributed Workflows & Sagas | **Conditional** | **Skip** | **Conditional** | **Required** | `diff.touches_messaging == true` (Standard) |
| **ARC** | `ARC-S2`: Layer Boundaries & Isolation | **Skip** | **Skip** | **Required** | **Required** | Base requirement for backend / arch reviews |
| **ARC** | `ARC-S3`: State Machines & CQRS | **Skip** | **Conditional** | **Conditional** | **Required** | `diff.touches_state_machine == true` OR `blast_radius > 1` |
| **ARC** | `ARC-S4`: Resiliency & Backpressure | **Conditional** | **Skip** | **Required** | **Required** | Base requirement for backend / arch reviews |
| **GEN** | `GEN-S1`: Google Review Discipline | **Skip** | **Required** | **Required** | **Required** | Always active across all profiles |
| **GEN** | `GEN-S2`: Clean Code & Naming | **Skip** | **Required** | **Required** | **Required** | Always active across all profiles |
| **GEN** | `GEN-S3`: Security, Errors & Testing | **Skip** | **Required** | **Required** | **Required** | Always active across all profiles |
| **GEN** | `GEN-S4`: Observability & Performance | **Skip** | **Skip** | **Required** | **Required** | Active for standard and deep architectural reviews |
| **JAV** | `JAV-S1`: Java Core, Types & Exceptions | **Skip** | **Conditional** | **Conditional** | **Conditional** | `diff.languages` contains `"java"` |
| **JAV** | `JAV-S2`: Java Concurrency & Spring | **Skip** | **Skip** | **Conditional** | **Conditional** | `diff.languages` contains `"java"` AND (`touches_spring` OR `hot_path`) |
| **GOL** | `GOL-S1`: Go Idioms, Error Handling & Interfaces | **Skip** | **Conditional** | **Conditional** | **Conditional** | `diff.languages` contains `"golang"` |
| **GOL** | `GOL-S2`: Go Concurrency, Memory & Contexts | **Skip** | **Skip** | **Conditional** | **Conditional** | `diff.languages` contains `"golang"` AND (`touches_concurrency` OR `hot_path`) |
| **PYT** | `PYT-S1`: Python Typing & Async | **Skip** | **Conditional** | **Conditional** | **Conditional** | `diff.languages` contains `"python"` (Extensible) |
| **TOTAL** | **Active Section Count Range** | **1–2 Sections** | **4–5 Sections** | **6–7 Sections** | **8–10 Sections** | *Strict adherence to target section budgets* |

---

## 4. Canonical Dimension Traceability Registry (1:1 Verified Mapping)

| Category | Normalized Dimension ID | Mapped Rule ID | Rule Summary / Focus |
| :--- | :--- | :--- | :--- |
| **Architectural Patterns** | `arch-design-doc-completeness` | `ARC-00` | Design doc problem statement, non-goals, >=2 alternatives & ADR alignment |
| | `arch-saga-consistency` | `ARC-01` | Saga orchestration, choreography & compensating transactions |
| | `arch-transactional-outbox` | `ARC-02` | Transactional outbox pattern & dual-write prevention |
| | `arch-event-idempotency` | `ARC-03` | Idempotency keys, duplicate processing & message ordering |
| | `arch-hexagonal-boundaries` | `ARC-04` | Ports and adapters isolation & domain purity |
| | `pattern-adapter-translation` | `ARC-05` | Anti-corruption layer & external API mapping |
| | `arch-state-machine-transitions` | `ARC-06` | Explicit state transitions & terminal state safety |
| | `arch-cqrs-boundaries` | `ARC-07` | Command/query separation & read-model sync |
| | `pattern-rich-domain-model` | `ARC-07` | Rich domain modeling vs. anemic entities |
| | `arch-resiliency-fault-isolation` | `ARC-08` | Circuit breakers, timeouts, retries & backpressure |
| | `arch-zero-downtime-migrations` | `ARC-09` | Expand-contract DDL, backward compatibility & safe rollouts |
| | `arch-data-partitioning-sharding` | `ARC-10` | Partitioning before sharding, shard-key query justification & cross-shard bounds |
| | `arch-caching-ttl-invalidation` | `ARC-11` | Cache TTL derived from change frequency, invalidation strategy & staleness bounds |
| **Google Industry Standards** | `std-google-small-cls` | `GEN-01` | Small, focused change sets (< 200–400 LOC) |
| | `std-google-clarity-over-cleverness` | `GEN-02` | Clear, explicit code over clever short-cuts |
| | `std-google-why-comments` | `GEN-03` | Document rationale and design intent, not redundant mechanics |
| | `std-google-design-vs-style` | `GEN-04` | Prioritize design and bug prevention over mechanical formatting |
| | `std-google-code-health-trajectory` | `GEN-04` | Boy Scout Rule: leave code overall healthier |
| | `std-google-test-coverage` | `GEN-12`, `GEN-18` | Deterministic unit and integration testing discipline |
| | `std-review-courtesy-actionability` | `§6.7` | Reviewer courtesy, respectful tone, actionable-fix requirement (execution contract, not a code rule) |
| **Clean Code & Principles** | `clean-kiss-simplicity` | `GEN-05` | Simplicity and avoiding unnecessary complexity |
| | `clean-yagni-scope` | `GEN-06` | Premature generalization and unused code prevention |
| | `clean-dry-abstraction` | `GEN-07` | Single source of truth vs. duplicate domain logic |
| | `clean-dry-abstraction` | `GEN-07a` | Cross-module structural reuse & duplication threshold; reuse is `major` because the fix is cheaper than the violation and unblocks future refactors |
| | `clean-solid-design` | `GEN-08` | SOLID principles enforcement |
| | `pattern-god-class-decomposition` | `GEN-08` | Decomposition of massive classes into single-purpose components |
| | `clean-cohesive-functions` | `GEN-08` | Single level of abstraction per method |
| | `clean-semantic-naming-signatures` | `GEN-09` | Self-explanatory names and explicit parameter contracts |
| | `clean-command-query-separation` | `GEN-09` | Separation of side-effecting commands from pure queries |
| | `clean-shallow-nesting-guard-clauses` | `GEN-09` | Early exits and shallow indentation levels |
| | `clean-magic-values-config` | `GEN-09`, `GEN-17` | Named constants and externalized environment configuration |
| **Quality Attributes & Patterns** | `qual-readability-cognitive-load` | `GEN-02` | Minimizing mental overhead for maintainers |
| | `qual-security-input-validation` | `GEN-10`, `GEN-16` | Input sanitization, injection defense & secure dependencies |
| | `qual-testability-determinism` | `GEN-12`, `GEN-18` | Flake-free, deterministic tests with proper test doubles |
| | `qual-error-discipline` | `GEN-11` (cf. `JAV-04`) | Explicit error handling discipline, exception safety & clean propagation |
| | `qual-reusability-modularity` | `GEN-07a`, `GEN-13` | Decoupled component boundaries, shared domain contracts & reusable modules |
| | `qual-maintainability-evolvability` | `GEN-13`, `GEN-17`, `ARC-09` | Extensible design, zero hardcoded env config, safe DDL |
| | `pattern-law-of-demeter` | `GEN-13` | Principle of least knowledge in object navigation |
| | `pattern-parameter-objects-primitives` | `GEN-13` | Replacing primitive obsession with domain parameter objects |
| | `qual-observability-telemetry` | `GEN-14` | Structured logging, tracing context & metrics hygiene |
| | `qual-performance-complexity` | `GEN-15`, `JAV-09`, `GOL-12` | Algorithmic efficiency, N+1 query safety & GC minimization |
| | `pattern-creational-factories` | `GEN-15` | Factory encapsulation for complex object creation |
| | `pattern-strategy-dispatch` | `GEN-15` | Polymorphic strategy dispatch over switch-case pollution |
| | `qual-supply-chain-dependencies` | `GEN-16` | Vulnerability auditing, license safety & minimal bloat |
| **Java Engineering** | `java-immutability-records-sealed` | `JAV-01` | Java Records, sealed interfaces & value immutability |
| | `java-optional-null-discipline` | `JAV-02` | Optional anti-patterns, null annotations & defensive checks |
| | `java-collections-streams-pitfalls` | `JAV-03` | Unmodifiable collections, efficient Stream pipelines |
| | `java-exception-handling` | `JAV-04`, `GEN-11` | Clean exception hierarchies & anti-pattern suppression |
| | `java-resource-lifecycle-safety` | `JAV-05` | Try-with-resources, AutoCloseable & leak prevention |
| | `pattern-di-scope-safety` | `JAV-06` | Constructor injection, bean scope safety & circular refs |
| | `java-spring-tx-concurrency` | `JAV-07`, `JAV-08`, `JAV-10`, `JAV-11` | `@Transactional` proxy limits, thread safety, virtual threads |
| | `java-jvm-memory-gc` | `JAV-09` | Allocation efficiency, autoboxing & heap pressure |
| | `java-virtual-threads-reactive-safety` | `JAV-11` | Carrier thread pinning avoidance & non-blocking I/O |
| **Go Engineering** | `go-error-handling` | `GOL-01`, `GOL-02` | Explicit error checks, wrapping with `%w`, and custom error types |
| | `go-context-lifecycle` | `GOL-07` | Context propagation as first param and cancellation cleanup |
| | `go-goroutine-concurrency` | `GOL-08`, `GOL-09` | Goroutine lifecycle, channel synchronization, and data race prevention |
| | `go-interface-design` | `GOL-03` | Small, consumer-defined interfaces and implicit implementation |
| | `go-naming-packages` | `GOL-04`, `GOL-12` | Idiomatic Go naming, short identifiers, and package organization |
| | `go-memory-slices-defer` | `GOL-10`, `GOL-11` | Slice preallocation, defer execution order, and resource unwinding |
| | `go-zero-values-init` | `GOL-05` | Useful zero-value design and avoiding `init()` side effects |
| | `go-testing-table-driven` | `GOL-06` | Table-driven unit testing, subtests, and parallel test execution |

---

## 5. Language Pack Extensibility Contract

To integrate a new language pack (e.g. `code-review-criteria-python.md`, `code-review-criteria-typescript.md`) into the framework without modifying existing `ARC` or `GEN` rules, the module **MUST** adhere to the standard Section Template Contract defined below.

### 5.1 Extension Rules & Constraints

1. **Isolated Prefix:** The extension module must declare a unique 3-letter rule prefix (e.g. `GOL-xx`, `PYT-xx`, `TS-xx`).
2. **Zero Invalidation:** Rules inside language packs must complement, not overwrite, existing `ARC` or `GEN` rules.
3. **Budget Compliance:** Language pack rules are plugged into the **Conditional Language Sections** slots defined in Section 2, ensuring total section counts stay within profile caps (`quick-pr`: 5, `standard-backend`: 7, `architecture-and-saga`: 10).
4. **Dimension Registration:** Any new language-specific dimensions must be appended to the Dimension Registry with a `[prefix]-` mapping.
5. **Never-Renumber Stability Rule:** Append new rules (`[PREFIX]-n`) monotonically; never renumber or reassign existing Rule IDs so historical audit logs and RAG/evaluation datasets remain immutable.

### 5.2 Canonical Section Template Contract

Language pack authors must implement all sections using the following structural schema:

**Section ID:** `[PREFIX]-S[NUM]: [Section Title]`
- **Target Profiles:** `[quick-pr | standard-backend | architecture-and-saga]`
- **Scope & Evaluation Triggers:** Evaluated when `diff.languages` contains `"[language]"` AND `[additional boolean predicate]`.

| Rule ID | Mapped Dimension(s) | Review Check / Condition | Evaluation Trigger | Pass Signal (Expected) | Fail Anti-Pattern | Severity |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `[PREFIX]-01` | `kebab-dimension` | Description of precise criteria to inspect | Context trigger predicate | Concrete evidence of compliant code | Concrete pattern causing failure | `blocker \| major \| minor \| nit` |

### 5.3 Example Implementation: Python Language Pack (`code-review-criteria-python.md`)

**Section ID:** `PYT-S1: Python Typing, Async & Resource Hygiene (Example Language Pack)`
- **Target Profiles:** `quick-pr` (Conditional), `standard-backend` (Conditional), `architecture-and-saga` (Conditional)
- **Scope & Evaluation Triggers:** Evaluated when `diff.languages` contains `"python"`.

| Rule ID | Mapped Dimension(s) | Review Check / Condition | Evaluation Trigger | Pass Signal (Expected) | Fail Anti-Pattern | Severity |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `PYT-01` | `py-async-event-loop` | Avoid blocking synchronous I/O inside `async def` coroutines | Defining or calling I/O inside async functions | Async I/O uses non-blocking clients (`httpx`, `asyncpg`) or `asyncio.to_thread` | Blocking `requests.get()` or `time.sleep()` stalling the asyncio event loop | `blocker` |
| `PYT-02` | `py-mutable-defaults-typing` | Avoid mutable default arguments and untyped public boundaries | Defining public functions or dataclass fields | Default arguments use `None` or `dataclasses.field(default_factory=...)`; public signatures include type annotations | Mutable list/dict literals in `def f(items=[])` causing cross-call state leaks | `major` |

---

## 6. AI Review Agent Execution & Noise-Suppression Contract

Automated LLM/AST code review agents executing this specification **MUST** strictly adhere to the following noise-suppression and execution rules:

1. **Diff-Hunk Anchoring:** Anchor findings strictly to modified lines (`+` hunks) or direct callers/callees impacted by the diff; never emit unprompted findings on untouched legacy code unless required by `GEN-04` (Boy Scout trajectory).
2. **Confidence, Evidence Object & Failing-State Gate:** Every finding MUST include an explicit evidence object (raw Dylext AST/call-graph/git output, Sestacon doc/ADR citation, or quoted diff/spec passage) plus the specific failing input or state for logic/boundary defects. Findings lacking concrete evidence MUST be dropped—never invent a standard or rule ID.
3. **CI Linter Deduplication:** Do not emit formatting, import-ordering, or trivial style nits already covered by Checkstyle, Spotless, ErrorProne, SpotBugs, `golangci-lint`, or Ruff. When a language pack is added, extend this list with that language's canonical linter/type-checker.
4. **Canonical Comment Output Format:** Every review comment must render deterministically as:
   `[<RuleID>][<severity>] <Concise Problem Statement> — **Why:** <Impact/Invariant violated>. **Evidence:** <Symbol/Line, Failing Input/State, or ADR Quote>. **Fix:** <Concrete recommendation>.`
5. **Merge Gate Summary:** Emit a final verdict block (`APPROVE`, `COMMENT`, or `REQUEST_CHANGES`) driven strictly by whether any finding has `blocks_merge == true` (`blocker` or `major`).
6. **Multi-Pass Evaluation & Context Lookup:** Evaluate `blocker` security/transaction/concurrency rules first; when `blocker` findings exist, suppress `nit` comments to reduce review noise; inspect caller/callee call graphs and DB schemas outside the diff hunk before raising cross-boundary or N+1 findings.
7. **Reviewer Courtesy & Actionability Standard (GEN-04 Communication Standard):** Maintain a respectful, empathetic, and constructive tone in all review comments. Findings must clearly state the rationale, propose concrete fixes, and avoid pedantic or purely subjective criticism.

---

## 7. Evidence Adapter Contract

The following evidence adapters define the standard interface and payload shapes for automated tools retrieving context, AST structures, symbol definitions, impact analyses, and document references during AI review execution:

| Evidence Adapter Function | Invocation Syntax & Trigger | Output Payload Shape & Structure | Primary Purpose & Usage |
| :--- | :--- | :--- | :--- |
| `dylext.outline` | `dylext.outline(file_path, depth?)` | `{ file_path, classes: [{ name, range, methods: [{ name, range, visibility }] }] }` | Retrieves high-level structural outline and declaration hierarchy for a target source file. |
| `dylext.expand` | `dylext.expand(file_path, start_line, end_line, context_lines?)` | `{ file_path, lines: [{ line_number, content }], AST_nodes: [...] }` | Expands specific line ranges in source code with detailed AST node metadata. |
| `dylext.search_symbols` | `dylext.search_symbols(query, scope?, language?, max_results?)` | `{ query, results: [{ symbol_id, kind, location: { file, line }, signature }] }` | Locates symbol declarations and usages across codebase or designated scope. |
| `dylext.get_digest` | `dylext.get_digest(target_path_or_module, aspect?)` | `{ target, public_api: [...], dependencies: [...], complexity_score }` | Generates a concise architectural digest and public API surface summary. |
| `dylext.impact_of` | `dylext.impact_of(symbol_or_diff, scope?)` | `{ target, callers: [...], callees: [...], affected_tests: [...], risk_level }` | Computes transitive call-graph dependency impact and affected test suites. |
| `dylext.recent_changes` | `dylext.recent_changes(path, time_window?, limit?)` | `{ path, commits: [{ commit_id, author, timestamp, message, loc_changed }] }` | Retrieves git history and recent commit activity for modified files or modules. |
| `sestacon.retrieve` | `sestacon.retrieve(query, doc_type?, scope?, top_k?)` | `{ query, documents: [{ doc_id, title, clause_id, content_snippet, score }] }` | Queries architectural documentation, ADRs, and spec requirements via semantic search. |
| `diff_hunk` | `diff_hunk(file_path, patch_id?)` | `{ file_path, hunks: [{ header, old_range, new_range, added_lines, removed_lines }] }` | Parses git diff patch hunks and extracts line-level added and removed modifications. |
