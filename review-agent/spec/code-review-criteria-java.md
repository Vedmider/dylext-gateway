# Java-Specific Code Review Criteria (`code-review-criteria-java.md`)

Canonical engineering criteria specification grounded in Effective Java (Bloch, 2nd/3rd ed.), the Google Java Style Guide, and Spring/JVM operational guidance, designed for standard pull request reviews and static analysis rule enforcement. This extended version embeds the actual guidance from the canonical sources rather than only linking to them.

---

## Overview & Profile Activation

- **Module Prefix:** `JAV-xx`
- **Canonical Sources:**
    - Effective Java, 3rd edition (Joshua Bloch) — items on immutability, exceptions, generics, methods
    - [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) — hard-and-fast rules
    - Spring Framework reference — `@Transactional`, bean scopes, proxy semantics
    - Java SE API docs — `Optional`, `try-with-resources`, `CompletableFuture`, `ThreadPoolExecutor`, Virtual Threads
    - Hibernate/JPA documentation — fetching strategies, `@EntityGraph`, `JOIN FETCH`
- **Active Section Counts:**
    - `quick-pr`: 1 section (`JAV-S1` when `.java` files modified)
    - `standard-backend`: 1–2 sections (`JAV-S1` + conditional `JAV-S2` on Spring/concurrency/hot-path diffs)
    - `architecture-and-saga`: 2 sections (`JAV-S1` + `JAV-S2` when `.java` files modified)
- **Focus:** Java type system, null/Optional safety, Stream and collection semantics, exception propagation, deterministic resource cleanup, Spring DI/transaction traps, concurrency, and JVM memory/GC efficiency.

---

## Authoritative Guidance — Extracted Reference

The rules in `JAV-S1` and `JAV-S2` operationalize the guidance below. Each rule's "Check" column references the corresponding subsection.

### A. Immutability, Records & Sealed Hierarchies

**Minimize Mutability (Effective Java, Item 17; Google Java Style Guide §6).** Classes should be immutable unless there is a good reason to make them mutable. Make fields `final` where possible; make classes `final` if they are not designed for inheritance. Immutable objects are simple, thread-safe, and can be shared freely. Records are a concise way to model immutable value carriers.

**Records are shallowly immutable (Java SE docs; modern Java guidance).** A `record`'s components are `final`, but if a component is a mutable object (e.g. `List`, `Map`, array), the record only prevents reassignment of the reference — not mutation of the referenced object. Copy mutable components defensively in the compact constructor:

```java
public record Group(List<Shape> items) implements Shape {
    public Group {
        items = List.copyOf(items); // defensive immutable copy
    }
}
```

Records are good for DTOs and value types. They are **not** JPA entities: Hibernate requires a no-arg constructor, non-final fields, and mutable proxies.

**Sealed hierarchies + exhaustive switch (Java 17+).** A `sealed` interface with a fixed set of `permits` implementations lets the compiler verify exhaustive pattern matching. Prefer an exhaustive `switch` expression **without a `default` case**, so adding a new subtype breaks compilation at every consumer that needs updating:

```java
public sealed interface Shape permits Circle, Rect, Group {}

static double area(Shape s) {
    return switch (s) {
        case Circle(double r) -> Math.PI * r * r;
        case Rect(double w, double h) -> w * h;
        case Group(List<Shape> items) -> items.stream().mapToDouble(Main::area).sum();
    };
}
```

**Avoid `clone` (Effective Java, Item 13).** `Cloneable` is a fragile, underspecified mechanism. Prefer a copy constructor or a static copy factory. If you must implement `clone`, obey the contract and document it.

### B. Optional & Null Discipline

**Optional is a return-type hint, not a general null replacement (Java SE docs; community consensus).** The JDK authors intended `Optional` for return values where absence is a meaningful, expected outcome. Using it elsewhere adds overhead and complexity.

