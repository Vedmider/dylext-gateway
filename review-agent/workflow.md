# Workflow

Follow these steps in order for every review.

## Step 1 — Identify the change

Ask the user (or infer from context) which of these is being reviewed:

- **Uncommitted local changes** → call `dylext.working_changes` (repo may need
  to be specified; see `tools.md`).
- **Branch vs. main** → run `git diff <main>...HEAD` via your shell tool.
- **A pasted diff or file path** → use it directly.
- **A design doc / ADR / spec** → this is a `design-review` profile; no diff.

If the user's intent is ambiguous, ask one short question, then proceed.

## Step 2 — Triage

Determine the profile per `spec/code-review-criteria.md` §2:

- If a design doc is the input → `design-review`
- Else if `loc <= 150` and fewer than 2 architectural triggers → `quick-pr`
- Else if `loc <= 600` and fewer than 2 deep triggers among
  `touches_messaging`, `touches_state_machine`, `touches_ddl`,
  `touches_spring + hot_path` → `standard-backend`
- Else → `architecture-and-saga`

Record the profile. Do not announce it unless asked.

## Step 3 — Load active spec sections

For the selected profile, load the `Required` and applicable `Conditional`
sections from `spec/code-review-criteria.md` §2–3. Read the section text from
the corresponding `spec/code-review-criteria-*.md` file.

If the spec files are too large for your context, retrieve the active sections
from Sestacon via `sestacon.retrieve` using the section ID as query.

## Step 4 — Gather context per section

For each active section, look at its rules' "Evidence Source" column. Call
only the tools needed. Typical calls:

- `dylext.outline(file)` — get imports, classes, method signatures
- `dylext.expand(file, line_range)` — get exact code at a diff line
- `dylext.search_symbols(query)` — find similar/reused symbols elsewhere
- `dylext.get_digest(module)` — module API, dependencies, complexity
- `dylext.impact_of(symbol_or_diff)` — callers, callees, affected tests
- `sestacon.retrieve(query)` — ADRs, design docs, conventions

Batch tool calls where sensible. Do not call tools whose evidence you will not
use in a finding.

## Step 5 — Evaluate rules

For each rule in each active section, decide:

- **Applies?** Check the rule's Trigger column against the diff.
- **Pass or fail?** Compare evidence to the rule's Pass Signal.
- **If fail:** produce a finding per `output.md`.

Drop findings where:
- the rule does not apply to this diff
- the evidence does not support the finding
- the file has a suppression for this rule

Keep a private note of suppressed rules (for the report's `suppressed` list).

## Step 6 — Apply merge rules

Per `agent.md` §6:
- Merge same `file:line:rule_id`.
- Drop suppressed.
- Drop unevidenced.

## Step 7 — Derive verdict

Per `agent.md` §7:
- `REQUEST_CHANGES` if any `blocker` or `major`
- `COMMENT` if only `minor`
- `APPROVE` otherwise

## Step 8 — Emit report

Produce the JSON per `output.md`, plus a short human-readable summary at the
top (2–3 sentences). Present the findings grouped by severity (blocker, major,
minor, nit) with file:line references the user can click.

## Step 9 — Offer next actions

Ask the user if they want:
- the report written to a file (`review.json`)
- the findings pasted as PR comments (they will paste; you will format)
- a re-run after fixes

Do not proceed to posting anywhere on your own.
