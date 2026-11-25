# Dylext \+ Sestacon Architecture Reference

**Status:** Level 2 Architecture Design Baseline (Sections 1–17) — **not frozen**; selective Level 3 design in progress (Sections 18–24)  
**Deployment Model:** Local-First, Remotely Extensible  
**Primary Host Integration:** Claude Code (AI-agnostic across Codex, Cursor, Kiro CLI, and CI runners)

---

## 1\. Core Architectural Thesis

Dylext is not a bag of low-level search tools for an AI agent to coordinate, nor is it a competing vector database for source code. It is a local-first code intelligence and review platform paired with Sestacon as its persistent project-knowledge layer.

* **Core Value Proposition:** AI coding agents lose context window and reasoning accuracy when forced to run multi-round exploratory searches to discover callers, test gaps, repository rules, and coupled documentation. Dylext investigates the repository on behalf of the agent and returns a smallest sufficient, trustworthy, revision-aware evidence package so the agent spends its context window solving the engineering task rather than discovering repository structure.  
* **Exclusive Agent Entry Point:** `dylext-gateway` is the exclusive agent-facing integration boundary. All AI hosts communicate with both Dylext and Sestacon capabilities through this single Gateway. Sestacon does not expose a competing, independently configured agent-facing MCP interface; the Gateway routes knowledge requests internally to Sestacon while preserving independent service and data ownership.  
* **Strict Separation of Responsibilities:**  
  * **Gateway (`dylext-gateway`):** Owns the single agent-facing interface, workspace-to-`repo_id` resolution, stateless request routing, and future remote authentication/tenant routing.  
  * **Dylext Intelligence Core:** Owns canonical code identities, symbol and dependency graphs, Git change analysis, impact analysis, review-rule resolution, evidence validation, and context assembly.  
  * **Sestacon:** Owns persistent non-code project knowledge (ADRs, requirements, API contracts, engineering standards, working architecture drafts), document versioning, approval lifecycle, and knowledge staleness state.  
  * **AI Agent / Host Model:** Owns engineering reasoning, implementation, and review judgment.  
  * **External Intelligence Providers:** Supply specialized signals (optional LSP/SCIP resolution, static analysis engines such as Semgrep and CodeQL, and diagnostic normalization/reporting formats such as reviewdog and SARIF); they never own the workflow or canonical graph.

### Considered Alternatives (Why Not the Cheaper Substitutes)

The cost thesis obliges an explicit "why not the free/simpler option." Each alternative was evaluated and rejected as insufficient *on its own* for the goal of cutting the agent's context-discovery loop:

* **Plain `grep`/`ripgrep` + read-whole-file (the status quo):** Zero infrastructure, but it is exactly the multi-round, token-heavy discovery loop this product exists to remove — no caller/impact graph, no test-gap awareness, no revision stamping, and whole-file reads blow the token budget. Rejected as the baseline being beaten, not a solution.
* **A raw LSP/SCIP index exposed directly to the agent:** Accurate symbol resolution, but LSP is a live, stateful, per-language server stream (not a stateless, revision-stamped evidence package), returns unranked structural data with no token budgeting, no test/doc blending, and no epistemic honesty layer. Dylext instead consumes LSP/SCIP as an *optional* evidence provider behind an adapter (ADR-02).
* **Pure vector-RAG over source code (embed every file/chunk):** Cheap to build, but embeddings give fuzzy similarity, not exact callers/overrides/impact; they hallucinate structure and cannot answer "who calls this" or "which tests cover this diff" with provenance. Rejected as a *retrieval authority* for code structure — but **not excluded as a grounded recall signal**: embeddings are a viable optional provider when every hit is resolved to a canonical `symbol_uri` and verified against the graph before use (ADR-09, §18.E). The bet is "exact-first, semantic-for-recall-then-grounded," never "semantic-as-truth."
* **An all-in-one agent that calls many low-level tools itself:** Maximum flexibility, but it pushes the discovery loop (and its token cost) back into the agent's context window — the opposite of the goal. Dylext moves the investigation server-side and returns one bounded result.
* **Existing code-graph MCPs (`codebase-memory-mcp`, `code-review-graph`):** Useful prior art, kept as optional benchmark comparators (ADR-05), not production dependencies — none combine revision-aware exact graph, token-budgeted progressive disclosure, epistemic labeling, and a prose-knowledge sibling behind one stateless gateway.

The architecture's bet: the win is not any single capability above but their *composition* behind a stateless, token-budgeted, revision-stamped, epistemically-honest evidence contract. If that composition does not measurably beat plain grep on tokens and tool-calls (§10), the thesis fails.

---

## 2\. System Layers and Ownership Boundaries

| Layer / Component | Explicit Ownership | What It Must NOT Own |
| :---- | :---- | :---- |
| **Gateway (`dylext-gateway`)** | Exclusive agent-facing integration boundary (MCP stdio locally; stateless Streamable HTTP remotely), workspace-to-`repo_id` resolution, unified routing to Dylext Core and Sestacon, and future auth/tenant routing | Internal code parsing, graph queries, review logic, or hidden stateful conversation sessions |
| **Dylext Intelligence Core** | Canonical symbol graph, Git change analysis, impact analysis, Markdown review-rule scope resolution, context assembly, finding validation, and rule-coverage auditing | Persistent document lifecycle management or host LLM infrastructure |
| **Sestacon** | Persistent project documents, approval states (`approved`, `working`, `historical`), draft continuation, hybrid retrieval, and document-to-symbol staleness tracking — *ownership of this responsibility is defined now; automated, event-driven drift propagation activates in Milestone 4 (see §6, §7). Until then Sestacon owns the staleness **model** but reports `staleness_unverified` rather than active tracking.* | Source-code AST parsing, competing code call graphs, or a separate agent-facing MCP server |
| **External Intelligence Providers** | Optional static analysis engines (Semgrep, CodeQL), diagnostic normalization/reporting adapters (reviewdog, SARIF), and selective symbol resolution (LSP / SCIP adapters) | Overwriting Dylext's canonical relationships or blocking Fast Path execution |

### Non-Goals (Explicit Exclusions)

* No separate, competing agent-facing MCP endpoint for Sestacon; all agent traffic enters through `dylext-gateway`.  
* No second general-purpose repository parser or indexer inside Dylext.  
* No source-code call graph inside Sestacon.  
* No separate review engine per AI host; all agents consume the same canonical evidence contract.  
* No automatic promotion of AI-generated working drafts to approved knowledge in Sestacon.  
* No claim that static call/test references prove runtime execution or runtime coverage.  
* No giant context dumps containing entire files or the full documentation corpus.

---

## 3\. Deployment Model: Local-First, Remotely Extensible

Dylext is designed and shipped as a local-first developer product while preserving clean boundaries so a centralized remote/team deployment can be added later without rewriting the core domain.

| Architectural Concern | Initial Target: Local-First (Milestones 1A–4) | Future Capability: Remote / Team (Milestone 5+) | Preserved Design Invariant |
| :---- | :---- | :---- | :---- |
| **Entry Point & Transport** | Local `dylext-gateway` subprocess over MCP stdio (diagnostics on stderr) | Remote `dylext-gateway` over stateless Streamable HTTP \+ OAuth 2.1 | **Transport independence:** MCP stdio is an adapter; every domain request is explicit and stateless |
| **Storage & Persistence** | Independent local stores: SQLite (WAL mode) for Dylext graph/snapshots and local storage for Sestacon | Independently owned centralized storage services for Dylext and Sestacon (e.g., separate PostgreSQL instances or stores), each retaining its own schemas, APIs, and transaction boundaries | **Storage independence:** No shared database or cross-system transaction; domain logic accesses storage strictly via `GraphStore`, `SnapshotStore`, and `KnowledgeProvider` interfaces |
| **Workspace Resolution** | Local Git working tree and committed revisions resolved at the `dylext-gateway` edge | Managed Git mirrors / CI pull-request checkouts | **Filesystem isolation:** Core domain operates only on logical `repo_id`, revision, and repo-relative paths |
| **Execution Topology** | Single-machine modular process; local routing between Gateway, Dylext Core, and Sestacon | Distributed workers, durable event brokers, horizontal scaling | **Execution isolation:** Workflows never assume hidden in-memory shared state across requests |
| **Auth & Multi-Tenancy** | Single local OS user; environment-based configuration | Centralized RBAC, multi-tenant isolation, rate limiting | **Configuration isolation:** Credentials, tenant IDs, and local paths stay outside the core domain model |

> **Explicitly Deferred (Do Not Build Now):** Remote Gateway deployment, distributed message brokers, Kubernetes/container orchestration requirements, multi-tenant isolation, high-availability clusters, and enterprise auth infrastructure.

---

## 4\. Two-Path Execution Model (Fast Path vs. Extended Path)

Interactive coding agents cannot block for 10–30 seconds waiting on heavy static analyzers. Dylext splits execution into two paths:

1. **Fast Path (Interactive Default — Target $<300\text{ms}$ p95 local processing):**  
   * Runs locally against the canonical SQLite symbol graph, Git change information supported by the current milestone (committed `HEAD` analysis in Milestone 1A, with dirty working-tree overlay introduced in Milestone 1B), repository Markdown rules (Milestone 1B+), and local/cached Sestacon knowledge.  
   * Powers `prepare_implementation_context`, `analyze_impact` (both Milestone 1A), and — in the **target architecture** — fast interactive `review_change` checks. *`review_change` is a target-state Fast Path capability that becomes available in Milestone 2 (see §10); it is listed here to show where it executes, not to imply Milestone 1A availability.*  
   * **Latency Measurement Scope:** The $<300\text{ms}$ p95 target measures local application processing time (`dylext-gateway` routing \+ Dylext graph/impact/context assembly \+ local Sestacon retrieval when invoked) and explicitly excludes external LLM reasoning or response generation. Warm-index and cold-index latencies are tracked and reported separately.  
   * Never blocked by slow external analyzers.  
2. **Extended Path (On-Demand / Cached / CI):**  
   * Runs or ingests expensive diagnostics: static analysis engines (Semgrep, CodeQL), normalized diagnostic reports (reviewdog, SARIF), runtime test coverage artifacts, and selective LSP/SCIP symbol resolution.  
   * Enriches findings asynchronously or on explicit request without delaying initial agent progress.

---

## 5\. Dylext Internal Architecture

Dylext is structured as a modular application coordinated by a central Analysis Orchestrator:

### A. Canonical Code Intelligence & Graph

* **Authoritative Parsers:** Retains Dylext's existing Java, Python, Go, and TypeScript parsers as the primary extraction layer.  
* **Canonical Repository Model:** Normalizes files, modules, classes, interfaces, functions, methods, calls, imports, inheritance/implementations, and test-to-code relationships.  
* **Epistemic Relationship Classification:** Every edge records resolution status (`Confirmed` — defined strictly as confirmed static/source-level resolution, `Potential impact`, or `Unknown`), source provider, repository revision, and confidence. Statically inferred dispatch is never presented as confirmed runtime behavior.

### B. Git Change Analyzer (`ChangeSet`)

* Produces a single normalized `ChangeSet` mapping changed hunks to canonical symbols so downstream analyzers never re-interpret diffs independently.  
* **Phased Capabilities:**  
  * **Milestone 1A:** Resolves clean committed `HEAD` revisions (and commit-to-parent diffs), handling renames, deletions, generated files, and binary changes.  
  * **Milestone 1B:** Adds staged/unstaged dirty working-tree analysis, re-parsing modified uncommitted files and invalidating stale incoming/outgoing graph edges tied to the previous revision snapshot.  
  * **Milestone 3:** Adds cumulative PR diffing (merge-base to head).

### C. Impact Analyzer & Distinct Test Evidence

Traces direct callers, transitive blast radius, interface implementations, API/schema contracts, and test relationships while keeping four test evidence categories strictly separate:

1. **Static test reference:** Static analysis shows a test references or calls the target symbol (missing references trigger a structural warning, not a runtime coverage claim).  
2. **Test execution:** Evidence that the test actually ran.  
3. **Runtime coverage:** Coverage artifact confirms the executed test traversed the changed code path.  
4. **Test failure:** Test failed under a specific execution revision.

### D. Review Rule Resolver & PR Security Policy (Milestone 1B / 3\)