- **Never call `Optional.get()` without checking presence.** Use `orElse`, `orElseThrow`, `ifPresent`, or a safe chain (`map`, `flatMap`, `filter`). `get()` throws `NoSuchElementException` and defeats the purpose of `Optional`.
- **Do not use `Optional` as a method parameter.** Callers are forced to wrap values. Use overloaded methods or a nullable parameter instead.
- **Do not use `Optional` as a class field.** It increases memory overhead (each `Optional` is an object), complicates serialization (`Optional` is not `Serializable`), and adds no benefit. Use a nullable field and expose an `Optional`-returning getter.
- **Do not use `Optional` in collections.** `List<Optional<String>>` is an anti-pattern. Filter out empty values with `flatMap(Optional::stream)` instead.
- **`orElse` vs `orElseGet`:** `orElse` evaluates its argument eagerly, even when the `Optional` is present. If the default is expensive (a DB call, a network request), use `orElseGet` for lazy evaluation.
- **`Optional.of(null)` throws NPE immediately.** Use `Optional.ofNullable` for values that may be null.

### C. Collections & Stream Pipelines

**Stream pipelines should be pure (Java SE docs; Effective Java Items 45-48).** `.map`, `.filter`, and `.forEach` should not mutate external state. Mutating a shared collection inside a stream is a hidden side effect that makes the code order-dependent and untestable. Use `Collectors.toList()` (or `toUnmodifiableList()`) to produce a new collection.

**`List.of` / `Map.of` / `Set.of` reject nulls.** These factory methods throw `NullPointerException` if any element is null. When building immutable collections from potentially-null inputs, filter or validate first.

**Defensive copies at boundaries (Effective Java, Item 50).** Accepting a mutable collection from a caller or returning a mutable internal collection transfers ownership. Make a defensive copy on input and return an unmodifiable view (or a copy) on output.

### D. Exceptions

**Checked vs unchecked (Effective Java, Item 70).** Use **checked exceptions for recoverable conditions** (the caller can reasonably take corrective action) and **unchecked exceptions for programming errors** (precondition violations, illegal arguments, null where non-null is required). If it is unclear whether recovery is possible, prefer an unchecked exception.

**Preserve the cause (Effective Java, Item 73).** When translating an exception, always pass the original as the `cause`:

```java
try {
    return loadConfig(path);
} catch (IOException e) {
    throw new ConfigException("failed to load config from " + path, e);
}
```

Never catch an exception and discard it (`catch (Exception e) {}`), and never catch `Exception` / `Throwable` blindly without rethrowing or logging.

**Do not use exceptions for control flow (Effective Java, Item 69).** Exceptions are for exceptional conditions, not for ordinary branching. A method that returns `null` or a sentinel to signal "not found" is often clearer than throwing.

### E. Resource Lifecycle

**try-with-resources (Java SE docs; Effective Java, Item 9).** Any `AutoCloseable` resource (streams, `Connection`, `ResultSet`, `Lock` implementations that implement `AutoCloseable`, `ExecutorService` on Java 19+) must be closed deterministically. `try-with-resources` guarantees closure even on exception paths and correctly suppresses secondary exceptions from `close()`:

```java
try (var in = Files.newInputStream(path);
     var out = Files.newOutputStream(dest)) {
    in.transferTo(out);
}
```

**Nested try-finally is error-prone (Effective Java, Item 9).** A `try-finally` ladder can mask the original exception if `close()` also throws. `try-with-resources` handles this correctly by attaching the close exception as a suppressed exception on the primary exception.

**Defensive copying (Effective Java, Item 50).** As in §C, copy mutable inputs and outputs at API boundaries. This prevents callers from mutating internal state and prevents internal code from mutating caller-owned collections.

### F. Spring DI, Bean Scopes & `@Transactional`

