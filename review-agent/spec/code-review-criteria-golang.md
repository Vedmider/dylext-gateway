# Go-Specific Code Review Criteria (`code-review-criteria-golang.md`)

Canonical engineering criteria specification grounded in official Go documentation, designed for standard pull request reviews and static analysis rule enforcement. This extended version embeds the actual guidance from the canonical sources rather than only linking to them.

---

## Overview & Profile Activation

- **Module Prefix:** `GOL-xx`
- **Canonical Sources:**
    - [Effective Go](https://go.dev/doc/effective_go)
    - [Go Code Review Comments](https://go.dev/wiki/CodeReviewComments)
    - [Google Go Style Guide](https://google.github.io/styleguide/go)
    - [Go Test Comments](https://go.dev/wiki/TestComments)
- **Active Section Counts:**
    - `quick-pr`: 1 section (`GOL-S1` activated when `.go` files are modified)
    - `standard-backend`: 1–2 sections (`GOL-S1` always active; conditional `GOL-S2` on concurrency or hot-path diffs)
    - `architecture-and-saga`: 2 sections (`GOL-S1` + `GOL-S2` activated when `.go` files are modified)
- **Focus:** Explicit error handling & wrapping, `context.Context` lifecycle propagation, consumer-defined interfaces, idiomatic Go naming/packages, zero-value design, table-driven tests, goroutine/channel concurrency safety, and slice/map/defer memory hygiene.

---

## Authoritative Guidance — Extracted Reference

The rules in `GOL-S1` and `GOL-S2` operationalize the guidance below. Each rule's "Check" column references the corresponding subsection.

### A. Error Handling

**Error Strings (Go Code Review Comments).** Error strings should not be capitalized (unless beginning with proper nouns or acronyms) or end with punctuation, since they are usually printed following other context. Write `fmt.Errorf("something bad")`, not `fmt.Errorf("Something bad")`, so that `log.Printf("Reading %s: %v", filename, err)` formats without a spurious capital letter mid-message. This does not apply to logging, which is line-oriented and not combined inside other messages.

**Don't Panic (Go Code Review Comments).** Do not use `panic` for normal error handling. Use `error` and multiple return values.

**Return Multiple Values, Not In-Band Errors (Go Code Review Comments).** In C-like languages it is common for functions to return values like `-1` or `null` to signal errors or missing results. Go's support for multiple return values provides a better solution. A function should return an additional value (an `error`, or a `boolean` when no explanation is needed) as the final return value. Values like `nil`, `""`, `0`, and `-1` are fine only when they are valid results and the caller need not handle them differently from other values.

**Indent Error Flow (Go Code Review Comments).** Keep the normal code path at a minimal indentation, and indent the error handling, dealing with it first. This improves readability by permitting visual scanning of the normal path quickly:

```go
if err != nil {
    // error handling
    return
}
// normal code
```

not:

```go
if err != nil {
    // error handling
} else {
    // normal code
}
```

If the `if` statement has an initialization, move the short variable declaration to its own line when needed for readability.

**Error wrapping (Effective Go; `errors` package docs).** Use `%w` when callers may inspect the error via `errors.Is` or `errors.As`; use `%v` only when the wrapped error's identity is not part of the contract. Error values carry identity across wrapping; losing it via `%v` breaks sentinel matching.

### B. Contexts

**Contexts (Go Code Review Comments).** Values of the `context.Context` type carry security credentials, tracing information, deadlines, and cancellation signals across API and process boundaries. Go programs pass Contexts explicitly along the entire function call chain from incoming RPCs and HTTP requests to outgoing requests.

- Most functions that use a Context should accept it as their first parameter: `func F(ctx context.Context, /* other arguments */) {}`
- A function that is never request-specific may use `context.Background()`, but err on the side of passing a Context even if you think you don't need to.
- The default case is to pass a Context; only use `context.Background()` directly if you have a good reason why the alternative is a mistake.
- **Do not add a Context member to a struct type**; instead add a `ctx` parameter to each method on that type that needs to pass it along. The one exception is for methods whose signature must match an interface in the standard library or in a third-party library.
- **Do not create custom Context types or use interfaces other than `Context` in function signatures.**
- If you have application data to pass around, put it in a parameter, in the receiver, in globals, or — if it truly belongs there — in a Context value.
- Contexts are immutable, so it is fine to pass the same `ctx` to multiple calls that share the same deadline, cancellation signal, credentials, parent trace, etc.
- Always call the `cancel` function returned by `context.WithCancel`, `context.WithTimeout`, or `context.WithDeadline`, typically via `defer cancel()`.

### C. Interfaces

**Interfaces (Go Code Review Comments).** Go interfaces generally belong in the package that uses values of the interface type, not the package that implements those values. The implementing package should return concrete (usually pointer or struct) types: that way, new methods can be added to implementations without requiring extensive refactoring. Do not define interfaces on the implementor side of an API "for mocking"; instead, design the API so that it can be tested using the public API of the real implementation. Do not define interfaces before they are used: without a realistic example of usage, it is too difficult to see whether an interface is even necessary, let alone what methods it ought to contain.

**Google Go Style Guide — Interfaces.** Define interfaces at the consumption site, not next to the implementation. Single-method interfaces should be named after the method plus `-er` (`Reader`, `Writer`). Accept interfaces, return concrete types. Avoid creating interfaces for hypothetical future needs or solely for mocking; prefer designing the API so the real implementation is testable via its public surface.

### D. Naming

**Package Names (Go Code Review Comments).** All references to names in your package will be done using the package name, so you can omit that name from the identifiers. For example, if you are in package `chubby`, you do not need `type ChubbyFile`, which clients will write as `chubby.ChubbyFile`. Instead, name the type `File`, which clients will write as `chubby.File`. Avoid meaningless package names like `util`, `common`, or `misc`. See the Go Style Guide on package names for more.

**Initialisms (Go Code Review Comments).** Words in names that are initialisms or acronyms (e.g. `URL` or `NATO`) should have a consistent case. For example, `URL` should appear as `URL` or `url` (never `Url`), `ID` should appear as `ID` or `id` (never `Id`), and `HTTP` should appear as `HTTP` or `http` (never `Http`). Within a Go program, this rule applies to variable names as well as type names. Constants like `URL` and `ID` should be capitalized.

**Receiver Names (Go Code Review Comments).** The name of a method's receiver should be a reflection of its identity; often a one or two letter abbreviation of its type suffices (such as `c` or `cl` for `Client`). Do not use generic names such as `this` or `self`, identifiers typical of object-oriented languages that place more emphasis on methods as opposed to functions. Do not use `me` or `my`. The name need not be as descriptive as that of a method argument, as its role is obvious and serves no documentary purpose. It can be very short in the case of very short method bodies.

**Receiver Type (Go Code Review Comments).** Choosing whether to use a value or pointer receiver on methods can be difficult, especially to new Go programmers. If in doubt, use a pointer, but there are times when a value receiver makes sense, usually for reasons of efficiency, such as for a small array, a struct that has no mutable fields, or a basic type such as `int` or `string`. The key is to be consistent: do not mix pointer and value receivers for a given type.

**Getter names (Go Code Review Comments).** Go does not provide automatic support for getters and setters. There is nothing wrong with providing getters and setters yourself, and it is often appropriate to do so, but it is neither idiomatic nor necessary to put `Get` into the getter's name. If you have a field called `owner` (lower case, unexported), the getter method should be called `Owner` (upper case, exported), not `GetOwner`. The use of upper-case names for export provides the hook to discriminate the field from the method. A setter function, if needed, will likely be called `SetOwner`.

### E. Zero Values and Initialization

**Useful Zero Values (Go Code Review Comments).** Go's zero values are useful and can often be relied upon. A struct can be used out of the box without any constructor. For example, a `sync.Mutex` is usable in its zero-value form. A `bytes.Buffer` is usable out of the box. Design types so their zero value is useful and sensible.

**`init()` functions (Go Code Review Comments; Google Go Style Guide).** Avoid `init()` where possible. `init()` runs before all other initialization in the package and can make code harder to reason about. It is not a substitute for a constructor or for explicit setup. Reserve `init()` for cases that genuinely cannot be expressed any other way (for example, setting up package-level registration tables that the standard library requires).

### F. Testing

**Table-Driven Tests (Go Test Comments).** Table-driven tests are the standard for testing Go functions. Use a slice of test cases, each with a `name` field and whatever inputs and expected outputs are relevant, and iterate over them with `t.Run`:

```go
tests := []struct {
    name string
    in   int
    want int
}{
    {"zero", 0, 0},
    {"one", 1, 1},
    {"two", 2, 1},
}
for _, tc := range tests {
    t.Run(tc.name, func(t *testing.T) {
        got := fib(tc.in)
        if got != tc.want {
            t.Errorf("fib(%d) = %d, want %d", tc.in, got, tc.want)
        }
    })
}
```

**Test Helpers (Go Test Comments).** Test helpers must call `t.Helper()` in their body so failures are attributed to the caller's line, not the helper's. Use `cmp.Diff` (from `github.com/google/go-cmp/cmp`) for structural comparison when the value is complex.

**Failure Messages (Go Test Comments).** Failure messages should include the input and the `got` vs. `want` values. Prefer `t.Fatalf("F(%d) = %d, want %d", in, got, want)` over `t.Fatal("test failed")`. When the value is complex, use `cmp.Diff(got, want)`.

### G. Concurrency

**Goroutines and Channels (Effective Go).** Channels are the primary means of communication between goroutines. Do not communicate by sharing memory; instead, share memory by communicating. Every goroutine you start should have a clear termination path: a `ctx.Done()` channel, a channel close, a `sync.WaitGroup`, or `golang.org/x/sync/errgroup`.

**Panics in goroutines (Go Code Review Comments).** A panic in a goroutine cannot be recovered from another goroutine; it crashes the process. If a goroutine may panic, wrap its body in a `defer`/`recover` at the worker boundary and report the panic through an error channel.

**Mutex copying (Go Code Review Comments).** Structs that contain a `sync.Mutex` or `sync.WaitGroup` must not be copied by value. Use pointer receivers and pass pointers to such structs. `go vet` reports this as a bug.

### H. Memory and `defer`

**`defer` (Effective Go).** `defer` runs the deferred function when the surrounding function returns, not when the enclosing block ends. A `defer` inside a loop runs at function return, so resources opened in each loop iteration accumulate until the function returns. If resource acquisition is inside a loop, extract the body into a helper function so the `defer` runs on each iteration:

```go
for _, path := range paths {
    if err := func() error {
        f, err := os.Open(path)
        if err != nil {
            return err
        }
        defer f.Close()
        return process(f)
    }(); err != nil {
        return err
    }
}
```

**Slice preallocation (Effective Go).** When a slice's final length is known in advance, preallocate with `make([]T, 0, n)` to avoid repeated backing-array reallocation. When a small sub-slice of a very large backing array would otherwise pin the large array in memory, copy it out (for example with `slices.Clone`) so the large array can be garbage-collected.

**Strings in hot paths (Effective Go).** String concatenation with `+` allocates a new string each time. In loops, use `strings.Builder` or `bytes.Buffer`. On hot paths, prefer `strconv.Itoa` over `fmt.Sprintf` for simple integer-to-string conversion.

**Cryptographic randomness (Effective Go).** Use `crypto/rand` for tokens, keys, and any security-sensitive identifier. `math/rand` is not cryptographically secure.

---

## Section GOL-S1: Go Idioms, Error Handling, Naming & Interface Design

- **Profiles:** `quick-pr` (Required on Go diffs), `standard-backend` (Required on Go diffs), `architecture-and-saga` (Required on Go diffs)
- **Scope & Triggers:**
    - **Apply when:** Any `.go` file is added or modified.
    - **Skip / Noise when:** Diff contains zero `.go` files or touches only generated Protobuf (`*.pb.go`), OpenAPI, or `mockgen` files.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GOL-01` | `go-error-handling` | Explicit error handling and wrapping (`%w` vs. `%v`). See §A — Error Strings, Don't Panic, Error wrapping. | Returning, wrapping, or checking error values | Errors are never ignored (`_ = err` only with explicit rationale); wrapped with `%w` when callers inspect via `errors.Is`/`errors.As`; error strings lowercase without trailing punctuation. | Ignored errors, `panic` for normal runtime errors, capitalized/punctuated error messages, or losing root causes via `%v` when sentinel matching is needed. | `blocker` | `dylext.expand, diff_hunk` |
| `GOL-02` | `go-error-handling` | Error inspection and indentation flow (`errors.Is`/`errors.As`, early returns). See §A — Indent Error Flow. | Comparing sentinel/typed errors or handling `if err != nil` | Uses `errors.Is`/`errors.As` instead of `==` or type assertions; handles errors early and returns so the happy path stays unindented. | Direct `err == ErrNotFound` comparison that fails on wrapped errors; `if err == nil { ... } else { ... }` deep nesting. | `major` | `dylext.expand, diff_hunk` |
| `GOL-03` | `go-interface-design` | Consumer-defined small interfaces ("Accept interfaces, return structs"). See §C — Interfaces. | Defining or accepting interfaces in function parameters or packages | Interfaces are small (1–3 methods), defined in the consuming package; constructors return concrete struct types. | Fat producer-side Java-style interfaces, returning interfaces from constructors, `interface{}`/`any` abuse where generics or concrete types fit, or interfaces introduced solely for mocking. | `minor` | `dylext.outline, dylext.search_symbols, diff_hunk` |
| `GOL-04` | `go-naming-packages` | Idiomatic Go naming, initialisms, receiver consistency. See §D — Package Names, Initialisms, Receiver Names, Getter names. | Naming packages, exported symbols, getters, initialisms, or method receivers | Short lowercase package names (no `util`/`common`/`misc`); consistent initialisms (`ID`, `URL`, `HTTP`); `Owner()` not `GetOwner()`; consistent value vs. pointer receivers across a type. | `GetX()` getters, mixed-case initialisms (`userId`, `HttpClient`), stuttering (`user.UserService`), or mixing pointer and value receivers on the same struct. | `minor` | `dylext.outline, sestacon.retrieve, diff_hunk` |
| `GOL-05` | `go-zero-values-init` | Useful zero values and avoiding `init()` side effects. See §E — Useful Zero Values, `init()` functions. | Designing exported structs, mutexes, buffers, or package initialization | Structs work safely at their zero value (`sync.Mutex`, `bytes.Buffer`) without mandatory constructors; `init()` avoids I/O, network calls, or hidden global state. | Uninitialized map/pointer dereference on zero-value structs; `init()` opening DB connections, reading env/files, or panicking unexpectedly. | `major` | `dylext.outline, dylext.expand, diff_hunk` |
| `GOL-06` | `go-testing-table-driven` | Table-driven tests, `t.Helper()`, diff-oriented failure messages. See §F — Testing. | Adding or modifying `*_test.go` files | Table-driven tests with `t.Run`; helpers call `t.Helper()`; failure messages show `got` vs. `want` clearly (`cmp.Diff` or `got %v, want %v`). | Duplicated test boilerplate without subtests; opaque `assert.True` failures without `got`/`want`; helpers that mask caller line numbers. | `major` | `dylext.expand, diff_hunk` |

---

## Section GOL-S2: Go Concurrency, Contexts, Memory & Performance Hygiene

- **Profiles:** `architecture-and-saga` (Required on Go diffs), `standard-backend` (Conditional when `diff.touches_concurrency == true` or `diff.hot_path == true`), `quick-pr` (Skip)
- **Scope & Triggers:**
    - **Apply when:** Spawning goroutines, using channels/mutexes/atomics/`errgroup`, accepting `context.Context`, managing `defer`/closers, or touching hot-path slices/maps/allocations.
    - **Skip / Noise when:** Reviewing simple stateless DTO mappers, CLI flag wiring, or static configuration structs.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `GOL-07` | `go-context-lifecycle` | `context.Context` propagation and cancellation discipline. See §B — Contexts. | Functions performing I/O, RPCs, DB queries, or spawning async work | `ctx context.Context` passed explicitly as first parameter (never stored in structs), propagated downstream; `cancel()` from `WithCancel`/`WithTimeout`/`WithDeadline` deferred immediately. | Storing `context.Context` inside a struct; using `context.Background()` inside request paths; leaking `WithTimeout`/`WithCancel` cancel functions. | `blocker` | `dylext.outline, dylext.impact_of, dylext.expand, diff_hunk` |
| `GOL-08` | `go-goroutine-concurrency` | Deterministic goroutine lifecycles and leak prevention. See §G — Goroutines and Channels, Panics in goroutines. | Spawning `go func(...)` or worker pools | Every goroutine has a clear termination path (`ctx.Done()`, channel close, `sync.WaitGroup`, or `golang.org/x/sync/errgroup`) and recovers panics at worker boundaries. | Fire-and-forget goroutines with no shutdown signal; blocking sends on unbuffered channels when receivers exit early; unrecovered goroutine panics crashing the process. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `GOL-09` | `go-goroutine-concurrency` | Data race prevention, mutex/channel discipline, and value copying. See §G — Mutex copying. | Sharing maps/slices across goroutines, using `sync.Mutex`/`RWMutex`, or passing structs | Concurrent map/state access is synchronized via mutex, `sync.Map`, or channel ownership; structs containing `sync.Mutex` or `sync.WaitGroup` are never copied by value. | Concurrent map read/write panic; copying structs that embed `sync.Mutex` by value; holding locks across slow network/DB calls. | `blocker` | `dylext.outline, dylext.impact_of, dylext.expand, diff_hunk` |
| `GOL-10` | `go-memory-slices-defer` | `defer` resource cleanup and loop-defer traps. See §H — `defer`. | Opening files, HTTP `resp.Body`, DB rows/txs, or locks inside functions or loops | `defer resp.Body.Close()` / `defer rows.Close()` runs immediately after error check (and `rows.Err()` is checked); loop-scoped resources close in a helper function rather than accumulating defers. | Calling `defer` inside unbounded loops (delaying cleanup until function return); forgetting `resp.Body.Close()`; ignoring `rows.Err()`. | `blocker` | `dylext.expand, diff_hunk` |
| `GOL-11` | `go-memory-slices-defer` | Slice/map preallocation and backing-array memory retention. See §H — Slice preallocation. | Building slices/maps in loops or sub-slicing large buffers | Uses `make([]T, 0, n)` / `make(map[K]V, n)` when size is known; copies small sub-slices out of huge backing arrays (`slices.Clone`) so large buffers can be GC'd. | Repeated slice reallocation in hot loops; returning `hugeBuf[:16]` keeping a multi-megabyte backing array pinned in memory. | `major` | `dylext.get_digest, dylext.expand, diff_hunk` |
| `GOL-12` | `go-naming-packages`, `qual-performance-complexity` | Hot-path string/reflection hygiene and cryptographic randomness. See §H — Strings in hot paths, Cryptographic randomness. | Hot loops building strings, `reflect` usage, or security token generation | Uses `strings.Builder`/`strconv` in hot paths; avoids runtime reflection in tight loops; uses `crypto/rand` (never `math/rand`) for tokens/secrets. | Quadratic `+=` string concatenation in loops; `fmt.Sprintf` overhead on hot paths; `math/rand` used for security-sensitive IDs/tokens. | `major` | `dylext.get_digest, dylext.expand, diff_hunk` |

---

## Section 7: Evidence Adapter Contract

> Adapter contract: see `code-review-criteria.md` §7.