* Discovers and resolves scoped repository instructions (`REVIEW.md`, `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `.github/instructions/**/*.instructions.md`, and referenced rule docs) across global, directory, and glob scopes.  
* **Trusted Base-Branch Rule Policy (Milestone 3):** For PR reviews, rules from the target (base) branch form the trusted policy baseline. Head-branch edits to instruction files cannot silently weaken or disable existing checks; they are surfaced explicitly as proposed policy changes, while strictly additive checks may be evaluated alongside the baseline.

### E. Context Builder & Stateless Progressive Evidence (`analysis_id`)

* **Configurable Token Budgets:** Replaces fixed hard truncation with caller-configurable token budgets and progressive drill-down via `get_evidence`.  
* **Stateless Progressive Drill-Down (`analysis_id`):** To keep `dylext-gateway` completely stateless across requests, every analysis response emits an immutable `analysis_id` bound to `repo_id`, `repo_revision` (`snapshot_id`), and the persisted evidence set for that analysis under a defined local retention policy (e.g., bounded ring buffer / TTL in Dylext's snapshot store). Subsequent `get_evidence(analysis_id, evidence_ids[])` calls retrieve omitted items deterministically without relying on hidden Gateway session memory.  
* **Multi-Signal Evidence Ranking (ADR-03):** Ranks candidate context items using:  
  1. Task and requirement relevance  
  2. Direct structural distance to target/changed symbols  
  3. Impact depth and relationship confidence  
  4. Applicable tests and external contracts (HTTP/Kafka/OpenAPI)  
  5. Scoped repository rules and approved Sestacon documents  
  6. Graph centrality (auxiliary signal only—never dominates task relevance)  
  7. Token cost  
* **Explicit Omissions:** When budget limits exclude items, the response explicitly reports what was omitted and why, along with `analysis_id` references so the agent can call `get_evidence` if needed.

### F. Review Engine (Preflight vs. Review Division)

Preflight and Review share the exact same underlying intelligence core but serve distinct workflow phases:

* **Preflight (`prepare_implementation_context`, `analyze_impact`):** Runs before code edits on the Fast Path. Delivers entry points, callers, static test-reference gaps, scoped rules, and relevant Sestacon ADRs/contracts so the agent edits accurately on the first attempt.  
* **Review (`review_change`, `review_pull_request`):** Runs after code edits. Combines Fast Path evidence with Extended Path diagnostics (Semgrep, CodeQL, reviewdog/SARIF), validates every candidate finding against exact diff lines and call paths (rejecting unsupported claims), deduplicates findings, ranks by severity/confidence, and emits a Rule Coverage Audit (`Applied`, `Not applicable`, `Unable to evaluate`, `Conflict`, `Missing reference`).

---

## 6\. Sestacon: Persistent Project Knowledge & Drift Model

Sestacon remains an independent product focused exclusively on non-code project knowledge, accessed by AI agents strictly via `dylext-gateway`:

* **Three Knowledge Lifecycles (Milestone 4):**  
  1. **Approved Knowledge (Authoritative):** Approved ADRs, requirements, acceptance criteria, API/integration contracts, and engineering standards. Included by default in implementation and review context (and supported in basic form in Milestone 1A).  
  2. **Working Knowledge (Unapproved):** Architecture drafts, proposed designs, trade-off analyses, and AI-generated intermediate notes. Supports multi-session continuation (`save_working_document` / `update_working_document`) and requires explicit promotion (`promote_document`) to become authoritative.  
  3. **Historical Knowledge:** Superseded ADRs, rejected proposals, and legacy designs preserved for context without polluting active constraints.  
* **Two-Level Context Availability & Session Bootstrap:**  
  * **Level 1 (Automatic Project Context via Host Bootstrap):** `dylext-gateway` exposes `get_project_context` as a high-level capability. The AI host invokes it at session start through its supported startup integration (e.g., session-start hook or MCP startup instruction); the Gateway resolves the local workspace to `repo_id` and retrieves a compact summary of approved ADRs, core constraints, and retrieval pointers (with document versions and approval status) from Sestacon without maintaining hidden conversation state.  
  * **Level 2 (On-Demand Task Context):** Targeted sections retrieved for active symbols/paths (`search_knowledge` / `get_document`, or internally during Dylext context assembly) with document ID, version, approval state, and exact citations.

---

## 7\. Shared Identity & Cross-System Integration Contract

Dylext and Sestacon maintain separate storage and separate transaction boundaries (no shared database in either local or remote deployments), coordinated through a canonical three-level identity model and explicit query/event contracts:

### Three-Level Canonical Identity

1. **Repository Identity (`repo_id`):** Logical repository identifier independent of local filesystem paths.  
2. **Revision Identity (`revision` / `snapshot_id`):** Exact Git commit SHA or working-tree snapshot identifier.  
3. **Symbol Identity (`symbol_uri`):** Dylext-owned canonical URI (e.g., `repo://<project-id>/src/payment/PaymentService.java#PaymentService.process(PaymentRequest)`), mapped internally to language-provider or SCIP identifiers so renames and provider swaps do not break cross-system links.

### Integration Contracts

* **Sync Context Query (`Dylext / Gateway -> Sestacon`):** Queries Sestacon with `repo_id`, `revision`, `target_symbols[]`, `target_paths[]`, `task_intent`, `token_budget`, and `include_working_drafts` (default `false` in reviews). Sestacon returns ranked sections with `doc_id`, `doc_version`, `approval_status`, `staleness_status` (`fresh` \| `potentially_stale` \| `staleness_unverified`), `stale_reason`, and `omitted_count`.  
  * **Milestone 1A Freshness Honesty:** Because automated event-driven staleness propagation ships in Milestone 4, Milestone 1A Sestacon responses explicitly mark freshness as `staleness_unverified` rather than implying active drift tracking.  
* **Async Change Event Contract (`repo.index.updated` — Contract Defined Now, Durable Outbox Delivered in Milestone 4):**  
  * **Contract Schema:** `event_id` (UUIDv7), `repo_id`, `base_revision`, `head_revision`, `snapshot_id`, `changed_files[]`, `changed_symbols[]`, and `renamed_symbols[]`.  
  * **Phased Delivery:** The schema is locked now so symbol bindings are compatible from Milestone 1A, while durable outbox delivery, automated `potentially_stale` marking, and `last_processed_snapshot_id` gap catch-up reconciliation are implemented in Milestone 4\.

---

## 8\. Agent-Facing MCP Surface (Exclusively via `dylext-gateway`)

`dylext-gateway` is the sole agent-facing MCP server. Agents never connect directly to internal Dylext engines or a separate Sestacon MCP server:

* **Gateway Capabilities Routed to Dylext Core:**  
  * `prepare_implementation_context`: Assembles pre-edit architectural, dependency, rule, and test-gap context for a task or target scope.  
  * `analyze_impact`: Traces callers, blast radius, interface implementations, contracts, and test references for target symbols or files.  
  * `review_change`: Evaluates a commit or dirty working-tree diff against scoped rules, structural impact, and optional diagnostics.  
  * `review_pull_request`: Evaluates cumulative PR diffs (`merge-base..head`) enforcing base-branch trusted rule policies.  
  * `explain_symbol`: Returns canonical signature, relationships, contracts, and linked documentation for a `symbol_uri`.  
  * `get_evidence`: Stateless progressive drill-down retrieving omitted evidence items by `analysis_id` and `evidence_ids[]`.  
* **Gateway Capabilities Routed to Sestacon:**  
  * `get_project_context`: Session-bootstrap Level-1 retrieval of approved ADRs, constraints, and knowledge pointers.  
  * `search_knowledge`: Hybrid lexical/semantic search across scoped project documentation.  
  * `get_document`: Retrieves specific document sections and version metadata by `doc_id`.  
  * **Milestone 4 Draft Lifecycle Tools:** `save_working_document`, `update_working_document`, and `promote_document`.  
* **Unified Analysis Result Contract:** Every Dylext analysis tool returns a revision-stamped envelope: Analysis ID (`analysis_id`), Scope, Change, Code Impact, Project Knowledge, Rules, Diagnostics, Findings, Coverage, and Limitations.

---

## 9\. Architectural Decision Records & Operational Invariants

| ID | Decision / Invariant | Milestone |
| :---- | :---- | :---- |
| **ADR-01** | Dylext is the sole owner of canonical `repo://` symbol identities and the primary code graph. | 1A |
| **ADR-02** | External LSP (live resolution) and SCIP (pre-built compiler index) tools are optional Relationship Evidence Providers behind a normalized adapter boundary—never generic fallbacks. | Later |
| **ADR-03** | Context ranking uses task relevance, structural proximity, evidence confidence, tests/contracts, rules/ADRs, and token cost; graph centrality (PageRank) is an auxiliary signal only. | 1A |
| **ADR-04** | Static test references, test execution, runtime coverage, and test failures remain strictly separate evidence categories. | 1A |
| **ADR-05** | External code-graph tools (`codebase-memory-mcp`, `code-review-graph`) are optional secondary evaluation comparators in benchmarks, not production dependencies. | 1A (opt) |
| **ADR-06** | Deterministic static analysis engines (Semgrep, CodeQL), structural rule evaluators (`ast-grep`), and diagnostic normalizers (reviewdog, SARIF) are optional Extended Path adapters. | 2+ |
| **ADR-07** | Expensive external analysis must never block Fast Path responses. | 1A |
| **ADR-08** | `dylext-gateway` is the exclusive agent-facing integration boundary for both Dylext and Sestacon capabilities. | 1A |
| **ADR-09** | **Code embeddings are an optional *grounded recall provider*, never a retrieval authority.** Semantic vector search over code (symbol bodies/doc-comments keyed by `symbol_uri`) may be added to improve recall for fuzzy intent with no lexical match; every hit MUST be resolved back to a canonical `symbol_uri` and verified against the graph before entering an evidence bundle (embed→resolve→verify, §18.E). It runs on the Extended Path / async so it never blocks the Fast Path budget (ADR-07), and it never returns raw chunks as truth. Reuses Sestacon's existing vector pipeline or a parallel code-vector store; feeds ADR-03 ranking as one signal. | Later (Extended Path) |
| **ADR-10** | **Agent tool-routing policy is advisory, not enforced.** The Gateway cannot and does not forbid native tools (`grep`/`read`/`glob`/editor LSP); doing so is neither possible nor desirable. The architecture ships an agent-facing usage policy (auto-loaded steering/skill, §26) that routes each need to the tool giving the **most precise answer at least token cost**, and native tools stay first-class for unindexed content, raw text, and mutations. Effectiveness-by-routing, not prohibition. | 1A |
| **INV-01** | **Revision & Snapshot Consistency:** Every result records an immutable `analysis_id` alongside the exact code revision and document versions used. | 1A |
| **INV-02** | **Partial Failure Resilience:** An unavailable optional analyzer or Sestacon timeout degrades gracefully with an explicit limitation notice rather than failing the request. | 1A |
| **INV-03** | **Epistemic Completeness:** Unresolved static relationships, unverified staleness, and budget-omitted items are reported explicitly, never silently dropped. | 1A |
| **INV-04** | **Cache Validity:** Cached evidence is valid only while its repository revision and transitive symbol dependencies remain unchanged. | 1B |
| **INV-05** | **Freshness-on-Read:** Every cacheable read (`outline`, `search`, `get_digest`, `expand`, `impact_of`, `repo_overview`, `analyze_impact`, `prepare_implementation_context`) MUST ensure the serving snapshot still matches live `HEAD`/`worktree_fingerprint` *before* returning; a result is never served from a snapshot the working tree has moved past. (No single read may skip this gate — the failure class is per-tool.) | 1B |
| **INV-06** | **Non-Revision Invalidation:** Any state change that alters a tool's output without moving the Git revision (e.g. signal/provider re-resolution, index-generation upgrade, manual re-index) MUST invalidate affected cached reads; cache keys bound solely to `(tool, args, revision)` are insufficient because such changes never move the key. | 1B |
| **INV-07** | **Degraded-Source Honesty:** When a required parser/provider is unreachable or a file is skipped, affected symbols/relationships are reported as a distinct `parser_unreachable` / `degraded` limitation — never silently stored as an empty shell that reads as "no symbols found" (an extension of INV-03). | 1B |

---

## 10\. Phased Roadmap & Milestone 1A Acceptance Criteria

* **Milestone 1A — Thin Vertical Slice (Current Target):** Local `dylext-gateway` (stdio MCP) routing to Dylext Core and Sestacon; clean committed `HEAD` only; Java \+ 1 mature Dylext language in SQLite WAL; basic Sestacon approved-doc retrieval (`staleness_unverified`); `prepare_implementation_context`, `analyze_impact`, `get_project_context`, and `analysis_id`\-backed `get_evidence`; 5–10 task smoke benchmark.  
* **Milestone 1B — Analysis Reliability:** Dirty working-tree overlay with graph edge invalidation, incremental index refresh, multi-directory Markdown review-rule discovery and conflict resolution, index recovery, and broader language coverage.  
* **Milestone 2 — Commit Review:** `review_change`, scoped rule evaluation, finding validation and deduplication, rule-coverage reporting, and optional Semgrep / reviewdog / SARIF ingestion.  
* **Milestone 3 — PR Review:** Cumulative merge-base vs. head PR diffing, trusted base-branch rule policy enforcement, instruction-tampering detection, and final-state finding validation.  
* **Milestone 4 — Sestacon Maturity:** Full approved/working/historical lifecycle, multi-session draft continuation, durable outbox `repo.index.updated` staleness propagation, and gap catch-up reconciliation.  
* **Milestone 5 — Scale & Remote Deployment:** Remote/team Streamable HTTP Gateway, centralized auth/multi-tenancy, independent centralized Dylext and Sestacon storage services, CI runners, additional agent hosts, runtime coverage / deep data-flow providers, and the optional grounded code-embedding recall provider (ADR-09, embed→resolve→verify).

### Milestone 1A Smoke-Test Acceptance Criteria (Must Pass Before Milestone 1B)

Evaluated across a 5–10 task smoke benchmark comparing Stock Claude Code vs. Claude Code \+ Dylext \+ Sestacon under identical models, prompts, starting revisions, and test suites (with optional secondary comparators per ADR-05):

1. **Fast Path Local Processing Latency:** `prepare_implementation_context` and `analyze_impact` p95 local processing time (Gateway \+ Dylext Core \+ local Sestacon lookup, excluding external LLM reasoning) $<300\text{ms}$ on a warm local index, with warm and cold latencies reported separately.  
2. **Token & Loop Efficiency:** $\ge 30\%$ reduction in total end-to-end session tokens (including retries/fixes) and $\ge 40\%$ reduction in pre-edit exploration tool calls (`Grep`, `Glob`, `Read`) compared to stock Claude Code across the 5–10 task smoke suite.  
3. **Engineering Quality Non-Regression (Smoke Check):** Task completion rate (passing build and test suite without manual intervention) $\geq$ baseline with no increase in rework or rollback rate across the smoke tasks.  
4. **Contract & Epistemic Compliance:** $100\%$ of responses pass through `dylext-gateway` and include `analysis_id`, `repo_revision`, `doc_versions`, configurable token-budget enforcement, and explicit `limitations` / `omitted` / `staleness_unverified` disclosures.  
5. **Context Sufficiency (the crux of the cost claim):** Across the smoke suite, $\ge 80\%$ of `prepare_implementation_context` / `analyze_impact` calls are **task-sufficient on the first response** — i.e. the agent completes the edit without issuing a follow-up `get_evidence` for omitted structural context *and* without falling back to `Grep`/`Glob`/`Read`. Under-inclusion (forcing a re-query) and over-inclusion (wasted tokens) are both tracked; the ranking (ADR-03) is tuned against this metric, because a "smallest sufficient" package that is not actually sufficient re-creates the discovery loop it was meant to remove.  
6. **Envelope Overhead Bound:** Median envelope+metadata overhead $\le 20\%$ of `tokens_used` (per §16.A), so the measured token reduction reflects delivered code context, not bookkeeping.

**Benchmark Methodology (so the cost claims are credible, not anecdotal):** Token counts use the host model's own tokenizer on full request+response transcripts; each task is run $\ge 5$ times per arm (stock vs. Dylext+Sestacon) under identical model/temperature/prompt/starting-revision to bound variance; results report per-task medians with an effect-size and a paired CI95 (not naked means), and a run is a regression if the CI lower bound crosses zero on any gating metric. A 5–10 task suite is explicitly a **smoke gate**, not a statistical proof; a larger suite is required before any external cost claim.

---

## 11\. System Component Architecture

Dylext \+ Sestacon is structured as a modular application with strict inward dependency rules. High-level orchestration coordinates domain services through explicit port interfaces; no domain module depends directly on MCP transport details, SQLite drivers, or external CLI tools.

### A. Component Topology & Dependency Flow

```
         [ AI Host: Claude Code / Kiro CLI / Codex / Cursor ]
                                  |
           MCP (stdio locally / Streamable HTTP remotely)
                                  v
+---------------------------------------------------------------------+
|                           dylext-gateway                            |
|  * MCP Protocol Adapter          * Workspace -> repo_id Resolver    |
|  * Request Validator & Budget    * Capability Router (Stateless)    |
+-----------------+---------------------------------+-----------------+
                  | (in-process / local RPC)        | (KnowledgeProvider port)
                  v                                 v
+-----------------------------------+   +-----------------------------+
|     Dylext Intelligence Core      |   |          Sestacon           |
|                                   |   |                             |
|  +-----------------------------+  |   |  * Document Registry        |
|  |    Analysis Orchestrator    |  |   |  * Lifecycle Manager        |
|  +-+------+------+------+----+-+  |   |  * Hybrid Index (FTS/V)     |
|    |      |      |      |    |    |   |  * Symbol Link & Drift      |
|    v      v      v      v    v    |   +-----------------------------+
|   Git    Code  Impact  Rule Review|
|  Change Graph  Engine  Res. Engine|
|    |      |      |      |    |    |
|    +------+------+------+----+    |
|                  |                |
|                  v                |
|           Context Builder         |
+------------------+----------------+
                   | (Port Interfaces)
    +--------------+-------------+------------------+
    v              v             v                  v
[GraphStore] [SnapshotStore] [ParserPort]   [DiagnosticsPort]
  (SQLite)      (SQLite)     (Java/Go/etc)  (Semgrep/SARIF/LSP)
```

### B. Module Responsibilities & Dependency Rules

* **`dylext-gateway` (Edge & Routing Layer):** Translates MCP tool calls into stateless domain commands, resolves local filesystem paths into logical `repo_id` values, enforces request timeouts and `token_budget` ceilings, and routes requests either to Dylext's Analysis Orchestrator or directly to Sestacon's `KnowledgeProvider` port.  
  * *Single Routing Model (resolves the §11/§15 overlap):* the Gateway applies exactly one rule — **direct knowledge tools** (`get_project_context`, `search_knowledge`, `get_document`, and the Milestone 4 draft-lifecycle tools) route straight to Sestacon's `KnowledgeProvider`; **analysis workflows** (`prepare_implementation_context`, `analyze_impact`, `review_change`, `review_pull_request`, `explain_symbol`) route to the Analysis Orchestrator, which is the **only** component that calls Sestacon internally (via `resolveContext`) to blend knowledge with code evidence. The Gateway never calls Sestacon on behalf of an analysis workflow, and the Orchestrator never serves direct knowledge tools. Both paths use the same `KnowledgeProvider` port.  
  * *Dependency Rule:* Depends only on application command/query DTOs and port interfaces; contains zero graph traversal, diff parsing, or SQLite logic.  
* **Analysis Orchestrator (Workflow Coordinator):** Owns end-to-end execution of Dylext workflows (`prepare_implementation_context`, `analyze_impact`, `review_change`, `review_pull_request`, `explain_symbol`, `get_evidence`). Selects Fast Path vs. Extended Path execution, dispatches independent sub-queries concurrently where safe, collects partial-failure limitations, and hands ranked candidates to Context Builder.  
  * *Dependency Rule:* Coordinates domain services; does not contain language-specific AST rules or raw storage queries.  
* **Domain Engines (Pure Domain Logic):**  
  * **Git Change Analyzer:** Computes normalized `ChangeSet` via `GitRepositoryPort`.  
  * **Code Intelligence & Graph Service:** Queries `GraphStore` and coordinates `ParserPort`.  
  * **Impact Analyzer:** Traverses `Confirmed`, `Potential impact`, `Unknown` edges and `StaticTestReference` links.  
  * **Review Rule Resolver:** Evaluates scoped Markdown rules and target-branch trust policies.  
  * **Review Engine:** Merges `DiagnosticsPort` outputs, validates findings against `ChangeSet` hunks, deduplicates, and computes rule coverage.  
  * **Context Builder:** Applies ADR-03 ranking, trims to `token_budget`, records omitted metadata, and persists immutable `AnalysisResult` records via `SnapshotStore`.  
* **Core Port Interfaces (Storage & Provider Abstraction):**  
  * `GraphStore`: Read/write canonical `Symbol` and `Relationship` records scoped by `snapshot_id`.  
  * `SnapshotStore`: Manages `Snapshot` lifecycle states and immutable `AnalysisResult` persistence.  
  * `KnowledgeProvider`: Synchronous context query and document retrieval contract with Sestacon.  
  * `ParserPort`: Language-specific AST extraction.  
  * `DiagnosticsPort`: Optional adapter boundary for Semgrep, CodeQL, reviewdog/SARIF, and selective LSP/SCIP providers.

---

## 12\. Core Domain Model

To keep module boundaries, SQLite persistence, and MCP payloads consistent without premature DDL lock-in, Dylext \+ Sestacon defines nine canonical domain entities. Every entity is immutable once persisted for a given `snapshot_id` or `analysis_id`.

> **Domain model is not frozen.** The `Relationship`, `Evidence`, and `ChangeSet` entities below are the Level 2 baseline; their refined Level 3 semantics — orthogonal relationship dimensions, evidence provenance/test-execution/coverage representation, and precise change mapping — are specified in §18 (Code Intelligence and Graph Semantics) and §21 (Evidence and Finding Model) and take precedence where they add detail.

### A. Entity Relationship Overview

```
Repository (1) -------< (N) Snapshot (1) -------< (N) File
                            |   |                   |
                            |   |                   v
                            |   |              Symbol (1) --< (N) Relationship
                            |   |                   |
                            |   +--< (1) ChangeSet -+ (affected_symbols)
                            |
                            +--< (N) AnalysisResult (1) --< (N) Evidence
                                             |
                                             +--< (N) Finding
```

### B. Canonical Entity Definitions

| Entity | Identity & Key Attributes | Ownership & Lifecycle Rules |
| :---- | :---- | :---- |
| **`Repository`** | `repo_id` (logical slug/hash independent of local path), `default_branch`, `detected_languages[]`, `config_hash` | Resolved at the `dylext-gateway` edge from local workspace path or remote repo URL. |
| **`Snapshot`** | `snapshot_id` (UUIDv7), `repo_id`, `base_revision` (commit SHA), `parent_revision`, `is_dirty` (`false` in M1A; `true` in M1B+ working-tree overlay), `worktree_fingerprint` (SHA-256 over the sorted set of `(repo_path, content_hash)` for tracked \+ staged \+ unstaged files; `null` when `is_dirty = false`), `status` (`building` \| `ready` \| `failed`), `created_at` | Owned by `SnapshotStore`. Readers only query snapshots in `ready` state. **Snapshot identity \= `(repo_id, base_revision, worktree_fingerprint)`**, not `(repo_id, base_revision, is_dirty)`: two successive dirty edits share one `base_revision` and both have `is_dirty = true` but are distinct repository states, so `worktree_fingerprint` is what disambiguates them and keys cache reuse (supersedes the `is_dirty`\-only key used elsewhere in this document). |
| **`File`** | `file_id`, `snapshot_id`, `repo_path` (POSIX relative path), `language`, `content_hash` (SHA-256), `parse_status` (`parsed` \| `skipped_binary` \| `skipped_generated` \| `parse_error`) | Scoped to a `Snapshot`. Unchanged files across revisions carry forward their `content_hash` for incremental reuse (M1B). |
| **`Symbol`** | `symbol_uri` (canonical `repo://<repo_id>/<path>#<qualified_sig>`), `snapshot_id`, `file_id`, `kind` (`class` \| `interface` \| `method` \| `function` \| `field` \| `endpoint`), `qualified_name`, `span` (`start_line`, `end_line`), `visibility`, `is_test_symbol` | Owned exclusively by Dylext (ADR-01). External LSP/SCIP IDs are stored in a separate provider-mapping table, never replacing `symbol_uri`. |
| **`Relationship`** | `edge_id`, `snapshot_id`, `source_symbol_uri`, `target_symbol_uri`, `edge_type` (`CALLS` \| `IMPLEMENTS` \| `EXTENDS` \| `IMPORTS` \| `TESTS` \| `EXPOSES_ENDPOINT`), `resolution_status` (`Confirmed` \| `Potential impact` \| `Unknown`), `provider` (`dylext_ast` \| `lsp` \| `scip`), `confidence` (`0.0–1.0`) | `Confirmed` denotes confirmed static/source-level resolution. Polymorphic/DI candidates carry `Potential impact`; unresolved calls carry `Unknown` with a target signature stub. **Normative note: this three-value `resolution_status` is the backward-compatible projection of the four orthogonal dimensions defined in §18.B (`edge_type` / `resolution_status` / `impact_certainty` / `evidence_provenance`). Implementations MUST persist the §18.B dimensions and derive this field (`Confirmed` = `resolved_concrete`+`direct`; `Potential impact` = `resolved_interface`|DI + `potential`; `Unknown` = `unresolved_*` + `speculative`). §18.B takes precedence on any conflict.** |
| **`ChangeSet`** | `changeset_id`, `repo_id`, `base_revision`, `head_revision`, `snapshot_id`, `mode` (`commit` \| `working_tree` \| `pr_cumulative`), `changed_files[]` (`path`, `change_type`, `hunks[]`), `affected_symbol_uris[]` | Produced once by Git Change Analyzer per request so Impact, Rule, and Review engines evaluate identical symbol spans. |
| **`Evidence`** | `evidence_id`, `analysis_id`, `category` (`caller` \| `callee` \| `test_ref` \| `test_exec` \| `runtime_coverage` \| `rule` \| `doc` \| `diagnostic`), `subject_uri`, `location` (`repo_path`, `start_line`, `end_line`), `revision`, `source_provider`, `freshness` (`verified` \| `potentially_stale` \| `unverified`), `token_cost`, `included_in_initial_response` (`boolean`) | Atomic traceable claim backing every item in an `AnalysisResult`. Items with `included_in_initial_response = false` remain retrievable via `get_evidence(analysis_id, evidence_ids[])`. |
| **`AnalysisResult`** | `analysis_id` (UUIDv7), `repo_id`, `snapshot_id`, `workflow` (`prepare_implementation_context` \| `analyze_impact` \| `review_change` \| `review_pull_request` \| `explain_symbol`), `token_budget`, `tokens_used`, `evidence_ids[]`, `omitted_summary[]`, `limitations[]`, `expires_at` | Persisted immutably in `SnapshotStore` under a bounded local TTL/ring retention policy to keep `dylext-gateway` stateless across progressive `get_evidence` calls. |
| **`Finding`** | `finding_id`, `analysis_id`, `rule_id` (repo Markdown rule or diagnostic rule), `severity` (`high` \| `medium` \| `low` \| `info`), `location` (`repo_path`, `start_line`, `end_line`), `symbol_uri`, `evidence_ids[]`, `validation_status` (`verified_on_diff` \| `rejected_off_diff` \| `unverified`), `confidence` | Emitted only by Review workflows (`review_change`, `review_pull_request`). Findings lacking diff-line or structural `evidence_ids[]` are rejected before output. |

---

## 13\. Storage Architecture

Dylext and Sestacon maintain strictly independent storage engines, schemas, and transaction boundaries (INV-01 through INV-04). In local-first mode, both run with zero external database servers; in future remote mode (Milestone 5+), each scales behind its own storage service without sharing tables or cross-system transactions.

### A. Dylext Logical Storage Model (`GraphStore` & `SnapshotStore`)

In local-first mode, Dylext persists state in a single local SQLite database configured in WAL (Write-Ahead Logging) mode (`PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA foreign_keys=ON;`) located under the user's local cache/data directory (never polluting the Git working tree).

| Logical Store / Partition | Stored Entities & Logical Structure | Key Access Patterns & Indexing |
| :---- | :---- | :---- |
| **`repositories` & `snapshots`** | Repository metadata \+ immutable `Snapshot` records (`snapshot_id`, `repo_id`, `base_revision`, `parent_revision`, `is_dirty`, `worktree_fingerprint`, `status`, `created_at`). | Lookup latest `ready` snapshot by `(repo_id, base_revision, worktree_fingerprint)` (clean snapshots use `worktree_fingerprint = null`). Atomic status transition (`building -> ready`) publishes a new graph snapshot. |
| **`files` (Content-Addressable Manifest)** | `File` records scoped by `snapshot_id` (`repo_path`, `language`, `content_hash`, `parse_status`). | Fast diffing between `parent_revision` and `base_revision` via `(snapshot_id, repo_path)` and `content_hash` reuse during incremental updates (M1B). |
| **`symbols` & `provider_mappings`** | Canonical `Symbol` nodes (`symbol_uri`, `snapshot_id`, `file_id`, `kind`, `qualified_name`, `start_line`, `end_line`, `is_test_symbol`) \+ separate `provider_mappings` (`provider`, `external_id -> symbol_uri`). | Point lookup by `(snapshot_id, symbol_uri)`, range lookup by `(snapshot_id, file_id, start_line, end_line)`, and prefix search on `qualified_name`. |
| **`relationships` (Directed Graph Edges)** | `Relationship` edges (`edge_id`, `snapshot_id`, `source_symbol_uri`, `target_symbol_uri`, `edge_type`, `resolution_status`, `provider`, `confidence`). | Covered composite indexes on `(snapshot_id, target_symbol_uri, edge_type)` for reverse caller/blast-radius traversal and `(snapshot_id, source_symbol_uri, edge_type)` for outgoing dependencies. |
| **`analysis_results` & `evidence_items`** | Immutable `AnalysisResult` envelopes and normalized `Evidence` records keyed by `analysis_id` with `expires_at` TTL. | Point lookup by `(analysis_id, evidence_id)` for stateless `get_evidence` drill-down; background/opportunistic sweep deletes expired rows past `expires_at`. |

### B. Sestacon Logical Storage Model (`KnowledgeProvider`)

Sestacon owns its independent local store (SQLite FTS5 \+ local vector/metadata store locally; independent PostgreSQL/pgvector service in Milestone 5+):

| Logical Store / Partition | Stored Entities & Logical Structure | Key Access Patterns & Lifecycle Rules |
| :---- | :---- | :---- |
| **`documents` & `document_versions`** | Logical `doc_id`, `repo_id`, `doc_type` (`adr` \| `requirement` \| `contract` \| `standard` \| `draft`), immutable version snapshots (`doc_version`, `content`, `approval_status`: `approved` \| `working` \| `historical`, `author`, `created_at`). | Default queries filter `approval_status = 'approved'`; working drafts require explicit opt-in (`include_working_drafts = true`) or explicit `promote_document` transition. |
| **`knowledge_index` (Hybrid Retrieval)** | Chunked sections linked to `(doc_id, doc_version)` with lexical full-text index (FTS5) and optional dense embeddings. | Hybrid lexical \+ semantic retrieval scoped by `repo_id` and `approval_status`. |
| **`symbol_links` & `drift_state`** | Explicit bindings between `(doc_id, doc_version)` and canonical Dylext `symbol_uri` / `repo_path` patterns, plus `staleness_status` (`fresh` \| `potentially_stale` \| `staleness_unverified`), `stale_reason`, and `last_verified_revision`. | Reverse lookup by `symbol_uri` or `repo_path` during `resolveContext` and when processing `repo.index.updated` events (Milestone 4). |
| **`sync_watermarks` & `inbox_dedup`** | Per-repository `last_processed_snapshot_id` watermark and processed `event_id` deduplication log (Milestone 4). | Guarantees idempotent event consumption and detects snapshot gaps (`staleness_unverified`) after crashes or branch rebases. |

### C. Cache Invalidation, Schema Migrations & Corruption Recovery

* **Snapshot-Scoped Cache Invalidation (INV-04):** Graph queries and cached evidence never mutate rows in place for a published `ready` snapshot. Any new commit (`HEAD` change in M1A) or dirty file edit (M1B) produces a new `snapshot_id`, naturally invalidating stale revision caches.  
* **Retention & Compaction:** Old `Snapshot` partitions (beyond the last $N$ commits or active branch heads) and expired `AnalysisResult` rows are pruned asynchronously so the local SQLite file stays compact.
  * *Evidence/Snapshot Retention Coupling (resolves ST-03):* `Evidence` rows MUST be **self-contained** — they persist resolved text spans, locations, and provenance at analysis time, never a lazy pointer that requires its originating `Snapshot` to still exist. Consequently `get_evidence` keeps working for the full `analysis_id` TTL even after the source snapshot is pruned. The compactor additionally MUST NOT prune a `Snapshot` while any live (non-expired) `analysis_id` still references it; the two retention clocks (`Snapshot` pruning and `AnalysisResult.expires_at`) are ordered so snapshot pruning respects live analysis references.
  * *Dirty-Snapshot Churn Bound (resolves ST-07):* because `worktree_fingerprint` changes on every editor save, dirty-overlay snapshots are capped per `repo_id` by a bounded LRU (default: keep the newest $K$ dirty snapshots, e.g. 8) and saves are coalesced within a debounce window (default 500 ms) before a new overlay is built. Evicted dirty snapshots are discarded immediately (they are cheap to rebuild from the working tree); committed (`is_dirty = false`) snapshots follow the commit-based retention above. This bounds snapshot/fingerprint cardinality under rapid saves so the local store cannot grow without limit during an edit session.  
* **Forward-Only Migrations & Rebuildable Index:** Both Dylext and Sestacon track explicit `schema_version` pragmas/tables with forward-only migrations. The "rebuild without data loss" guarantee applies **only to Dylext's SQLite database, which is a derived index over Git** — any schema mismatch, interrupted write, or file corruption triggers an automatic quarantine (`*.corrupt`) and clean rebuild from `HEAD`, because every byte can be regenerated from the repository. **Sestacon's store is categorically different: it holds user-authored, non-derived knowledge (ADRs, requirements, contracts, working drafts) that cannot be regenerated from Git.** Sestacon therefore must never apply quarantine-and-rebuild-from-source as a recovery path; its durability model is backup/restore and forward-only migration with verification, and a corruption event there is an operational incident requiring restore from a prior version, not disposal.

---

## 14\. Indexing and Graph Lifecycle

Because Dylext's preflight and impact accuracy depend on trustworthy symbol graphs, indexing is modeled as an explicit, snapshot-scoped lifecycle that never mutates an active `ready` snapshot in place.

### A. Indexing Triggers & Snapshot Lifecycle

* **Initial Indexing:** Triggered on first workspace access for a `repo_id` at the target Git revision (`HEAD` in Milestone 1A) when no `ready` snapshot exists for `(repo_id, base_revision, worktree_fingerprint)`.  
* **Committed Revision Indexing (Milestone 1A):** Triggered when `HEAD` advances to a new commit SHA (`base_revision`). A new `Snapshot` row is created in `building` status, populated with `File`, `Symbol`, and `Relationship` records, and atomically transitioned to `ready` once complete.  
* **Dirty Working-Tree Overlay (Milestone 1B):** When uncommitted changes exist in staged or unstaged files, Dylext builds an ephemeral or overlay snapshot (`is_dirty = true`) anchored to `base_revision` (`HEAD`), re-parsing only modified/added files and invalidating stale edges touching changed symbols/files.  
* **Reader Isolation:** Concurrent Fast Path queries continue reading the latest `ready` snapshot while a new snapshot is in `building` status; if no `ready` snapshot exists on cold start, queries wait briefly with a bounded timeout or return a `building` limitation notice (INV-02).
* **Target-First Partial-Ready Serving (resolves ST-06):** On a cold first index of a large repo (where a full build can take tens of seconds), the indexer prioritizes the **target file's neighborhood first** (the requested symbols/paths and their immediate imports/callers) and publishes an interim `symbols_ready, edges_pending` view so the Fast Path can answer with `status: "partial"` + `index_partial` limitation instead of forcing the agent to wait or fall back to `grep` (which would re-create the cost loop the product exists to remove). Edge-dependent answers (full blast radius) are marked partial until the background job completes and promotes the snapshot to `ready`. This keeps the first interactive call useful on cold start; the agent is told explicitly that impact is still being computed.

### B. Parser Execution, Normalization & Relationship Resolution

* **File Discovery & Filtering:** Enumerates tracked repository files (and dirty files in M1B), skipping or tagging binary files (`skipped_binary`), generated files (`skipped_generated`), and oversized/ignored paths.  
* **Parser Selection & Execution:** Dispatches files to the matching language parser (`ParserPort` — Java and secondary mature language in M1A; expanding in M1B+) with per-file timeouts and error isolation so a single syntax/parse error marks that file `parse_error` without aborting the snapshot.  
* **Canonical Normalization:** Maps AST declarations and references into canonical `Symbol` (`repo://...`) and `Relationship` records, tagging `is_test_symbol` and recording static resolution status (`Confirmed`, `Potential impact`, `Unknown`).  
* **Unresolved & Polymorphic Symbols:** Unresolved external or dynamic calls are recorded as `Unknown` (or stubbed `symbol_uri` targets) rather than silently dropped (INV-03). Ambiguous interface/override or dependency-injection dispatch is recorded as `Potential impact` unless resolved by an optional provider (ADR-02).

### C. Incremental Updates, Git History Shifts & Recovery

* **Incremental Reuse (Milestone 1B):** Compares file `content_hash` (SHA-256) against the prior snapshot; unchanged files reuse prior `File` and `Symbol` extractions, while changed/deleted files refresh affected nodes and incoming/outgoing edges.  
  * *Immutable Snapshot Membership:* every `File`, `Symbol`, and `Relationship` row belongs to exactly one `snapshot_id` and is immutable for the life of that snapshot. "Reuse" therefore means the new snapshot **re-references content-addressed extraction keyed by `content_hash`** (and, where materialized per-snapshot, copies the rows under the new `snapshot_id`) — it never shares or mutates rows owned by an older snapshot. Extraction artifacts are stored content-addressably so identical file content across snapshots is extracted once and bound into each snapshot's membership; deleting or compacting an old snapshot can never invalidate a newer one, and a reader resolving a `symbol_uri` always sees a consistent, single-snapshot view.  
* **Branch Switch, Rebase & Force-Push:** Because snapshots are keyed by `(repo_id, base_revision, worktree_fingerprint)` rather than assuming linear commit history, switching branches, rebasing, or force-pushing simply resolves the new commit SHA and looks up or builds the corresponding `Snapshot`—without corrupting prior snapshots.  
* **Failure & Corruption Recovery:** If indexing fails or times out, the `Snapshot` transitions to `failed` with diagnostic error details and never becomes visible as `ready`. If SQLite detects corruption or an incompatible schema version, Dylext quarantines the file (`*.corrupt`) and rebuilds cleanly from Git (INV-02, INV-04) — this disposal-and-rebuild path is valid **only** because the Dylext index is derived; it is never applied to Sestacon's user-authored store (see §13.C).

---

## 15\. Workflow Architecture

All agent-facing workflows execute through `dylext-gateway` using explicit, stateless inputs (`workspace_path` / `repo_id`, `task_intent` or `change_scope`, `token_budget`, and `execution_mode`). The Analysis Orchestrator coordinates conditional branching, safe parallel fan-out, snapshot cache hits, and graceful partial-failure degradation (INV-01 through INV-04).

### A. Preflight Workflow: `prepare_implementation_context` (Fast Path)

```
AI Host             dylext-gateway         Analysis Orchestrator      Domain Engines & Sestacon
  |                      |                          |                          |
  |- prepare_impl_ctx -->|                          |                          |
  |- resolve repo & HEAD>|                          |                          |
  |- lookup ready Snap ->| (SnapshotStore)          |                          |
  |                      | [if missing: build/wait] |                          |
  |- resolve symbols --->| (Code Graph / ChangeSet) |                          |
  |                      |                          |                          |
  |                      |                          |+-> Impact Analyzer       | [Parallel]
  |                      |                          ||   (callers, test_refs)  |
  |                      |                          |+-> Rule Resolver (M1B+)  | [Parallel]
  |                      |                          |+-> Sestacon.resolveCtx   | [Parallel, bounded]
  |                      |                          |    (Approved docs/ADRs)  |
  |                      |                          |                          |
  |- rank & pack budget >| (Context Builder)        |                          |
  |- persist Result ---->| (SnapshotStore -> id)    |                          |
  |<- Envelope(id) ------|<-------------------------|                          |
```

* **Conditional & Parallel Execution:** Reuses the cached `ready` snapshot if `(repo_id, base_revision, worktree_fingerprint)` matches; otherwise triggers indexing before proceeding. Once target symbols/files are resolved, Impact Analyzer, Review Rule Resolver (Milestone 1B+), and `Sestacon.resolveContext` execute concurrently over immutable inputs (`snapshot_id`, `target_symbols[]`, `target_paths[]`). If Sestacon times out or is unconfigured, Analysis Orchestrator records `sestacon_unavailable` in `limitations[]` and proceeds rather than failing (INV-02).

### B. Blast-Radius Workflow: `analyze_impact` (Fast Path)

1. **Gateway Entry:** Resolves workspace to `repo_id` and target revision (`HEAD` in M1A; dirty overlay in M1B+), validating target `symbol_uri` or `repo_path` inputs.  
2. **Graph & Contract Traversal:** Impact Analyzer queries `GraphStore` for direct and transitive incoming callers (`Confirmed` vs. `Potential impact` vs. `Unknown`), interface implementations, exposed endpoints/contracts, and `StaticTestReference` links.  
3. **Optional Knowledge Enrichment:** Concurrently queries Sestacon for symbol-linked ADRs or contracts within budget.  
4. **Budgeted Packaging:** Context Builder orders impacted nodes by structural distance, confidence, and test-reference gaps, trims to `token_budget`, persists an immutable `AnalysisResult` (`analysis_id`), and returns explicit `omitted` and `limitations` disclosures.

### C. Post-Edit Review Workflows: `review_change` & `review_pull_request`

1. **ChangeSet Construction (Git Change Analyzer):** `review_change` (Milestone 2\) builds `ChangeSet` for a commit (`HEAD` vs. parent) or dirty working tree (`is_dirty = true`). `review_pull_request` (Milestone 3\) builds cumulative `ChangeSet` from `merge-base` to `head` and loads trusted review rules exclusively from the base branch, flagging any head-branch rule edits separately as proposed policy modifications.  
2. **Parallel Evidence & Diagnostics Collection:** Fast Path branch runs Impact Analyzer (affected callers and static test-reference gaps), Review Rule Resolver, and `Sestacon.resolveContext` (`include_working_drafts = false` by default). Extended Path branch (optional/async/cached) ingests SARIF/reviewdog reports or runs configured static analyzers (Semgrep, CodeQL) bounded by strict timeouts.  
3. **Diff-Grounded Finding Validation & Rule Audit (Review Engine):** Filters candidate findings against `ChangeSet` modified hunks and verified call paths (`verified_on_diff` vs. `rejected_off_diff`), deduplicates overlapping diagnostics, and emits a Rule Coverage Audit (`Applied`, `Not applicable`, `Unable to evaluate`, `Conflict`, `Missing reference`).  
4. **Immutable Persistence:** Saves `AnalysisResult` \+ `Finding` \+ `Evidence` under `analysis_id` for deterministic `get_evidence` follow-ups.

### D. Sestacon Context Retrieval & Staleness Reconciliation Flow

* **Session Bootstrap (`get_project_context`):** Invoked by the AI host at session start through `dylext-gateway`; returns Level-1 project overview, active approved ADRs, and `doc_version` metadata without Gateway session state.  
* **Task Context Query (`resolveContext`):** Queries approved documents matching `target_symbols[]`, `target_paths[]`, and `task_intent` within `token_budget`, returning `staleness_status` (`staleness_unverified` in M1A–M3).  
* **Asynchronous Drift Reconciliation (Milestone 4):** When Dylext publishes a new `ready` snapshot, it writes a `repo.index.updated` event to its local outbox. Sestacon consumes the event idempotently (`event_id`), re-binds renamed `symbol_uri` links, marks affected documents `potentially_stale` with `stale_reason`, and advances `last_processed_snapshot_id` (flagging `staleness_unverified` if a snapshot gap is detected).

---

## 16\. Gateway and Internal API Contracts

`dylext-gateway` exposes a single, stateless tool surface over MCP (stdio locally; Streamable HTTP remotely) and translates every call into typed domain commands routed to Analysis Orchestrator or `KnowledgeProvider`.

### A. Unified Request & Response Envelope

* **Standard Request Context:** Every tool invocation carries explicit `workspace_path` (resolved by the Gateway to `repo_id`), optional `revision` (defaults to current `HEAD` in M1A; active workspace state in M1B+), `token_budget` (default configured per tool, capped by Gateway ceiling), and `timeout_ms`.  
* **Standard Response Envelope:** All Dylext analysis responses share a consistent top-level structure: `analysis_id` (UUIDv7), `repo_id`, `repo_revision`, `is_dirty`, `doc_revisions[]`, `status` (`"complete"` \| `"partial"` \| `"building"` \| `"failed"`), `payload` (tool-specific structured evidence), `tokens_used` / `token_budget`, `omitted` (summary of excluded evidence categories/counts and `evidence_ids[]`), and `limitations[]` (`unresolved_dynamic_edges`, `sestacon_unavailable`, `staleness_unverified`, `extended_analyzer_timeout`, `index_building`, `index_partial`, `revision_superseded`).
* **Revision Supersession Signal (resolves ST-02):** A result is always bound to the immutable `snapshot_id` it was computed against. If `HEAD` / the working tree advances *while* an analysis is in flight (or between a parent call and a later `get_evidence`), the response carries a `revision_superseded` limitation naming the newer `repo_revision`, so the agent knows its evidence is one or more revisions stale and can re-run if correctness depends on the latest state. The result itself stays internally consistent (it is never a mix of two revisions); supersession is advisory, not a failure.
* **Envelope Token Accounting (Cost-Thesis Guard):** Because the product's value is net token reduction, the envelope and per-`Evidence` metadata (ids, provenance, locations, `token_cost`, `limitations`, `omitted`) are **not free** and MUST be counted against `token_budget`, not excluded from it. The contract enforces: (1) a configurable `envelope_overhead_ceiling` (default: metadata ≤ 20% of `tokens_used`) above which the Gateway emits a `metadata_heavy` limitation so regressions are visible; (2) provenance is emitted **compactly by default** (stable short ids + a single location) with verbose provenance fetched only via `get_evidence`; (3) a `verbosity` request flag (`compact` default | `full`) controls envelope richness. A response whose metadata would exceed the ceiling trims optional provenance before trimming payload evidence, so precise code context is never sacrificed to bookkeeping.
* **Drill-Down Round-Trip Economics:** Each `get_evidence` round-trip re-pays the host's framing/system-prompt tokens, so progressive disclosure can *add* cost if initial budgets are set too lean. The contract therefore requires the initial `payload` to target *task-sufficiency* (see §10 criterion 5), batches drill-down via `evidence_ids[]` (never one id per call), and reports an `expected_followups` hint so a host can widen the initial budget instead of paying N round-trips.

### B. Progressive Retrieval (`get_evidence`) & Retention Semantics

* **Request:** `get_evidence(workspace_path, analysis_id, evidence_ids[], max_tokens)`  
* **Resolution & Persistence:** `dylext-gateway` performs zero in-memory session lookup; Analysis Orchestrator loads the persisted `AnalysisResult` and requested `Evidence` rows directly from SQLite (`SnapshotStore`).  
* **Retention & Process Restart Behavior:** Because `AnalysisResult` and `Evidence` are persisted in SQLite with an `expires_at` TTL (default 24 hours, bounded by a configurable max-row ring buffer), `get_evidence` continues working seamlessly across `dylext-gateway` process restarts until the record expires or is compacted. If an expired or unknown `analysis_id` is requested, the Gateway returns a structured `ANALYSIS_EXPIRED` error instructing the agent to re-run the parent analysis tool.

### C. Canonical Error Model

| Error Code | Condition & Meaning | Agent / Host Recovery Action |
| :---- | :---- | :---- |
| **`INVALID_SCOPE`** | Workspace is not a valid Git repository or target `symbol_uri` / `repo_path` does not exist at `repo_revision`. | Verify workspace path, Git status, or symbol URI spelling before retrying. |
| **`INDEX_NOT_READY`** | Cold-start indexing exceeded synchronous wait budget; response includes retry hint and partial metadata where available. | Wait briefly and retry the tool call; background indexing continues to completion. |
| **`ANALYSIS_EXPIRED`** | Referenced `analysis_id` is outside the local TTL or ring-buffer retention window. | Re-run the parent analysis tool (e.g., `prepare_implementation_context`) to obtain a fresh `analysis_id`. |
| **`BUDGET_EXCEEDED`** | Requested minimum required payload cannot fit inside an overly restrictive `token_budget`. | Retry with a larger `token_budget` or narrow the target symbol/file scope. |
| **`INTERNAL_ERROR`** | Unexpected runtime fault (logged with stack trace to stderr; never corrupts stdout JSON-RPC framing). | Inspect stderr logs or fall back to standard local file inspection. |

---

## 17\. Execution, Concurrency, and Failure Model

### A. Concurrency & SQLite Read/Write Isolation

* **Concurrent Agent Requests:** Multiple read/analysis requests (`prepare_implementation_context`, `analyze_impact`, `get_evidence`) execute concurrently against any `ready` snapshot without blocking each other.  
* **Single-Writer Indexing Serialization:** Per `repo_id`, at most one indexing job (`Snapshot` in `building` status) runs at a time via a per-repository write lock, preventing duplicate work when multiple agent tool calls arrive simultaneously on a cold cache.  
* **SQLite WAL Contention Control:** SQLite runs with `journal_mode=WAL` and a bounded `busy_timeout` (e.g., `5000ms`), ensuring concurrent readers never block on an active background writer and writers commit atomically when flipping `Snapshot.status` from `building` to `ready`.

### B. Timeouts, Cancellation & Background Task Lifecycle

* **Hierarchical Deadline Propagation:** Every incoming MCP request receives a root deadline (`timeout_ms`). Sub-calls (`Sestacon.resolveContext`, `ParserPort`, optional `DiagnosticsPort` analyzers) receive strict child timeouts strictly shorter than the parent deadline so Analysis Orchestrator always retains margin to assemble partial results before returning (INV-02).  
* **Host Cancellation vs. Background Job Lifetime:** When an AI host cancels an in-flight MCP request, `dylext-gateway` propagates cancellation to the request's own child tasks (Sestacon query, ranking, packaging) and returns promptly. Indexing is **not** owned by the triggering request: a per-`repo_id` background Index Job Manager owns each `building` snapshot with its own lifecycle (queued → running → ready/failed), deduplicates concurrent triggers onto one job, and decides continuation independently of any single caller. Cancelling a request merely **detaches** that caller from the job; the job continues to `ready` so the next request benefits from a warm snapshot, or is cancelled only when no caller is attached and policy (idle/resource pressure) dictates. *(This replaces the earlier arbitrary "continue if past 50%" rule, which incorrectly tied a background job's lifetime to one request's progress.)*  
* **Background Maintenance:** Snapshot compaction, expired `analysis_results` cleanup, and (in Milestone 4\) `repo.index.updated` outbox delivery run as idempotent, restart-safe background tasks reconciled on Gateway startup.

### C. Incomplete Results vs. Empty Results (INV-03)

Dylext strictly prevents silent false negatives by separating *"nothing affected"* from *"analysis incomplete"*:

* **Verified Empty Result (`status: "complete"`, `limitations: []`):** The repository is indexed (`ready`), all target symbols resolved statically (`Confirmed`), zero callers or findings exist, and `limitations` is empty. The agent can trust that no static references exist.  
* **Partial / Incomplete Result (`status: "partial"`, `limitations: [...]`):** Returned whenever any static edge is `Unknown` / `Potential impact`, Sestacon is unreachable/timed out, an optional Extended Path analyzer timed out, or `staleness_unverified` applies. Agents are instructed by the contract to treat `status: "partial"` as bounded evidence and inspect `limitations[]` before assuming absence of impact.

---

> **Level 3 Design Sections (18–24).** The following sections develop the design precision the Level 2 baseline (§1–17) deliberately deferred. They are the highest-priority areas because Dylext's differentiating value is the correctness and usefulness of its canonical evidence model — not the MCP interface, the parser collection, or the ability to call static analyzers. Weights, thresholds, and depths named here are **configurable defaults**, not frozen constants.

---

## 18\. Code Intelligence and Graph Semantics

The canonical graph is the foundation of Dylext's value; weak graph semantics would undermine impact analysis, context ranking, and review accuracy. This section defines symbol identity, cross-language normalization, the relationship model, and graph invariants.

### A. Canonical Symbol Identity and Normalization

Parsers (`ParserPort`) emit a **neutral symbol descriptor**; the Code Intelligence service maps it to a canonical `symbol_uri`. Normalization rules are uniform across languages so cross-language and provider-swap links survive renames:

| Construct | Canonical representation | Rationale |
| :---- | :---- | :---- |
| **Overloaded methods** | Qualified name plus an ordered **parameter-type descriptor**, e.g. `PaymentService.process(PaymentRequest)` vs. `PaymentService.process(PaymentRequest,RetryPolicy)` | Overloads are distinct symbols; a descriptor-free name collides and silently drops symbols. |
| **Generics / type parameters** | Erased to the raw declared type in the descriptor, with arity retained (`List` / `Map<K,V>` → `Map`), original signature kept as a display attribute | Call resolution operates on erased types; the display form preserves human readability. |
| **Nested / inner classes** | Enclosing qualifier chain with `$` or `.` separator per language (`Outer.Inner`), never a bare simple name | Prevents cross-class collisions. |
| **Anonymous / lambda symbols** | Synthetic stable name \= enclosing symbol \+ ordinal (`Outer.method$lambda#2`) | Deterministic identity across re-index of unchanged source. |
| **Endpoints / contracts** | `kind = endpoint` with method+route or topic as the qualified name (`GET /payments/{id}`) | First-class so HTTP/Kafka/OpenAPI edges attach to a real node. |
| **External / library symbols** | Not materialized as owned `Symbol` rows; referenced only as **unresolved targets** on edges | Dylext owns repository symbols only (ADR-01); external types stay provider-mapped, never invented. |

A `provider_mappings` table binds language-provider or SCIP IDs to the owned `symbol_uri` so an LSP/SCIP swap (ADR-02) never rewrites canonical identity.

### B. Relationship Model — Four Orthogonal Dimensions

The Level 2 baseline overloaded a single `resolution_status` (`Confirmed` / `Potential impact` / `Unknown`) to mean three different things. Level 3 separates them so impact analysis can reason correctly:

1. **`edge_type` (what the relationship *is*):** `CALLS`, `IMPLEMENTS`, `EXTENDS`, `IMPORTS`, `TESTS`, `EXPOSES_ENDPOINT`, `OVERRIDES`, `REFERENCES_TYPE`.  
2. **`resolution_status` (how the *target* was bound):**  
   * `resolved_concrete` — bound to a single concrete symbol.  
   * `resolved_interface` — bound to an interface/abstract member with one or more known implementations (enumerated on the edge).  
   * `unresolved_dynamic` — real reference whose runtime target cannot be determined statically (reflection, dynamic dispatch with no known impls, external target).  
   * `unresolved_external` — target is outside the repository (library/framework).  
3. **`impact_certainty` (how sure we are a change *propagates*):** `direct` (a confirmed concrete call/override path), `potential` (reaches the target only through an interface fan-out, DI binding, or dynamic edge), `speculative` (reached only via a chain containing an `unresolved_*` edge).  
4. **`evidence_provenance` (where the claim came from):** `provider` (`dylext_ast` / `lsp` / `scip`), `provider_version`, `snapshot_id`, `repo_revision`, `confidence (0.0–1.0)`.

The retired single status maps as: old `Confirmed` → `resolution_status = resolved_concrete` **and** `impact_certainty = direct`; old `Potential impact` → `resolved_interface` or a DI binding with `impact_certainty = potential`; old `Unknown` → `unresolved_dynamic|external` with `impact_certainty = speculative`.

### C. Call, Interface, and DI Resolution Rules

* **Receiver typing:** a call is bound using the static type of its receiver. A call on an **external/unknown type** is left `unresolved_external` and never bound to a same-named repository method (prevents false callers). An unqualified call prefers the caller's own enclosing type before widening.  
* **Arity and overload matching:** candidates must match call arity (with varargs expansion) before binding; a call whose arity matches no candidate is left unresolved rather than bound to all same-named symbols.  
* **Interfaces / overrides:** a call resolved to an interface member produces one `resolved_interface` edge that enumerates known implementation `symbol_uri`s; each implementation reachable with `impact_certainty = potential`.  
* **Dependency injection / framework wiring:** constructor/field injection and framework-managed beans produce `potential` edges from the injection point to each candidate implementation; a framework-wired class with zero static callers is reported explicitly (not a bare empty result) so "no callers" is never confused with "wired elsewhere".  
* **Constructors:** `new X(..)` emits a `CALLS` edge targeting the constructor, making constructors and records first-class callees.

### D. Graph Mutation and Invariants

* **Symbol change/disappearance:** because snapshots are immutable (§14), a changed symbol yields new rows under a new `snapshot_id`; edges are never mutated in place.  
* **Late-binding re-resolution:** when a new symbol named *N* appears (or an existing one is removed), same-repository edges whose unresolved target name is *N* are **reopened to pending and re-resolved** during index construction, so a reference in an unchanged file that now resolves differently is corrected rather than left stale.  
* **Cross-file / cross-module edges:** stored by `symbol_uri` (not file offsets), so they survive file moves and module refactors as long as the canonical identity is stable.  
* **Invariants:** (I) every edge references targets by `symbol_uri`, with unresolved targets carrying a signature stub and an `unresolved_*` status — never silently dropped (INV-03); (II) `symbol_uri` is unique within a `snapshot_id`; (III) no edge crosses `snapshot_id` boundaries; (IV) `resolution_status`, `impact_certainty`, and `edge_type` are independently assignable.

### E. Semantic Recall as a Grounded Provider (ADR-09)

Exact structural search answers "who calls `X`" precisely but cannot answer "where do we handle cert rotation?" when no symbol name matches and the answer lives only in code. Optional code embeddings close that recall gap **without weakening the trust thesis**, by being a recall signal that is grounded and verified, not a retrieval authority:

1. **Embed:** symbol bodies and doc-comments are embedded (small local CPU code embedder; Extended Path / async, never on the Fast Path budget) and stored keyed by `symbol_uri` + `snapshot_id`, reusing Sestacon's vector pipeline or a parallel code-vector store.
2. **Resolve:** a fuzzy query runs vector search; each hit is mapped back to its canonical `symbol_uri` — the embedding never leaves the provider as a raw chunk.
3. **Verify:** the resolved symbol is confirmed against the live graph (real symbol, exact signature, callers, revision) exactly as `verify_claims` does; only verified symbols enter the evidence bundle, carrying `evidence_provenance.provider = embedding` with its confidence.

So embeddings supply **recall**; the graph supplies **truth**. A hit that cannot be grounded to a real symbol is dropped (or surfaced as an explicit `unverified` candidate), never presented as structure. This keeps the bundle precise, provenance-stamped, and revision-aware while extending it to intent-based discovery. The semantic score feeds ADR-03 ranking as one signal; it never dominates exact structural relevance.

---

## 19\. Impact Analysis Design

Impact analysis converts the graph into a ranked, honestly-qualified blast radius. This section defines traversal, propagation, and selection semantics; exact weights are configurable.

### A. Traversal Semantics

* **Direction:** blast radius traverses **incoming** edges (reverse: who depends on the changed symbol). Dependency/"what this needs" traversal uses outgoing edges. `analyze_impact` defaults to the reverse direction.  
* **Edge-type rules:** `CALLS`/`OVERRIDES`/`TESTS` propagate impact upward to callers/tests; `IMPLEMENTS`/`EXTENDS` expand downward to implementers when an interface member changes; `IMPORTS`/`REFERENCES_TYPE` propagate only for signature/type-shape changes, not internal-body changes.  
* **Depth:** bounded by a configurable `max_depth` (default 5\) with the reached depth reported; results beyond the bound are summarized as an omission, never silently cut.  
* **Cycles:** a visited-set over `symbol_uri` makes traversal terminate on cyclic graphs; the first (shortest-path) arrival determines a node's distance.

### B. Certainty Propagation and Ranking

* **Path certainty:** a reached symbol's certainty is the **weakest link** along its best path — `direct` only if every edge is `direct`; `potential` if any edge is `potential`; `speculative` if any edge is `unresolved_*`. This is reported per impacted symbol.  
* **Ranking order:** (1) structural distance (direct callers first), (2) path certainty (`direct` \> `potential` \> `speculative`), (3) test-reference gap (changed symbols with no static `TESTS` edge ranked up for attention), (4) token cost. Graph centrality is an auxiliary tie-breaker only (ADR-03).  
* **Worked example** — change to `PaymentService.process()` with `process → PaymentRepository.save → DatabaseAdapter.execute`:  
  * If `process → save` is `resolved_concrete` (`direct`) and `save → execute` is `resolved_interface` (`potential`), the result lists all three, but `PaymentRepository.save` is `direct` impact while `DatabaseAdapter.execute` is `potential` (reached only through an interface fan-out). The agent is told which conclusions are trustworthy and which require judgment.

### C. Interface Expansion and Test Selection

* **Interface expansion:** when the changed symbol is an interface member, the affected set includes each known implementation (as `potential`) plus their callers; when it is a concrete override, only that override's callers are `direct`.  
* **Test selection:** tests are selected via `TESTS` edges to any symbol in the affected set, then tiered — *directly referencing tests* (static `TESTS` edge to a `direct`\-impact symbol) first, then tests reaching the change only through `potential` paths. Selection reports static references only and never claims execution or runtime coverage (ADR-04); execution/coverage come from Extended Path evidence (§21).  
* **Potential vs. direct in the final result:** potential and speculative impacts are **included but labeled**, so the agent sees the full plausible radius without treating unconfirmed reach as fact (INV-03).

---

## 20\. Indexing and Incremental Update Design

This section defines the full indexing pipeline and the intended **correctness model** for incremental updates (not necessarily a sophisticated incremental compiler in the first release).

### A. Full Pipeline Stages

1. **File discovery & filtering** — enumerate tracked (+ dirty in M1B) files; tag `skipped_binary` / `skipped_generated` / oversized/ignored.  
2. **Parser dispatch** — route by language to `ParserPort` with per-file timeout and error isolation (`parse_error` marks one file, never aborts the snapshot).  
3. **AST extraction** — produce the neutral descriptor per declaration and reference.  
4. **Canonical symbol normalization** — apply §18.A rules to emit `Symbol` rows.  
5. **Relationship extraction** — emit edges with `edge_type` and raw target descriptors.  
6. **Relationship resolution** — bind targets per §18.C, assigning `resolution_status` and `impact_certainty`; unresolved targets kept as stubs.  
7. **Test relationship extraction** — tag `is_test_symbol` and emit `TESTS` edges.  
8. **Graph persistence** — write `File`/`Symbol`/`Relationship` under the new `snapshot_id` in `building` status.  
9. **Snapshot validation** — integrity checks (uniqueness, dangling non-stub targets, counts).  
10. **Atomic publication** — flip `status: building → ready` in one transaction; readers only ever see `ready`.

### B. Incremental Correctness Model

On a change, the intended invalidation is defined by **what could resolve differently**, not merely what text changed:

* **Which symbols regenerate:** symbols in changed/added files; symbols in deleted files are removed.  
* **Which files re-parse:** changed/added files only; unchanged files reuse content-addressed extraction (§14 immutable membership).  
* **Which incoming edges re-resolve:** any edge in the repository whose **target name** matches a symbol that was added, removed, or had its signature changed is reopened and re-resolved — this is how a reference in an *unchanged* file that now binds to a new/removed symbol is corrected (§18.D late-binding re-resolution).  
* **Signature-change blast:** a changed method signature invalidates (a) the symbol itself, (b) all incoming `CALLS`/`OVERRIDES` edges (arity/override may no longer match), and (c) `IMPLEMENTS` edges if an interface member signature changed.  
* **Correctness vs. sophistication:** the first release may re-resolve conservatively (reopen a slightly wider candidate set) as long as it never leaves a stale binding; precision optimizations are additive and must preserve this no-stale-binding guarantee.

---

## 21\. Evidence and Finding Model

Evidence provenance is what lets an agent trust Dylext over its own exploration; this section refines the `Evidence`, test-evidence, and `ChangeSet` entities from §12.

### A. Evidence Provenance (every `Evidence` item)

Each item records: **what claim it supports** (`subject_uri` \+ `claim_type`), **where it came from** (`source_provider` \+ `provider_version`), **exact source location** (`repo_path`, `start_line`, `end_line`, or document `section_anchor`), **revision & snapshot** (`repo_revision`, `snapshot_id`; for docs `doc_id` \+ `doc_version`), **resolution/confidence** (`resolution_status`, `impact_certainty`, `confidence`), and **whether it was in the initial response** (`included_in_initial_response`). Items with `included_in_initial_response = false` remain retrievable by `get_evidence(analysis_id, evidence_ids[])`.

### B. Test Evidence Representation

The four categories (ADR-04) are represented as distinct evidence shapes, not a single flag:

| Category | Required fields | Never implies |
| :---- | :---- | :---- |
| **Static test reference** | `test_symbol_uri`, `target_symbol_uri`, `snapshot_id` | execution or coverage |
| **Test execution** | `execution_id`, `repo_revision`, `test_identity`, `result` (`passed`/`failed`/`skipped`), `started_at` | that the changed line was traversed |
| **Runtime coverage** | `coverage_artifact_id`, `repo_revision`, covered `repo_path`\+line ranges | that any assertion checked behavior |
| **Test failure** | `execution_id`, `repo_revision`, `test_identity`, failure message/location | a Dylext-owned defect conclusion |

Execution and coverage are **Extended Path** artifacts (ingested SARIF/coverage/CI), tied to an `execution_id` and `repo_revision` so they are never confused with Fast Path static references.

### C. `ChangeSet` Refinement

Per changed file: `change_type` (`added` / `modified` / `deleted` / `renamed` / `copied`), `old_path` \+ `new_path` for renames, and `hunks[]` each carrying **both** `old_range` (`start_line`,`line_count`) and `new_range`. Symbol mapping records, per affected symbol, the `symbol_uri` before and after plus a `symbol_change` (`added` / `removed` / `signature_changed` / `body_changed` / `moved` / `renamed`) so rename-vs-delete+add is distinguished and impact analysis can scope signature-change blast correctly (§20.B).

### D. Finding Provenance

Every `Finding` references the `evidence_ids[]` that justify it; findings whose evidence does not intersect `ChangeSet` diff lines or a verified structural path are `rejected_off_diff` before output. `validation_status` and `confidence` are always present.

---

## 22\. API and Contract Specification

Section 16 defines envelopes and errors; this section specifies each capability as a typed contract (inputs, validation, output, errors, timeout, pagination, versioning). The JSON Schemas below (Draft 2020-12) are **normative and embedded in this document**; the accompanying prose states validation and behavior that the schema alone cannot express. All schemas share the common definitions in §22.A.

### A. Shared Definitions (`$defs`)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "dylext://contracts/common.json",
  "$defs": {
    "symbol_uri": {
      "type": "string",
      "pattern": "^repo://[^/]+/.+#.+$",
      "description": "Canonical repo://<repo_id>/<path>#<qualified_sig>."
    },
    "repo_path": { "type": "string", "pattern": "^(?!/)(?!.*\\.\\.).+$",
      "description": "POSIX repo-relative path; no leading slash, no '..'." },
    "revision": { "type": "string", "minLength": 1,
      "description": "Commit SHA, ref, or working-tree snapshot id." },
    "token_budget": { "type": "integer", "minimum": 1 },
    "timeout_ms": { "type": "integer", "minimum": 1, "maximum": 300000 },
    "request_context": {
      "type": "object",
      "required": ["workspace_path"],
      "properties": {
        "workspace_path": { "type": "string", "minLength": 1 },
        "revision": { "$ref": "#/$defs/revision" },
        "token_budget": { "$ref": "#/$defs/token_budget" },
        "timeout_ms": { "$ref": "#/$defs/timeout_ms" }
      }
    },
    "status": { "enum": ["complete", "partial", "building", "failed"] },
    "limitation": {
      "enum": ["unresolved_dynamic_edges", "sestacon_unavailable",
               "staleness_unverified", "extended_analyzer_timeout", "index_building",
               "index_partial", "revision_superseded"]
    },
    "omitted_entry": {
      "type": "object",
      "required": ["category", "count", "evidence_ids"],
      "properties": {
        "category": { "type": "string" },
        "count": { "type": "integer", "minimum": 0 },
        "evidence_ids": { "type": "array", "items": { "type": "string" } }
      }
    },
    "response_envelope": {
      "type": "object",
      "required": ["analysis_id", "repo_id", "repo_revision", "is_dirty",
                   "status", "payload", "tokens_used", "token_budget",
                   "omitted", "limitations"],
      "properties": {
        "contract_version": { "type": "string" },
        "analysis_id": { "type": "string", "format": "uuid" },
        "repo_id": { "type": "string" },
        "repo_revision": { "$ref": "#/$defs/revision" },
        "is_dirty": { "type": "boolean" },
        "doc_revisions": { "type": "array", "items": { "type": "string" } },
        "status": { "$ref": "#/$defs/status" },
        "payload": { "type": "object" },
        "tokens_used": { "type": "integer", "minimum": 0 },
        "token_budget": { "$ref": "#/$defs/token_budget" },
        "omitted": { "type": "array", "items": { "$ref": "#/$defs/omitted_entry" } },
        "limitations": { "type": "array", "items": { "$ref": "#/$defs/limitation" } }
      }
    },
    "error": {
      "type": "object",
      "required": ["code", "message"],
      "properties": {
        "code": { "enum": ["INVALID_SCOPE", "INDEX_NOT_READY", "ANALYSIS_EXPIRED",
                           "BUDGET_EXCEEDED", "INTERNAL_ERROR"] },
        "message": { "type": "string" },
        "retry_after_ms": { "type": "integer", "minimum": 0 }
      }
    }
  }
}
```

Every tool contract therefore defines required/optional inputs, input validation (and the error raised), the output schema (`response_envelope` + a typed `payload`), error behavior (§16.C), timeout behavior (`timeout_ms` + partial-result rule), progressive retrieval, and compatibility/versioning.

### B. `prepare_implementation_context`

```json
{
  "$id": "dylext://contracts/prepare_implementation_context.json",
  "allOf": [{ "$ref": "common.json#/$defs/request_context" }],
  "type": "object",
  "required": ["workspace_path"],
  "properties": {
    "task_intent": { "type": "string", "minLength": 1 },
    "target_symbols": { "type": "array",
      "items": { "$ref": "common.json#/$defs/symbol_uri" } },
    "target_paths": { "type": "array",
      "items": { "$ref": "common.json#/$defs/repo_path" } },
    "include_working_drafts": { "type": "boolean", "default": false }
  },
  "anyOf": [
    { "required": ["task_intent"] },
    { "required": ["target_symbols"] },
    { "required": ["target_paths"] }
  ]
}
```

Validation: at least one of `task_intent` / `target_symbols` / `target_paths`. Unknown symbol/path at `repo_revision` → `INVALID_SCOPE`. Output `payload` carries entry points, callers, static test-reference gaps, scoped rules, and approved Sestacon docs; Sestacon timeout → `sestacon_unavailable` in `limitations`, `status: "partial"`.

### C. `analyze_impact` (exactly-one target)

```json
{
  "$id": "dylext://contracts/analyze_impact.json",
  "allOf": [{ "$ref": "common.json#/$defs/request_context" }],
  "type": "object",
  "required": ["workspace_path"],
  "properties": {
    "symbol_uris": { "type": "array", "minItems": 1,
      "items": { "$ref": "common.json#/$defs/symbol_uri" } },
    "repo_path": { "$ref": "common.json#/$defs/repo_path" },
    "change_scope": {
      "type": "object",
      "required": ["changed_files"],
      "properties": {
        "changed_files": { "type": "array", "minItems": 1, "items": {
          "type": "object",
          "required": ["path"],
          "properties": {
            "path": { "$ref": "common.json#/$defs/repo_path" },
            "hunks": { "type": "array", "items": {
              "type": "object",
              "required": ["start_line", "line_count"],
              "properties": {
                "start_line": { "type": "integer", "minimum": 1 },
                "line_count": { "type": "integer", "minimum": 0 }
              } } }
          } } }
      }
    },
    "changeset_id": { "type": "string" },
    "direction": { "enum": ["reverse", "forward"], "default": "reverse" },
    "max_depth": { "type": "integer", "minimum": 1, "default": 5 },
    "include_tests": { "type": "boolean", "default": true }
  },
  "oneOf": [
    { "required": ["symbol_uris"] },
    { "required": ["repo_path"] },
    { "required": ["change_scope"] },
    { "required": ["changeset_id"] }
  ]
}
```

`oneOf` enforces exactly one target form. Empty/unknown target at `repo_revision` → `INVALID_SCOPE`. Each impacted item in `payload` carries `impact_certainty` (`direct`/`potential`/`speculative`) and reached depth (§19); results beyond `max_depth` appear in `omitted`.

### D. `get_evidence` (partial + over-budget)

```json
{
  "$id": "dylext://contracts/get_evidence.json",
  "type": "object",
  "required": ["workspace_path", "analysis_id", "evidence_ids"],
  "properties": {
    "workspace_path": { "type": "string", "minLength": 1 },
    "analysis_id": { "type": "string", "format": "uuid" },
    "evidence_ids": { "type": "array", "minItems": 1,
      "items": { "type": "string" } },
    "max_tokens": { "$ref": "common.json#/$defs/token_budget" }
  }
}
```

Response `payload` = `{ "evidence": [...], "missing_evidence_ids": [...] }`.
* **Partial existence:** existing ids are returned in `evidence`; absent ids go to `missing_evidence_ids` — the call does not fail. An entirely unknown/expired `analysis_id` → `ANALYSIS_EXPIRED` (with `retry_after_ms` hint to re-run the parent tool).
* **Over `max_tokens`:** items are returned in requested order up to `max_tokens`, the remainder reported in the envelope `omitted` with `evidence_ids` and token costs for follow-up paging; an item is never truncated mid-item. If even the first requested item cannot fit → `BUDGET_EXCEEDED`.

### E. `review_change` / `review_pull_request`

```json
{
  "$id": "dylext://contracts/review_change.json",
  "allOf": [{ "$ref": "common.json#/$defs/request_context" }],
  "type": "object",
  "required": ["workspace_path"],
  "properties": {
    "mode": { "enum": ["commit", "working_tree"], "default": "working_tree" },
    "commit": { "$ref": "common.json#/$defs/revision" },
    "execution_mode": { "enum": ["fast", "extended"], "default": "fast" }
  },
  "if": { "properties": { "mode": { "const": "commit" } } },
  "then": { "required": ["commit"] }
}
```

```json
{
  "$id": "dylext://contracts/review_pull_request.json",
  "allOf": [{ "$ref": "common.json#/$defs/request_context" }],
  "type": "object",
  "required": ["workspace_path", "base_ref", "head_ref"],
  "properties": {
    "base_ref": { "$ref": "common.json#/$defs/revision" },
    "head_ref": { "$ref": "common.json#/$defs/revision" },
    "execution_mode": { "enum": ["fast", "extended"], "default": "fast" }
  }
}
```

`review_pull_request` builds a cumulative `merge-base..head` `ChangeSet` and loads trusted rules only from `base_ref` (§5.D). `payload` carries validated `Finding`s (`verified_on_diff` only) and the Rule Coverage Audit; `execution_mode: "extended"` analyzer timeouts → `extended_analyzer_timeout` in `limitations`, `status: "partial"`.

### F. `explain_symbol` and Sestacon knowledge tools

```json
{
  "$id": "dylext://contracts/explain_symbol.json",
  "allOf": [{ "$ref": "common.json#/$defs/request_context" }],
  "type": "object",
  "required": ["workspace_path", "symbol_uri"],
  "properties": { "symbol_uri": { "$ref": "common.json#/$defs/symbol_uri" } }
}
```

```json
{
  "$id": "dylext://contracts/search_knowledge.json",
  "type": "object",
  "required": ["workspace_path", "query"],
  "properties": {
    "workspace_path": { "type": "string", "minLength": 1 },
    "query": { "type": "string", "minLength": 1 },
    "approval_status": {
      "type": "array",
      "items": { "enum": ["approved", "working", "historical"] },
      "default": ["approved"]
    },
    "token_budget": { "$ref": "common.json#/$defs/token_budget" }
  }
}
```

`get_project_context` takes only `request_context`; `get_document` requires `doc_id` (string) and optional `doc_version` (string). These route directly to Sestacon (§11 routing model) and return `doc_id`, `doc_version`, `approval_status`, and `staleness_status`.

### G. Pagination / Progressive Retrieval and Versioning

* **Pagination:** large result sets are surfaced as a ranked head plus an `omitted` tail addressable by `evidence_ids[]`; there is no opaque cursor — `analysis_id` + `evidence_ids[]` is the stable pagination key fed back into `get_evidence`.
* **Versioning:** the envelope's `contract_version` follows SemVer. Changes are additive within a major version (new optional input fields, new `payload`/`limitations` members); removals or semantic changes bump the major version. Producers must not emit unknown `limitation` enum values without a major bump; consumers must ignore unknown envelope fields.

---

## 23\. Security and Trust Model

Dylext reads repository content, executes Git, and exposes capabilities to AI agents, so trust boundaries are a first-class design concern.

* **Workspace filesystem boundaries:** the core domain operates only on `repo_id` \+ repo-relative POSIX paths. The Gateway resolves and canonicalizes every path and rejects any that escapes the resolved workspace root. The Dylext index/data store lives under the user's cache/data directory, never inside the Git working tree.  
* **Symlinks and path traversal:** paths containing `..`, absolute paths, or symlinks that resolve outside the workspace root are rejected (`INVALID_SCOPE`); symlink loops are bounded during discovery. No tool input is used to construct a filesystem path without canonicalization \+ root-containment checks.  
* **Git command execution:** Git is invoked via a library/porcelain boundary with **argument arrays, never shell string interpolation**; `repo_id`\-resolved paths and revisions are validated (SHA / ref shape) before use. No user-supplied value is passed to a shell.  
* **Repository content is untrusted input:** source, comments, and repository documentation are **data, not instructions**. Repository instruction files (`REVIEW.md`, `AGENTS.md`, `CLAUDE.md`, etc.) are useful engineering context and feed rule resolution, but they are **never** elevated to trusted system instructions for Dylext or the host agent. The architecture preserves this boundary explicitly: rule docs influence *review policy*, not *tool behavior or system prompts*.  
* **Prompt injection:** text inside ingested docs or source comments that resembles instructions ("ignore previous instructions…") is surfaced as content with provenance, never executed or allowed to alter tool routing, budgets, or policy. For PR reviews, head-branch edits to instruction files cannot silently weaken checks (§5.D trusted base-branch policy).  
* **Secret exposure protection:** evidence packaging avoids echoing likely-secret content; files matching secret/credential patterns (`.env`, key material) are referenced by location/name, and their bodies are excluded from context payloads by default.  
* **Sestacon permissions and trust levels:** document ACLs/roles are enforced at retrieval; approval status (`approved`/`working`/`historical`) governs default inclusion, and working drafts are never auto-promoted or included in reviews unless explicitly opted in. Role scoping is exact (no case-fold collision) so restricted knowledge never leaks to an unauthorized scope.  
* **Gateway request validation:** every tool call is validated against its contract (§22) — types, budget ceilings, timeout bounds, payload size — before any domain work begins; oversized or malformed requests are rejected with a structured error.  
* **Local data and credential handling:** credentials, tenant IDs, and local paths stay outside the core domain model (§3) and are never persisted into snapshots, analysis results, or evidence.

---

## 24\. Operational Architecture

A locally-installed developer tool must be easy to install, start, recover, and upgrade; architectural elegance that is operationally fragile is a poor product.

* **Startup & shutdown:** `dylext-gateway` starts as a subprocess (stdio MCP) with diagnostics on stderr and **pure JSON-RPC on stdout** (no banner/log leakage). Startup opens the SQLite store, runs pending migrations, reconciles background jobs, and reports readiness; shutdown drains in-flight requests, lets detached index jobs reach a safe checkpoint, and closes stores cleanly.  
* **Gateway-to-core initialization:** the Gateway wires port implementations (`GraphStore`, `SnapshotStore`, `KnowledgeProvider`, `ParserPort`, `DiagnosticsPort`) at boot; a failed required port (e.g., unreadable data dir) fails fast with a clear diagnostic rather than serving partial truth.  
* **Sestacon availability & recovery:** Sestacon is an optional dependency reached through the `KnowledgeProvider` port. If it is down/slow/unconfigured, analysis proceeds with `sestacon_unavailable` in `limitations[]` (INV-02); a reachability probe with short TTL avoids per-request timeouts, and recovery is automatic when it returns.  
* **Background job lifecycle:** indexing, snapshot compaction, expired-`AnalysisResult` sweep, and (M4) outbox delivery run under a restart-safe job manager (§17.B) that is reconciled on startup; jobs are idempotent and own their own lifetime independent of any request.  
* **Logging & diagnostics:** structured logs to stderr with correlation by `analysis_id`/`repo_id`; log level is configurable; no secret values are logged.  
* **Configuration management:** environment/config-file driven (data dir, budget ceilings, timeouts, parser paths, resource limits, Sestacon endpoint); configuration lives outside the core domain (§3) and changes that affect answer shape invalidate caches.  
* **Database migration & upgrade:** forward-only migrations gated by `schema_version`; an incompatible or corrupt **Dylext** index quarantines and rebuilds from Git (§13.C), while **Sestacon** uses backup/restore (never rebuild-from-source) for its user-authored data.  
* **Index rebuild & recovery guarantees:** a failed snapshot never becomes `ready`; readers always see a consistent prior `ready` snapshot or an explicit `INDEX_NOT_READY`/`index_building` limitation — never a half-written graph.  
* **Resource limits:** configurable caps on CPU/parallelism, memory, on-disk store size (with compaction/retention), per-file parser timeouts, and max file/repo size; exceeding a limit degrades with an explicit limitation rather than an unbounded hang.  
* **Installation & upgrade model:** single local binary/subprocess plus a data directory; upgrade is drop-in (new version runs migrations on first start); downgrade is not supported across a schema-version bump (documented), and the data dir can always be discarded to force a clean rebuild of the derived index.



---

## 25. Architecture Stress Test & Failure-Mode Analysis

A design-level adversarial pass: each scenario is walked through the architecture to see whether a section saves it (`HOLDS`), the design is silent/wrong (`GAP`/`BUG`), or the risk is inherent and only mitigated (`RISK`). Several `GAP`s are grounded in the real Dylext/Sestacon implementation's own defect history (freshness and degraded-source bugs that only surfaced under cache-on, cross-tool, and sidecar-down conditions) and in Sestacon retrievals over this document that surfaced no on-point text for the probed concern.

### A. Concurrency & Consistency

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-01 | Two agents, cold cache, same repo, simultaneous first call | HOLDS | §17.A per-`repo_id` single-writer lock dedups to one `building` job; the second waits or gets `INDEX_NOT_READY`. |
| ST-02 | `HEAD` advances *during* an in-flight analysis | GAP→fixed | Result stays bound to its immutable `snapshot_id`; the envelope now carries a `revision_superseded` limitation naming the newer revision (§16.A), so the agent knows the evidence is stale and can re-run. |
| ST-03 | `get_evidence` after the source `Snapshot` was compacted but the `analysis_id` TTL is still live | GAP→fixed | §13.C now pins it: `Evidence` rows are **self-contained** (resolved text/locations/provenance persisted at analysis time), and the compactor MUST NOT prune a `Snapshot` referenced by a live `analysis_id`. The two retention clocks are ordered so `get_evidence` works for the full TTL. |

### B. Freshness (grounded in real F-016/F-017/F-018 defects)

| # | Attack | Verdict | Resolution |
| :-- | :--- | :--- | :--- |
| ST-04 | A new commit lands; agent re-calls the *same* read tool that is the only reader | GAP→fixed | Pre-fix, a per-tool read could serve a stale snapshot (this was real: `impact_of` and `repo_overview` lacked freshness-on-read). Now covered by **INV-05 (Freshness-on-Read)** as a per-tool invariant. |
| ST-05 | A non-git state change (signal ingest / provider re-resolution / generation upgrade) alters `impact_of` output without moving `HEAD` | GAP→fixed | Cache keyed on `(tool,args,revision)` never busts. Sestacon retrieval for "non-git cache invalidation" surfaced nothing in the pre-fix doc — confirming the hole. Now covered by **INV-06 (Non-Revision Invalidation)**. |

### C. Scale & Throughput

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-06 | 20k-file monorepo, first-ever interactive call (~100 s full index) | GAP→fixed | §14.A now specifies **target-first partial-ready serving**: the indexer builds the target file's neighborhood first and publishes a `symbols_ready, edges_pending` interim view answered as `status: "partial"` + `index_partial`, so the first cold call is useful instead of forcing a grep fallback. Full blast radius is marked partial until the background job promotes the snapshot to `ready`. |
| ST-07 | Dirty-overlay churn: every editor save changes `worktree_fingerprint`, minting a new snapshot | GAP→fixed | §13.C now bounds it: dirty snapshots are capped per `repo_id` by a bounded LRU (default keep newest 8) and saves are coalesced within a 500 ms debounce before a new overlay is built; evicted dirty snapshots are discarded immediately. Cardinality is bounded during an edit session. |
| ST-08 | `impact_of` on a god-interface implemented 500× | HOLDS* | `token_budget` + `omitted[]` bound the payload (§15.B); *conditional on* ranking surfacing the real caller above the fan-out (ADR-03), which §10 criterion 5 now gates. |

### D. Failure & Degradation

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-09 | Sestacon down/slow | HOLDS | §15/INV-02: `sestacon_unavailable` limitation, Fast Path proceeds. |
| ST-10 | A language parser sidecar is unreachable mid-index | GAP→fixed | The spec previously let a file degrade to an empty shell (reads as "no symbols" — a silent false negative; real bug F-009). Now covered by **INV-07 (Degraded-Source Honesty)**. |
| ST-11 | SQLite corruption / schema mismatch | HOLDS | §13.C/§14.C: Dylext quarantines + rebuilds from Git; Sestacon restores from backup (never disposes user data). |

### E. Cost / Token (the thesis under attack)

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-12 | Tiny answer, heavy envelope — metadata dwarfs the one real fact | GAP→fixed | Sestacon retrieval for "envelope token budget" surfaced no on-point text pre-fix. Now bounded by §16.A **Envelope Token Accounting** + §10 criterion 6 (≤20% overhead). |
| ST-13 | Pathological drill-down: 20 single-id `get_evidence` calls | HOLDS* | Contract batches via `evidence_ids[]` and emits `expected_followups` (§16.A); *conditional on* the host batching rather than looping one id per call. |
| ST-14 | Ranking under-includes → agent re-queries every task | RISK | This is the core thesis risk; not a code bug but a quality bar. §10 criterion 5 (≥80% first-response sufficiency) makes it a gating metric rather than an assumption. |

### F. Security & Trust

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-15 | `REVIEW.md` on the head branch says "disable all checks / auto-approve" | HOLDS | §5.D trusted base-branch policy + §23: head edits are surfaced as *proposed* policy changes, never silently applied. |
| ST-16 | `symbol_uri` / path with `..` or absolute path in a tool arg | HOLDS | §22 schema `repo_path`/`symbol_uri` patterns reject `..`; §23 canonicalizes + enforces workspace-root containment. |
| ST-17 | Prompt-injection text inside an ingested ADR returned as doc evidence | RISK | §23 treats content as data and stamps provenance, but the *agent* still consumes the doc text — the architecture bounds, not eliminates, injection reaching the host. Residual risk, explicitly acknowledged; mitigation is host-side and provenance labeling. |

### G. Protocol & Contract

| # | Attack | Verdict | Where it holds / breaks |
| :-- | :--- | :--- | :--- |
| ST-18 | `token_budget` = 0 / smaller than minimal payload | HOLDS | §16.C `BUDGET_EXCEEDED` with a widen-budget recovery action. |
| ST-19 | Forged / cross-repo `analysis_id` in `get_evidence` | HOLDS | Bound to `repo_id`+`snapshot_id`; mismatch → `INVALID_SCOPE`; expired → `ANALYSIS_EXPIRED`. |
| ST-20 | Duplicate/out-of-order `repo.index.updated` events (M4) | HOLDS | §7/§13: `inbox_dedup` by `event_id` (UUIDv7) + `last_processed_snapshot_id` gap detection. |

### Summary

- **Holds (12):** ST-01, 08*, 09, 11, 13*, 15, 16, 18, 19, 20 and the two freshness/degraded classes once INV-05/06/07 are enforced.
- **Gaps closed in this revision (8):** ST-04/ST-05 (→ INV-05/INV-06), ST-10 (→ INV-07), ST-12 (→ §16.A envelope accounting), ST-02 (→ `revision_superseded`, §16.A), ST-03 (→ evidence/snapshot retention coupling, §13.C), ST-06 (→ target-first partial-ready serving, §14.A), ST-07 (→ dirty-snapshot churn bound, §13.C).
- **Open gaps to design next (0):** all stress-test gaps identified in this pass are now specified; the remaining items are implementation/validation work, not design holes.
- **Inherent risks, mitigated not eliminated (2):** ST-14 (ranking sufficiency — now gated by §10.5), ST-17 (prompt injection via doc content — bounded by provenance labeling, resolvable only host-side).

The through-line: the architecture is sound on concurrency, failure isolation, and protocol safety; its exposure lives where the cost thesis lives — freshness correctness under caching, envelope overhead, cold-start fallback, and ranking sufficiency. All four are now pinned by invariants/contract (INV-05/06/07, §16.A envelope accounting, §14.A partial-ready, §10.5 sufficiency gate); the residual work is measuring ranking sufficiency against real tasks and host-side prompt-injection hygiene.



---

## 26. Agent Integration & Tool-Routing Policy

An agent that doesn't know *when* to call the MCP API will either ignore it (and keep the expensive grep+read loop) or over-use it (paying MCP overhead for a one-line literal grep). So the architecture ships, as a first-class artifact, an **agent-facing usage policy**: a short, auto-loaded steering/skill file that teaches the host AI how to apply the rules and, above all, **which tool to use when**. The policy is the delivery vehicle for the cost thesis at the agent tier — without it, the server's precision never reaches the model's behavior.

### A. Delivery & Enforcement Model (ADR-10)

* **Advisory, not enforced.** We cannot and do not forbid native tools (`grep`, `read`, `glob`, editor LSP). The policy *recommends*; the agent stays free. The goal is effectiveness: each tool used where it is most precise and cheapest, not tool prohibition.
* **Soft adoption (default, AI-agnostic):** the policy ships as a steering/skill file the host auto-loads (`AGENTS.md`/`CLAUDE.md`/`.cursor/rules`/`.kiro/steering` style). Every client gets it; compliance is best-effort.
* **Hard adoption (optional, host-specific):** where a host supports pre-tool hooks, an optional speed-bump can *ask the agent to confirm* before running `grep`/raw-read on **indexed code** (it asks, it never hard-blocks — legitimate native use still works). This is opt-in and per-host, because hook schemas are not portable.
* **Escalation is expected, not a failure:** if an MCP call returns `INDEX_NOT_READY`, `index_partial`, or an empty result with `limitations[]`, falling back to native tools is the correct behavior. The policy tells the agent to route back to MCP once the index is `ready`.

### B. Tool-Routing Decision Rule

The governing principle, one line: **use the MCP API when the question is about code structure, relationships, impact, change, or trusted project knowledge; use native tools for raw text, unindexed content, and mutations — always picking the tool that yields the most precise answer for the least context.**

| The need | Recommended tool | Why this tool is more precise here |
| :--- | :--- | :--- |
| Who calls / what's the blast radius / what tests cover this change | **MCP `analyze_impact`** | Native `grep` matches text, not call edges; it cannot build callers/overrides/test-reference graphs or rank by impact. |
| Pre-edit context: entry points, callers, signatures, rules, relevant ADRs for a task | **MCP `prepare_implementation_context`** | One budgeted, ranked, deduplicated bundle vs. 5+ grep+read round-trips the model must sift. |
| Symbol body + signature + relationships + linked docs for a known symbol | **MCP `explain_symbol`** | Returns structure with provenance; `read` returns whole files the model must trim. |
| Definition / references of a **known** symbol name | **MCP symbol search** (or editor **LSP**) | Exact, revision-stamped; `grep` yields noisy text matches including comments/strings. |
| What changed and which symbols it touches | **MCP `review_change` / ChangeSet** | Maps diff hunks to canonical symbols; raw `git diff` is line-level, un-ranked. |
| Concept / "why" / convention / "where do we handle X" when the answer is **prose** (ADRs, standards, requirements) | **MCP → Sestacon** (`get_project_context`, `search_knowledge`) | Hybrid lexical+semantic retrieval over approved knowledge with citations; `grep` over docs misses paraphrase. |
| Fuzzy intent over **code** with no matching string | **MCP grounded recall** (ADR-09, when shipped); until then **native `grep` + agent judgment** | Honest routing: the capability that gives recall-then-verified code wins once it exists; before that, native search is the pragmatic choice. |
| Literal string in **one known file**; arbitrary **non-code** text (logs, config, lockfiles, data) | **Native `grep` / `read`** | Not indexed code; MCP adds overhead with no structural advantage. |
| Binary / generated / oversized / **outside-repo** files | **Native tools** | Deliberately not in the index (`skipped_*`); the graph has nothing to add. |
| Editing files, running builds/tests, git operations | **Native tools** | Out of scope for Dylext/Sestacon by design; never routed to MCP. |
| User points at a specific file/line and asks "read this" | **Native `read`** | Direct intent; no discovery needed. |

### C. Anti-Patterns the Policy Suppresses

* Re-reading whole files to extract a few relevant lines (use `explain_symbol` / `expand`).
* Multi-round `grep` to reconstruct a call graph the server already has (use `analyze_impact`).
* Calling MCP for a one-off literal match in a single open file (native `grep` is cheaper).
* Trusting a fuzzy match as structure without grounding it to a real symbol (ADR-09 embed→resolve→verify).
* Treating an MCP `limitations[]`/`partial` result as ground truth instead of escalating or widening scope.

### D. Why Routing Beats Prohibition

Forbidding native tools pushes the discovery loop *back into the model's context window* the moment the MCP server can't answer (cold index, unindexed file, raw text) — the exact cost the product exists to remove. A routing policy keeps the agent effective in every case: MCP for precise, structural, trustworthy context; native tools for everything the index legitimately doesn't cover. The win is not that the agent *only* uses Dylext — it's that the agent uses the *right* tool each time, so precise answers come from the index and the model stops paying to rediscover structure. This policy is therefore part of the architecture, not documentation about it: it is how the server's precision becomes the agent's token savings.



### E. Soft Self-Check (Agent Self-Audit)

Routing guidance drifts over a long session: an agent that fell back to native tools once (cold index, a raw-text detour) tends to *keep* using `grep`/`read` out of momentum even after MCP became the better choice again. The fix is a **soft, self-issued reminder** — the agent auditing its own tool mix — not a brute external trigger. This is deliberately distinct from the optional host-side pre-tool hook in §26.A: that hook is machinery; this is the agent's own habit, and the architecture *prefers* the soft version.

* **Self-reflection, not enforcement.** The policy asks the agent to keep a light running awareness of how it has been gathering context and to pause, at natural checkpoints, for a one-line internal question: *"Am I rebuilding code structure by hand with grep/read that an MCP call would give me more precisely and cheaper — and is the index `ready`?"* If yes, route the next discovery step through MCP. If no, carry on; native tools are correct where §26.B says so.
* **Natural checkpoints (not a timer, not every call):** at task start; before kicking off a multi-file exploration; after a short streak of native discovery calls; when about to re-read a file already seen; at the end of a reasoning step before acting. The cadence is judgment-based so the check stays quiet and cheap.
* **Drift signals the agent watches for in itself:** several consecutive `grep`/`read` calls reconstructing callers or a call graph; grepping for a *symbol name* (an exact MCP lookup); re-reading the same file to re-extract a few lines; assembling impact/blast-radius manually. Any of these is the cue to self-correct toward `analyze_impact` / `explain_symbol` / symbol search.
* **Low-noise and non-punitive.** The self-check is an internal nudge, not a visible ritual or an apology; it must not spam the user or over-correct into calling MCP for things §26.B routes to native tools. A single quiet course-correction is the whole behavior.
* **Optional supporting signal.** Where the host exposes it, the agent may glance at its own recent tool-usage mix (or Dylext's served-vs-baseline accounting) as objective input to the reflection — but the self-check works with nothing more than the agent's own awareness, so it is available on every host.

The intent matches ADR-10: we never force the agent back to MCP; we give it a gentle, self-triggered habit of noticing tool drift and re-routing when MCP would genuinely serve it better. Soft reminder, owned by the agent — not a hard gate.