**Singleton beans must be stateless (Spring reference; community consensus).** Spring's default bean scope is `singleton`. A singleton bean is shared across all threads, so any mutable instance field is a data race. Keep singleton beans stateless: business state should live in method-local variables or be passed as parameters. If you genuinely need per-request state, use a request-scoped bean or `ThreadLocal` (and clean it up). Never inject a prototype or request-scoped bean directly into a singleton field — use a scoped proxy or `ObjectProvider`.

**`@Transactional` is proxy-based (Spring reference).** Spring's declarative transaction management is implemented with AOP proxies (JDK dynamic proxy or CGLIB). The proxy intercepts calls from **outside** the bean. Consequences:

- **Self-invocation bypasses the proxy.** A call from `this.otherMethod()` inside the same bean does not go through the proxy, so `@Transactional` on `otherMethod()` has no effect. Fixes: split the transactional method into a separate bean (recommended), inject the bean's own proxy via `ObjectProvider<Self>`, or use AspectJ weaving (not proxy-based).
- **Only public methods can be proxied.** `@Transactional` on a `private` or `final` method is silently ignored.
- **Default rollback is only on `RuntimeException` and `Error`.** A checked exception thrown from a `@Transactional` method will **not** roll back by default. Use `@Transactional(rollbackFor = Exception.class)` when checked exceptions should also trigger rollback.
- **Swallowed exceptions prevent rollback.** If a `try/catch` inside a transactional method catches an exception and does not rethrow, the transaction commits. Either rethrow, or call `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`.

**Read-only transactions (Spring / JPA guidance).** Read-only queries should use `@Transactional(readOnly = true)` so Hibernate skips dirty-checking and the JDBC driver can optimize. The annotation also prevents accidental writes from a read path.

### G. JPA / Hibernate Fetching

**N+1 query problem (Hibernate docs).** Loading a collection with lazy fetching and then iterating it issues one query for the parent list and one query per parent for the child collection. Fix with `JOIN FETCH` in JPQL or `@EntityGraph(attributePaths = "...")` on the repository method. For large result sets that cannot be joined safely (e.g. paginated queries over a collection), use `@BatchSize` or a two-step ID-then-fetch query.

**Lazy loading outside a transaction (`LazyInitializationException`).** A lazy association can only be initialized while the persistence context (the Hibernate `Session`) is open. If you access it after the transaction commits, Hibernate throws `LazyInitializationException`. Fix by fetching inside the transaction (`JOIN FETCH` / `@EntityGraph`) or by mapping to a DTO before the transaction closes.

### H. Concurrency & Executors

**Bounded queues + rejection policy (Java SE docs; production guidance).** `Executors.newFixedThreadPool` and `newCachedThreadPool` use unbounded queues internally. Under load, tasks accumulate without bound until the JVM runs out of memory. In production, construct a `ThreadPoolExecutor` explicitly with a bounded `ArrayBlockingQueue` and an explicit rejection policy (`CallerRunsPolicy`, `AbortPolicy`, or a custom policy). Treat `RejectedExecutionException` as a first-class overload signal, not an unexpected error.

**`CompletableFuture` timeouts (Java SE docs; production guidance).** Never call `CompletableFuture.join()` or `.get()` without a timeout. A blocking call with no deadline turns a transient downstream issue into a permanent thread leak. Use `future.orTimeout(30, TimeUnit.SECONDS).join()`. The legacy `.get(timeout, unit)` requires catching three checked exceptions and is discouraged in new code.

**Virtual thread pinning (JEP 444 / JEP 491).** On JDK 21–23, a virtual thread that blocks inside a `synchronized` block or method **pins** its carrier platform thread, defeating the scalability benefit of virtual threads. Replace `synchronized` with `ReentrantLock` around long blocking critical sections (I/O, `Thread.sleep`, JDBC calls). JDK 24 (JEP 491) removes most pinning, but code targeting 21–23 must avoid it. Do not pool virtual threads; create one per task and limit concurrency to a downstream resource with a `Semaphore`.

