# Python-Specific Code Review Criteria (`code-review-criteria-python.md`)

Canonical engineering criteria specification grounded in PEP 8, PEP 484, the Google Python Style Guide, and official asyncio/mypy documentation, designed for standard pull request reviews and static analysis rule enforcement. This extended version embeds the actual guidance from the canonical sources rather than only linking to them.

---

## Overview & Profile Activation

- **Module Prefix:** `PYT-xx`
- **Canonical Sources:**
    - [PEP 8 — Style Guide for Python Code](https://peps.python.org/pep-0008/)
    - [PEP 484 — Type Hints](https://peps.python.org/pep-0484/)
    - [Google Python Style Guide](https://google.github.io/styleguide/pyguide.html)
    - [mypy documentation](https://mypy.readthedocs.io/) — `--strict`, `disallow_untyped_defs`, `warn_return_any`, `no_implicit_optional`, `warn_redundant_casts`, `warn_unused_ignores`
    - [asyncio documentation](https://docs.python.org/3/library/asyncio.html) — event loop, `TaskGroup`, cancellation, `asyncio.timeout`, `asyncio.run`
    - [dataclasses documentation](https://docs.python.org/3/library/dataclasses.html) — `default_factory`, `frozen`, `slots`, `__post_init__`
    - [contextlib documentation](https://docs.python.org/3/library/contextlib.html) — `contextmanager`, `ExitStack`, `closing`, `aclosing`
    - [`typing` documentation](https://docs.python.org/3/library/typing.html) — `Protocol`, `TypedDict`, `NewType`, `Literal`, `Final`, `ClassVar`, `overload`, `Generic`, `TypeVar`, `Callable`
- **Active Section Counts:**
    - `quick-pr`: 1 section (`PYT-S1` when `.py` files modified)
    - `standard-backend`: 1–2 sections (`PYT-S1` + conditional `PYT-S2` on async/concurrency/hot-path diffs)
    - `architecture-and-saga`: 2 sections (`PYT-S1` + `PYT-S2` when `.py` files modified)
- **Focus:** Type annotation coverage, mutable-default traps, exception discipline, context-manager resource cleanup, asyncio event-loop safety, cancellation propagation, import/naming hygiene, advanced typing, modern asyncio primitives, and dataclass immutability.

---

## Authoritative Guidance — Extracted Reference

The rules in `PYT-S1` and `PYT-S2` operationalize the guidance below. Each rule's "Check" column references the corresponding subsection.

### §A. PEP 8 & Google Python Style

**Naming (PEP 8; Google Python Style Guide).** Modules and packages use `lower_snake_case`. Classes, exceptions, and type variables use `PascalCase` (CapWords). Functions, methods, variables, and arguments use `lower_snake_case`. Module-level constants use `CONSTANT_CASE` (`MAX_BOND_DIM`). A single leading underscore (`_helper`) marks module- or class-internal API; `__double_leading` only for genuine name mangling. Use a trailing underscore only to dodge a keyword clash (`class_`, `id_`). Avoid single-character names except loop counters/iterators; never `l`, `O`, `I`.

**Imports (PEP 8; Google).** Import modules/packages, not individual symbols — `from myproject import linalg`, then call `linalg.Svd(...)`. Google style qualifies at the call site; typing symbols are the usual exception. One import per line; grouped and ordered: (1) `__future__`, (2) standard library, (3) third-party, (4) first-party — alphabetical within each group. No wildcard `from x import *`.

**Layout (PEP 8).** 4 spaces per level, never tabs. Line length 79 for code (88/100 acceptable by team), 72 for comments/docstrings. Two blank lines around top-level defs/classes; one blank line between methods. Prefer f-strings; avoid `%` and `.format` in new code.

### §B. Type Hints & mypy Strict Mode

**PEP 484 annotations (mypy docs).** Annotate public function signatures with parameter and return types. mypy is the standard static type checker for Python, enabling gradual typing with type hints (PEP 484) and comprehensive type safety. Strict mode (`--strict`) turns on `disallow_untyped_defs`, `warn_return_any`, `no_implicit_optional`, `warn_redundant_casts`, `warn_unused_ignores`, and related checks. It catches type errors before runtime, improves code documentation, and enhances IDE support while maintaining Python's dynamic nature through incremental adoption.

- Prefer built-in generics (Python 3.9+): `list[int]`, `dict[str, int]`, `tuple[float, float]`.
- Do not annotate `self` / `cls`.
- Use `Optional[T]` (or `T | None`) explicitly; avoid implicit `Optional`.
- For heavy or optional types, `from __future__ import annotations` gives string-form annotations without runtime cost.
- Do not annotate public boundaries with `Any` unless the value genuinely can be anything; prefer `object` (the safe top type) or a protocol.

### §D. Exceptions & Context Managers
Exception handling (Google Python Style Guide). Catch specific exceptions, never bare except:. Bare except catches KeyboardInterrupt and SystemExit and hides real bugs. If you must catch broadly, use except Exception: and re-raise or log. Do not use exceptions for ordinary control flow.

Context managers (contextlib docs). The with statement guarantees setup and teardown of a resource, and cleanup runs exactly once even on exceptions. Use with for files, connections, locks, transactions, and temporary state. For dynamic numbers of context managers, use contextlib.ExitStack. For async resources, use async with and contextlib.aclosing for async generators.

### §E. asyncio & the Event Loop
Single-threaded cooperative scheduling (asyncio docs). asyncio uses a single-threaded event loop that switches between coroutines when they hit await. Any synchronous blocking call inside a coroutine — time.sleep, requests.get, synchronous file I/O, CPU-heavy loops — blocks the entire event loop and prevents all other tasks from running.

Offloading blocking work (asyncio docs). If you must run blocking code inside an async context, offload it: await asyncio.to_thread(blocking_fn, arg) for I/O-bound work, loop.run_in_executor(ProcessPoolExecutor(), cpu_bound_fn, arg) for CPU-bound work. Use async-native libraries (aiohttp, httpx, asyncpg) where available.

Missing await (asyncio docs). Calling an async def function without await returns a coroutine object that is never executed. This is a silent bug: the function body never runs, and if the return value is discarded, Python emits a RuntimeWarning: coroutine was never awaited. Always await coroutines, or schedule them explicitly with asyncio.create_task.

Event loop management (asyncio docs). Use asyncio.run(main()) as the entry point (Python 3.7+). Do not manually create event loops with asyncio.new_event_loop() and asyncio.set_event_loop() — this leads to multiple loops, leaks, and DeprecationWarning in modern Python.

Cancellation (asyncio docs). When a task is cancelled, asyncio.CancelledError is raised inside the coroutine at the next await. Catch it only to perform cleanup, and always re-raise so cancellation propagates. Swallowing CancelledError prevents the task from terminating and is a common source of hangs.

### §F. PEP 8 Programming Recommendations
Comparisons (PEP 8 — Programming Recommendations). Use is and is not for comparisons with singletons like None and True/False, never ==. Write if x is not None:, not if not x == None: (and never if x != None:). Use if is_ready: not if is_ready == True:. Use isinstance(obj, cls) rather than type(obj) == cls, because isinstance correctly handles subclasses and ABCs.

String quotes (PEP 8). Pick one style — single or double — and be consistent within a file. PEP 8 recommends double quotes in prose-adjacent contexts and single quotes otherwise; the Google guide prefers double quotes. Within a f-string, use the opposite quote for the outer vs inner delimiter (f"Hello {name!r}"). Never use '''...''' for a one-line string.

Compound statements (PEP 8). Avoid if x: return y on one line — write the return on its own line. Never use ; to separate statements on one line. Never if x == 1: pass with the body on the same line unless the body is a single short expression and the whole fits in 79 columns; even then prefer a new line.

Trailing commas (PEP 8). In multi-line function calls, definitions, and collection literals, use a trailing comma after the final element. This makes diffs cleaner (adding an element touches one line, not two) and prevents the classic bug of accidentally concatenating a string and a tuple.

### §G. Iteration, Comprehensions & Default Operators

**Iteration idioms (Google Python Style Guide).** Iterate over mappings with `.items()` and `.keys()` / `.values()` as appropriate — never `for k in d: v = d[k]`. Use `enumerate(iterable)` instead of manual index counters. Use `zip(a, b)` instead of parallel indexing. Never iterate over a list while mutating it; iterate over a copy (`for x in list(lst):`) or build a new list. Prefer `.get(key, default)` over `if key in d: d[key] else: default`.

**Comprehensions (Google Python Style Guide).** List/dict/set comprehensions are idiomatic and preferred over `map`/`filter` with a `lambda` when the transformation is simple. A comprehension may contain one `for` clause and one `if` clause; nested `for` clauses or multiple `if`s indicate the logic should be a real function. Do not use comprehensions solely for side effects — that is what `for` loops are for. Dict comprehensions must use the `k: v` form.

**Generator expressions (Google Python Style Guide).** Use generator expressions (`sum(x * x for x in nums)`) to avoid materializing intermediate lists. This is especially important for any iterable whose length is not known to be small.

### §H. Mutable Global & Class-Level State

**Module-level mutable state (Google Python Style Guide).** Module-level variables are initialized once at import time and shared across every importer. A mutable module-level object (a dict, list, or class instance) that is mutated by one code path is silently visible to every other import of the module. Prefer passing state explicitly, using `@functools.lru_cache` for memoization, or a dependency-injection container.

**Class-level mutable attributes (Google Python Style Guide).** Class-level mutable defaults (`class C: items = []`) are shared across every instance that does not override them. Instance-level assignment (`self.items = []` in `__init__`) is the correct fix. `@dataclass` rejects class-level mutable defaults for the same reason.

**The `global` keyword.** `global x` rebinds the name in the module namespace and is invisible to any caller reading the old value. If you find yourself writing `global`, the state should probably be passed as a parameter or owned by a class.

### §I. Advanced Typing — Protocol, TypedDict, NewType, Literal, Final, ClassVar

**Protocol (PEP 544; `typing` docs).** `typing.Protocol` defines structural interfaces ("duck typing with a name"). Prefer `Protocol` over `typing.ABC` when the implementation classes do not otherwise share a base — it lets existing classes satisfy the interface without modification. For runtime-checkable protocols, decorate with `@runtime_checkable` and use `isinstance` sparingly (it only checks method presence, not signatures).

**TypedDict (`typing` docs).** `TypedDict` describes a dictionary with a fixed set of string keys and value types. Prefer `TypedDict` over `dict[str, Any]` when the dict is a record with known fields. Use `Required` / `NotRequired` (PEP 655) to express optional keys. `TypedDict` is structural and works with `json.loads` output.

**NewType (`typing` docs).** `NewType("UserId", int)` creates a nominal type that is distinct to the type checker but identical to `int` at runtime. Use `NewType` for identifiers, units, and other values where mixing two values of the same underlying type is a bug (e.g. `UserId` vs `OrderId`, both `int`). This is the lightweight alternative to a full class.

**Literal (`typing` docs).** `Literal["debug", "info", "warn"]` restricts a value to a fixed set of literal strings or numbers. Use `Literal` in function signatures for flag-like parameters (mode selectors, log levels) instead of `str`, so typos are caught statically.

**Final and ClassVar (`typing` docs).** `Final` marks a name or attribute as not reassignable — the type checker rejects reassignment. `ClassVar` marks a class attribute that is not an instance field. Both are compiler-enforced documentation; annotate `Final` on module-level constants and `ClassVar` on shared class state.

### §J. Type-Checker Suppression Discipline

**`# type: ignore[code]` (mypy docs).** A bare `# type: ignore` silences every error on the line, including future ones. Use the specific error code: `# type: ignore[attr-defined]`, `# type: ignore[arg-type]`, `# type: ignore[no-any-return]`. Add a reason on the same line or an adjacent comment. mypy's `warn_unused_ignores` will catch ignores that become unnecessary; keep it on.

**`typing.cast` vs suppression (`typing` docs).** `typing.cast(T, value)` tells the type checker the value is `T` without any runtime check. It is the type-checker equivalent of `assert` — use it to express a fact the checker cannot infer, not to silence a real error. Never `cast` to `Any` (that's just `# type: ignore` in disguise).

**`reveal_type` (mypy docs).** `reveal_type(x)` (from `typing` under mypy, or `typing_extensions`) prints the inferred type during type-checking. Use it to debug "why is this `Any`?" before reaching for `cast` or `# type: ignore`.

### §K. Modern asyncio Primitives — TaskGroup, timeout, Semaphore, Queue, shield

**`asyncio.TaskGroup` (Python 3.11+).** `async with asyncio.TaskGroup() as tg: tg.create_task(...)` replaces `asyncio.gather` for structured concurrency. If any child task fails, the group cancels the remaining tasks and raises an `ExceptionGroup` — no orphaned tasks, no silent failures. Prefer `TaskGroup` over `gather` in new code; `gather(return_exceptions=True)` is fine for the "collect all results" case, but `TaskGroup` is the safer default.

**`asyncio.timeout` (Python 3.11+).** `async with asyncio.timeout(seconds):` replaces `asyncio.wait_for`. It raises `TimeoutError` on expiry and cancels the enclosed task, ensuring cleanup. Do not use `asyncio.wait_for` in new code.

**`asyncio.Semaphore` / `Queue` / `Lock` / `Event`.** Use `Semaphore` to bound concurrency to a downstream resource (database pool, HTTP connection limit). Use `Queue` for producer/consumer pipelines. Use `Lock` to serialize access to a shared resource. Use `Event` for one-shot signaling. These are the correct primitives — do not hand-roll them with `while` loops and `asyncio.sleep`.

**`asyncio.shield`.** Wrap an awaitable with `asyncio.shield(aw)` when you want the outer coroutine to be cancellable but the shielded operation to complete regardless. Use sparingly — shielding a resource that should be cancelled leaks it.

**Task naming (Python 3.8+).** Name long-lived tasks with `asyncio.create_task(coro, name="...")` or `task.set_name("...")`. Named tasks appear in `asyncio.all_tasks()` output and in `TaskGroup` diagnostics, making production debugging tractable.

### §L. Dataclass Immutability & Validation

**`frozen=True` (dataclasses docs).** `@dataclass(frozen=True)` makes instances immutable: field assignment raises `FrozenInstanceError`, and instances become hashable (usable as dict keys and set elements). Use `frozen=True` for value objects, DTOs, and any type that models an identity by its fields.

**`__post_init__` (dataclasses docs).** For validation, invariants, or derived fields, define `__post_init__(self)` on a dataclass. It runs after `__init__` with the constructed instance; raise `ValueError` on invariant violation. With `frozen=True`, use `object.__setattr__(self, "field", value)` to assign derived fields.

**`slots=True` (dataclasses docs, Python 3.10+).** `@dataclass(slots=True)` prevents per-instance `__dict__` creation and reduces memory. Use it for dataclasses instantiated at scale; skip it when the class will be subclassed with additional fields.

**`order=True` (dataclasses docs).** `@dataclass(order=True)` derives comparison methods based on field order. Field order matters: put the most significant field first. Use `field(compare=False)` to exclude a field from ordering.

**`field(repr=False)` / `field(default=...)` / `field(default_factory=...)`.** Suppress large or noisy fields from `repr()` with `repr=False`. Use `default_factory` for mutable defaults. Use `field(init=False)` for derived or lazily-computed fields that should not be in `__init__`.

---

## Section PYT-S1: Python Types, Typing & Exception Discipline

- **Profiles:** `quick-pr` (Required on Python diffs), `standard-backend` (Required on Python diffs), `architecture-and-saga` (Required on Python diffs)
- **Scope & Triggers:**
    - **Apply when:** Any `.py` file is added or modified.
    - **Skip / Noise when:** Diff contains zero `.py` files or touches only auto-generated stubs, `__init__.py` re-exports, or migration scripts.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `PYT-01` | `py-type-coverage` | Type annotations on public boundaries. See §B — PEP 484 annotations. | Defining or modifying public functions, methods, or module-level constants | Public function signatures have parameter and return annotations; built-in generics used (`list[int]`, not `List[int]`); `Optional[T]` explicit; no `Any` on public boundaries unless justified. | Untyped public functions; `Any` on a public boundary without a comment; `# type: ignore` without an error code and reason. | `major` | `dylext.outline, dylext.expand, diff_hunk` |
| `PYT-02` | `py-mutable-defaults` | Mutable default arguments and dataclass fields. See §C — Mutable defaults. | Defining function parameters with defaults, or dataclass fields | Defaults are immutable (`None`, tuple, string, `frozen=True` dataclass) or use `field(default_factory=...)`; mutable defaults are created inside the body. | `def f(items=[])` or `@dataclass class C: items: list = []` — shared state across calls/instances. | `major` | `dylext.expand, diff_hunk` |
| `PYT-03` | `py-exception-discipline` | Specific exception handling and no bare `except`. See §D — Exception handling. | Writing `try`/`except` blocks or custom exceptions | Catches specific exception types; bare `except:` never used; `except Exception` only with re-raise or explicit logging; exceptions carry actionable context. | `except:` or `except Exception: pass`; catching and silencing `KeyboardInterrupt`/`SystemExit`; using exceptions for control flow. | `major` | `dylext.expand, diff_hunk` |
| `PYT-04` | `py-resource-lifecycle` | Context managers and deterministic cleanup. See §D — Context managers. | Opening files, sockets, DB connections, locks, or transactions | Resources are acquired via `with` / `async with`; `contextlib.ExitStack` for dynamic sets; `aclosing` for async generators; cleanup runs on exception paths. | Manual `f = open(...)` / `f.close()` without `try`/`finally`; leaked connections on early return; bare `finally: close()` where a context manager exists. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `PYT-05` | `py-import-naming-style` | Import discipline, naming, and layout. See §A — PEP 8 & Google Python Style. | Adding imports, new modules, or new public names | One import per line; grouped stdlib/third-party/first-party, alphabetically; no wildcard imports; `snake_case` functions/vars, `PascalCase` classes, `CONSTANT_CASE` constants. | `from x import *`; imports scattered through the file; `camelCase` Python functions; single-letter names outside loop counters. | `minor` | `dylext.outline, sestacon.retrieve, diff_hunk` |
| `PYT-06` | `py-docstrings` | Google-format docstrings on public API. See §A — Google Python Style Guide. | Defining public modules, classes, or functions | Triple-double-quoted docstring with imperative one-line summary; `Args:`, `Returns:`, `Raises:` sections where applicable; examples are deterministic. | Missing docstring on a public function; docstring restates the signature without adding intent; doctest examples that depend on wall-clock time or network. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |
| `PYT-13` | `py-comparisons` | PEP 8 comparisons, `isinstance`, singleton, and string quote discipline. See §F — PEP 8 Programming Recommendations. | Writing comparisons, `isinstance` checks, or new string literals | `is`/`is not` used for `None`/`True`/`False`; `isinstance(obj, cls)` used instead of `type(obj) == cls`; consistent quote style; trailing commas in multi-line constructs; no `;`-separated statements. | `x == None`; `if is_ready == True:`; `type(obj) == int`; mixed quote styles in one file; missing trailing comma in a multi-line list/dict. | `minor` | `dylext.expand, diff_hunk` |
| `PYT-14` | `py-iteration-idioms` | Iteration, comprehension, and default-operator idioms. See §G — Iteration, Comprehensions & Default Operators. | Writing loops, `map`/`filter`, or collection transformations | `.items()` used when iterating dicts; `enumerate` for indices; `zip` for parallel iteration; comprehensions for simple transformations; generator expressions for lazy pipelines; `.get(key, default)` instead of `if key in d`. | `for k in d: v = d[k]`; manual index counters; `map(lambda ...)` where a comprehension fits; mutating a list during iteration; comprehensions with two `for` clauses for non-trivial logic. | `minor` | `dylext.expand, diff_hunk` |
| `PYT-15` | `py-mutable-global-state` | Module-level mutable state and class-level mutable attributes. See §H — Mutable Global & Class-Level State. | Defining module-level variables or class attributes | Module-level state is immutable (`Final`) or explicitly managed (cache, DI container); class-level mutable attributes are replaced by instance fields in `__init__`; `global` keyword avoided. | `MODULE_CACHE = {}` mutated across imports; `class C: items = []`; `global counter` in a function body. | `major` | `dylext.outline, dylext.expand, diff_hunk` |
| `PYT-16` | `py-advanced-typing` | Structural typing, branded types, literals, and suppression discipline. See §I — Advanced Typing; §J — Type-Checker Suppression Discipline. | Defining interfaces, identifiers, flag-like parameters, or suppressing type errors | `Protocol` used for structural interfaces where classes don't share a base; `TypedDict` for record-shaped dicts; `NewType` for identifiers; `Literal` for flag-like parameters; `Final`/`ClassVar` where applicable; `# type: ignore[code]` with a reason instead of bare ignores; `typing.cast` reserved for facts the checker cannot infer. | `dict[str, Any]` for a known record; `str` for a flag with a fixed set of values; `int` for identifiers that must not mix; `# type: ignore` without a code or reason; `cast(Any, x)`. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |
| `PYT-18` | `py-dataclass-immutability` | Dataclass immutability, validation, and field configuration. See §L — Dataclass Immutability & Validation; §C — Dataclasses. | Defining or modifying `@dataclass` classes | Value objects and DTOs use `frozen=True`; `__post_init__` validates invariants; `slots=True` on hot-scale dataclasses; `order=True` with `field(compare=False)` where appropriate; `repr=False` on large fields. | Mutable dataclass used as a value object; no validation of invariants; missing `slots=True` on a dataclass instantiated per request; `field(default=[])` instead of `field(default_factory=list)`. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |

---

## Section PYT-S2: Python Async, Concurrency, Resource Hygiene & Performance

- **Profiles:** `architecture-and-saga` (Required on Python diffs), `standard-backend` (Conditional when `diff.touches_async == true` or `diff.hot_path == true`), `quick-pr` (Skip)
- **Scope & Triggers:**
    - **Apply when:** Using `async def` / `await`, creating tasks, using `asyncio.gather`/`Queue`/`Semaphore`/`TaskGroup`, managing event loops, or touching hot-path comprehensions/allocations.
    - **Skip / Noise when:** Reviewing pure synchronous utility modules with no I/O and no `async` keyword.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `PYT-07` | `py-async-event-loop` | No blocking synchronous I/O inside `async def`. See §E — Single-threaded cooperative scheduling. | Defining or calling I/O inside `async def` coroutines | Async I/O uses non-blocking clients (`httpx`, `aiohttp`, `asyncpg`) or `asyncio.to_thread`; CPU work offloaded to a process pool; `time.sleep` replaced with `await asyncio.sleep`. | `requests.get()`, `time.sleep()`, synchronous `open().read()`, or a CPU-bound loop inside a coroutine — stalls the entire event loop. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `PYT-08` | `py-async-await` | Missing `await` on coroutines. See §E — Missing `await`. | Calling an `async def` function or creating a task | Every coroutine call is either `await`ed or explicitly scheduled with `asyncio.create_task`; returned coroutine objects are never discarded. | `async_fn()` without `await`; discarding the return value of a coroutine call; `RuntimeWarning: coroutine was never awaited`. | `blocker` | `dylext.expand, diff_hunk` |
| `PYT-09` | `py-async-loop-management` | Correct event-loop lifecycle. See §E — Event loop management. | Defining the async entry point or managing loops | `asyncio.run(main())` used at the top level; no manual `new_event_loop`/`set_event_loop` in application code; libraries do not call `asyncio.run`. | `loop = asyncio.new_event_loop(); asyncio.set_event_loop(loop)`; calling `asyncio.run` inside an already-running loop; multiple event loops in one process. | `major` | `dylext.outline, dylext.expand, diff_hunk` |
| `PYT-10` | `py-async-cancellation` | Cancellation propagation. See §E — Cancellation. | Catching `asyncio.CancelledError` or using `try`/`finally` around `await` | `CancelledError` is re-raised after cleanup; `finally` blocks release resources without swallowing cancellation; `asyncio.shield` used deliberately where cancellation must be deferred. | `except asyncio.CancelledError: pass` (swallowed); catching bare `Exception` around `await` that also swallows cancellation; cleanup that hangs indefinitely. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `PYT-11` | `py-async-cpu-offload` | CPU-bound work offloaded from the event loop. See §E — Offloading blocking work. | Running heavy computation inside a coroutine | CPU-heavy work uses `loop.run_in_executor(ProcessPoolExecutor(), ...)` or is moved to a sync function called from a worker; the event loop stays responsive. | A `for`/`while` loop over a large dataset inside `async def` with no `await`; image processing, compression, or crypto in a coroutine without an executor. | `major` | `dylext.get_digest, dylext.expand, diff_hunk` |
| `PYT-12` | `py-async-session-reuse` | Connection and session reuse in async clients. See §E — asyncio best practices. | Creating HTTP clients, DB connections, or session objects inside a coroutine | A single `httpx.AsyncClient`/`aiohttp.ClientSession`/DB pool is created at module or application scope and reused; per-request creation is avoided. | Creating a new `ClientSession` per request; opening a new DB connection per coroutine call; no connection pool configured. | `major` | `dylext.get_digest, dylext.expand, diff_hunk` |
| `PYT-17` | `py-async-modern-primitives` | TaskGroup, timeout, Semaphore, Queue, shield, task naming. See §K — Modern asyncio Primitives. | Using `asyncio.gather`, `asyncio.wait_for`, launching many tasks, or creating long-lived tasks | `asyncio.TaskGroup` used for structured concurrency (Python 3.11+); `asyncio.timeout` instead of `wait_for`; `Semaphore`/`Queue` to bound concurrency; `shield` used deliberately; long-lived tasks named. | `asyncio.gather(*coros)` without `return_exceptions` or a group; `asyncio.wait_for` in new code (use `asyncio.timeout`); unbounded `create_task` in a loop with no `Semaphore`; unnamed long-lived tasks. | `major` | `dylext.expand, dylext.impact_of, diff_hunk` |

---

## Section 7: Evidence Adapter Contract

> Adapter contract: see `code-review-criteria.md` §7.