**Reactive event loops (Project Reactor / WebFlux).** Calling `.block()` on a `Mono` or `Flux` from a Netty event-loop thread deadlocks the event loop. Use non-blocking composition (`flatMap`, `zip`, `then`) and let the framework subscribe.

### I. JVM Memory & GC

**Autoboxing in hot loops (JVM performance guidance).** A primitive boxed inside a loop (`Integer`, `Long`, `Double` as an accumulator or as a `Map` key) allocates one wrapper object per iteration, creating GC pressure. Use primitive types (`int`, `long`, `double`) and primitive collections (or arrays) in hot paths. Prefer `long[]` over `List<Long>` for large numeric sequences.

**String concatenation in loops (Effective Java, Item 63; JVM guidance).** `String +=` inside a loop allocates a new `String` on every iteration, giving quadratic time and heavy allocation. Use `StringBuilder` (or `String.join`, `Collectors.joining`) for repeated concatenation. On hot paths, prefer `Integer.toString` over `String.format` for simple conversions.

**Reflection on hot paths.** Reflection (`Class.forName`, `Method.invoke`, `Field.get`) is expensive and prevents JIT inlining. Cache reflective lookups at startup (e.g. in a static initializer or a `ConcurrentHashMap`), and prefer direct calls or code-generated accessors in hot paths.

**Unbounded in-memory caches.** A `HashMap` used as a cache with no eviction policy grows until `OutOfMemoryError`. Use a bounded cache (Caffeine, Guava `CacheBuilder` with `maximumSize`) or a distributed cache. If you must use `ConcurrentHashMap`, document the intended maximum and enforce it at the call site.

---

## Section JAV-S1: Java Types, Optional/Null Discipline, Collections & Exceptions

- **Profiles:** `quick-pr` (Required on Java diffs), `standard-backend` (Required on Java diffs), `architecture-and-saga` (Required on Java diffs)
- **Scope & Triggers:**
    - **Apply when:** Any `.java` file is added or modified.
    - **Skip / Noise when:** Diff contains zero `.java` files or touches only generated OpenAPI/Protobuf sources.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `JAV-01` | `java-immutability-records-sealed` | Immutability, `record` & `sealed` hierarchies. See §A — Minimize Mutability, Records are shallowly immutable, Sealed hierarchies, Avoid `clone`. | Modeling DTOs, domain events, value objects, or algebraic sum types | Value carriers use `record` or final immutable fields; mutable record components are defensively copied (`List.copyOf`); closed type hierarchies use `sealed` interfaces with exhaustive `switch` expressions and no `default`; `clone` is avoided in favor of copy constructors. | Mutable setters on value carriers; non-exhaustive `instanceof` chains or `default`-hiding sealed switches; exposing internal arrays/collections without unmodifiable views; mutable record components without defensive copy. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |
| `JAV-02` | `java-optional-null-discipline` | `Optional` & null-handling discipline. See §B — Optional is a return-type hint. | Using `Optional` or nullable types in method signatures, fields, or chains | `Optional` is used strictly as a return type with safe chaining (`orElse`, `orElseGet`, `orElseThrow`, `map`, `flatMap`); `Optional.ofNullable` used for potentially-null values; nullable inputs are explicitly validated; `orElseGet` used for expensive defaults. | Calling `Optional.get()` without presence check; `Optional` as a class field, method parameter, or collection element; `Optional.of(null)`; `orElse` with an expensive default; `isPresent()` + `get()` instead of `ifPresent`. | `minor` | `dylext.outline, dylext.expand, diff_hunk` |
| `JAV-03` | `java-collections-streams-pitfalls` | Collections & `Stream` pipeline hygiene. See §C — Stream pipelines should be pure, `List.of` null traps, Defensive copies. | Transforming collections, iterating with mutation, or building stream pipelines | Stream pipelines are pure and side-effect free; collection iteration uses safe removal/copies; `List.of`/`Map.of`/`Set.of` are not passed null elements; defensive copies at API boundaries. | Mutating external state inside `.map`/`.forEach`; `ConcurrentModificationException` from removing during iteration; passing null elements to `List.of`/`Map.of`/`Set.of`; returning a mutable internal collection directly. | `minor` | `dylext.expand, diff_hunk` |
| `JAV-04` | `java-exception-handling` | Checked vs. unchecked exceptions & cause preservation. See §D — Checked vs unchecked, Preserve the cause, Do not use exceptions for control flow. | Writing `try/catch` blocks, custom exceptions, or boundary error translation | Exceptions preserve the original `cause`; checked exceptions are used for recoverable conditions and unchecked for programming errors; checked exceptions are declared individually and documented with `@throws`; no exceptions for ordinary control flow. | Empty `catch` blocks; catching `Exception`/`Throwable` blindly without rethrow/logging; discarding root causes (`new RuntimeException(e.getMessage())`); checked exceptions for programming errors or sentinel returns for "not found". | `major` | `dylext.expand, diff_hunk` |
| `JAV-05` | `java-resource-lifecycle-safety` | `try-with-resources` & defensive copying. See §E — try-with-resources, Nested try-finally, Defensive copying. | Opening I/O streams, JDBC/HTTP clients, locks, or accepting/returning mutable collections | All `AutoCloseable` resources are bound in `try-with-resources`; nested `try-finally` ladders are replaced by `try-with-resources`; mutable boundary inputs and outputs are defensively copied. | Leaked streams/connections/locks on exception paths; `try-finally` that masks the original exception when `close()` throws; storing/returning caller-owned mutable collections directly. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |

---

## Section JAV-S2: Concurrency, Virtual Threads, JVM/GC Efficiency & Spring Runtime Hygiene

- **Profiles:** `architecture-and-saga` (Required on Java diffs), `standard-backend` (Conditional when `diff.touches_spring == true` or `diff.hot_path == true`), `quick-pr` (Skip)
- **Scope & Triggers:**
    - **Apply when:** Modifying Spring beans/configuration, `@Transactional`/`@Async` methods, thread pools/futures/locks, JPA repositories, high-throughput hot paths, Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor`, `spring.threads.virtual.enabled`), WebFlux (`Mono`/`Flux`), or `ThreadLocal`/MDC context propagation.
    - **Skip / Noise when:** Reviewing plain POJOs, static mappers, or low-QPS CLI utilities.

| Rule ID | Mapped Dimensions | Check | Trigger | Pass Signal | Fail / Anti-Pattern | Severity | Evidence Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `JAV-06` | `pattern-di-scope-safety` | DI bean scopes & singleton thread safety. See §F — Singleton beans must be stateless. | Defining or injecting fields into Spring `@Service`, `@Component`, `@Controller`, or singleton beans | Singleton beans remain stateless or use thread-safe structures; business state lives in method-local variables or parameters; request/prototype-scoped dependencies use scoped proxies or `ObjectProvider`; `ThreadLocal` is cleaned up in a `finally` block. | Mutable per-request or per-user state stored in singleton fields; injecting prototype/request beans directly into singletons; `ThreadLocal` values leaked between requests. | `blocker` | `dylext.outline, dylext.expand, dylext.impact_of, diff_hunk` |
| `JAV-07` | `java-spring-tx-concurrency` | `@Transactional` proxy boundaries & propagation. See §F — `@Transactional` is proxy-based. | Adding or modifying `@Transactional`, `@TransactionalEventListener`, or DB write boundaries | `@Transactional` is invoked across external Spring proxies (not via `this.`); the method is `public` and non-`final`; `rollbackFor = Exception.class` is set when checked exceptions should roll back; exceptions are not swallowed inside the transaction; DB-only transaction scope is short. | Self-invocation (`this.saveInternal()`) bypassing the Spring AOP proxy; `@Transactional` on a `private`/`final` method; checked exception thrown without `rollbackFor`; `try/catch` that swallows the exception and lets the transaction commit; holding a DB transaction across remote HTTP calls. | `blocker` | `dylext.outline, dylext.impact_of, dylext.expand, diff_hunk` |
| `JAV-08` | `java-spring-tx-concurrency` | Concurrency, bounded executors & `CompletableFuture`. See §H — Bounded queues + rejection policy, `CompletableFuture` timeouts. | Using `ExecutorService`, `@Async`, `CompletableFuture`, `synchronized`, or `ReentrantLock` | `ThreadPoolExecutor` is constructed with a bounded `ArrayBlockingQueue` and an explicit rejection policy; `CompletableFuture.join()`/`.get()` always has a timeout (`orTimeout(...).join()`); async error paths use `.exceptionally`/`.handle`; lock ordering is consistent. | Unbounded `Executors.newFixedThreadPool` / `newCachedThreadPool`; `future.join()` / `future.get()` with no timeout; swallowed async exceptions; missing timeouts on `.get()`; nested lock deadlock hazards. | `major` | `dylext.expand, dylext.impact_of, diff_hunk` |
| `JAV-09` | `java-jvm-memory-gc`, `qual-performance-complexity` | JVM memory, GC churn, N+1 queries & reflection. See §I — Autoboxing, String concatenation, Reflection, Unbounded caches; §G — N+1 query problem. | High-throughput endpoints, batch loops, JPA queries, or dynamic reflection code | Hot paths use primitives (no autoboxing in tight loops), `StringBuilder` for repeated concatenation, and cached reflective lookups; JPA queries use `JOIN FETCH`/`@EntityGraph`/`@BatchSize`; in-memory caches are bounded. | Autoboxing or `+=` string concatenation in hot loops; unbounded `HashMap` caches causing `OutOfMemoryError`; N+1 lazy select storms; per-request reflection. | `major` | `dylext.get_digest, dylext.expand, dylext.impact_of, diff_hunk` |
| `JAV-10` | `java-spring-tx-concurrency` | JPA/Hibernate fetching & read-only transaction hygiene. See §F — Read-only transactions; §G — Lazy loading outside a transaction. | Modifying JPA entities, repositories, or transactional service methods | Read-only queries use `@Transactional(readOnly = true)`; lazy associations are initialized inside an active persistence context (via `JOIN FETCH` / `@EntityGraph` or DTO projection); no accidental dirty-checking writes on fetched entities. | `LazyInitializationException` outside transaction scope; accidental dirty-checking updates on fetched entities; missing `readOnly = true` on heavy read flows; accessing a lazy collection after the service method returns. | `major` | `dylext.expand, sestacon.retrieve, diff_hunk` |
| `JAV-11` | `java-virtual-threads-reactive-safety`, `java-spring-tx-concurrency` | Virtual thread pinning, reactive event-loop blocking & MDC context propagation. See §H — Virtual thread pinning, Reactive event loops. | Using Virtual Threads, `synchronized`/native calls on carrier threads, WebFlux `Mono`/`Flux`, or `ThreadLocal`/MDC across async boundaries | On JDK 21–23, long blocking critical sections inside virtual threads use `ReentrantLock` instead of `synchronized`; reactive pipelines avoid `.block()` on Netty event loops; MDC/security context propagates explicitly across async boundaries; virtual threads are not pooled. | Pinning virtual carrier threads with `synchronized` + blocking I/O on JDK 21–23; calling `.block()` on a reactive event-loop thread; leaking or dropping `ThreadLocal`/MDC context across async tasks; pooling virtual threads. | `blocker` | `dylext.expand, dylext.impact_of, diff_hunk` |

---

## Section 7: Evidence Adapter Contract

> Adapter contract: see `code-review-criteria.md` §7.
