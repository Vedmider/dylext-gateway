# TypeScript-Specific Code Review Criteria (`code-review-criteria-typescript.md`)

Canonical engineering criteria specification grounded in the TypeScript compiler strict-mode flags, the Google TypeScript Style Guide, `typescript-eslint` recommended rules, and official TypeScript handbook guidance, designed for standard pull request reviews and static analysis rule enforcement. This extended version embeds the actual guidance from the canonical sources rather than only linking to them.

---

## Overview & Profile Activation

- **Module Prefix:** `TS-xx`
- **Canonical Sources:**
    - [TypeScript Handbook](https://www.typescriptlang.org/docs/handbook/) — strict mode, narrowing, generics, utility types
    - [Google TypeScript Style Guide](https://google.github.io/styleguide/tsguide.html)
    - [`typescript-eslint` recommended rules](https://typescript-eslint.io/rules/) — `no-explicit-any`, `no-floating-promises`, `no-misused-promises`, `consistent-type-imports`, `require-await`, `no-non-null-assertion`, `no-unsafe-*`
    - [TSConfig reference](https://www.typescriptlang.org/tsconfig) — `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`, `noImplicitOverride`, `noPropertyAccessFromIndexSignature`, `noImplicitReturns`, `noFallthroughCasesInSwitch`
    - [Node.js documentation](https://nodejs.org/api/process.html#event-unhandledrejection) — `unhandled-rejections` mode
- **Active Section Counts:**
    - `quick-pr`: 1 section (`TS-S1` when `.ts`/`.tsx` files modified)
    - `standard-backend`: 1–2 sections (`TS-S1` + conditional `TS-S2` on async/error-handling/promise diffs)
    - `architecture-and-saga`: 2 sections (`TS-S1` + `TS-S2` when `.ts`/`.tsx` files modified)
- **Focus:** Strict-mode compliance, elimination of `any`, safe type narrowing, generics and utility types, async/await discipline, unhandled-promise-rejection prevention, runtime safety of indexed access, module/import hygiene, and modern TypeScript patterns.

---

## Authoritative Guidance — Extracted Reference

The rules in `TS-S1` and `TS-S2` operationalize the guidance below. Each rule's "Check" column references the corresponding subsection.

### §A. Strict Mode & Compiler Flags

**`strict: true` is the baseline (Google TypeScript Style Guide; TSConfig docs).** The `strict` umbrella enables `strictNullChecks`, `noImplicitAny`, `strictFunctionTypes`, `strictBindCallApply`, `strictPropertyInitialization`, `noImplicitThis`, and `alwaysStrict`. In a healthy codebase this is not optional. Without `strictNullChecks`, `null` and `undefined` are assignable to every type, defeating the type system's central promise.

**Beyond `strict` (TSConfig docs; production guidance).** Two high-impact flags are **not** included in the `strict` bundle:

- `noUncheckedIndexedAccess` changes `arr[i]` from `T` to `T | undefined` and `record[key]` from `V` to `V | undefined`. This catches real bugs in code that reads an array or record by a computed index. It is the single highest-value extra flag.
- `exactOptionalPropertyTypes` distinguishes `{ href?: string }` (the key may be absent) from `{ href: string | undefined }` (the key is present with value `undefined`). This prevents a class of bugs where `undefined` is treated as "absent."

**Additional high-value flags (TSConfig docs).**

- `noImplicitOverride: true` requires the `override` keyword on any member that overrides a base-class member. This catches the classic bug of a base class renaming or removing a method that a subclass still "overrides" — without the flag, the subclass method silently becomes a new method.
- `noPropertyAccessFromIndexSignature: true` forces bracket notation (`obj["key"]`) for index signatures, preventing a typo like `obj.key` from compiling when `key` is not a declared property.
- `noImplicitReturns: true` requires every code path in a function to return a value. It catches the accidental `undefined` return in a function that is supposed to return `T`.
- `noFallthroughCasesInSwitch: true` catches missing `break`/`return` in `switch` cases.
- `forceConsistentCasingInFileNames: true` prevents case-sensitivity mismatches between macOS (case-insensitive) and Linux (case-sensitive) filesystems.

**`strictPropertyInitialization` (TSConfig docs).** Class fields must be initialized in the constructor or marked with the definite-assignment assertion `!`. If you find yourself turning this flag off frequently, it is a signal that the class has too many optional fields or that initialization logic should move to a factory.

### §B. The `any` Escape Hatch & `unknown`

**`any` disables the type system (`no-explicit-any`; Google TypeScript Style Guide).** The `any` type is a dangerous escape hatch. Using `any` disables many type-checking rules and is generally best used only as a last resort or when prototyping. The `--noImplicitAny` flag prevents an *implied* `any`, but it does not prevent `any` from being written explicitly; the ESLint rule `@typescript-eslint/no-explicit-any` catches explicit uses. Preferable alternatives: if the type is known, describe it in an `interface` or `type`; if the type is not known, use `unknown` and narrow with a type guard.

**`unknown` vs `any` (Google TypeScript Style Guide).** `unknown` is the safe top type: you must narrow before use. `any` silently breaks type safety — every property access and every call is permitted. Never launder an existing `any` into `unknown` merely to quiet a linter, and respect an `any` that is deliberate (with a comment).

**`no-unsafe-*` family (typescript-eslint).** The `no-unsafe-assignment`, `no-unsafe-return`, `no-unsafe-call`, and `no-unsafe-member-access` rules catch the most common leak paths: assigning `any` to a typed variable, returning `any` from a typed function, calling a method on an `any`, and accessing a property on an `any`. Enable them alongside `no-explicit-any`.

### §C. Type Narrowing, Generics & Utility Types

**Interfaces for object shapes (Google TypeScript Style Guide).** Use `interface` for object shapes, not a `type` alias of an object literal. Use interfaces rather than classes to define structural types, and state the type at the symbol's declaration. Optional fields and parameters (`href?: string`) rather than `href: string | undefined`. Do not bake `| null` or `| undefined` into a type alias; add nullability at the use site.

**Type guards and narrowing (TypeScript Handbook).** Use `typeof`, `instanceof`, and `in` for built-in narrowing. For custom guards, write `function isUser(x: unknown): x is User`. Prefer runtime narrowing over type assertions (`as`). Discriminated unions (a union of object types each with a literal `kind` property) enable exhaustive `switch` checking; destructure *after* narrowing, never before. For assertion functions, use `function assertIsString(x: unknown): asserts x is string`.

**Generics and utility types (TypeScript Handbook; Google TypeScript Style Guide).** Use standard utility types (`Partial<T>`, `Pick<T, K>`, `Omit<T, K>`, `Record<K, V>`, `Awaited<T>`, `ReturnType<F>`, `Parameters<F>`) rather than re-declaring equivalent types. Name generic parameters meaningfully (`Repository<TEntity>`, not `Repo<T>`) unless the scope is trivial. Constrain generics with `extends` to express intent (`<T extends { id: string }>`). Use `satisfies` to check a value against a type without widening it.

**`satisfies` vs annotation (TS Handbook; Google TypeScript Style Guide).** `const x: Type = val` widens `val` to `Type`. `const x = val satisfies Type` checks `val` against `Type` while preserving the narrower literal type of `val`. Use `satisfies` for configuration objects, enums-as-objects, and any place where you want both type checking and literal preservation.

**Branded / nominal types (TS Handbook, advanced).** When two values share a primitive type but must not be interchangeable (`UserId` vs `OrderId`, both `string`), use a branded type: `type UserId = string & { readonly __brand: "UserId" }`. The brand is erased at runtime; the type checker enforces separation. Use branded types for identifiers, units, and security-sensitive strings.

### §D. Async, Promises & Unhandled Rejections

**Floating promises (typescript-eslint `no-floating-promises`, `await-thenable`).** Every promise must be awaited, returned, or explicitly handled with `.catch(...)` or `void promise`. A "floating promise" is a promise that is created and never observed; its rejection is silently lost or, in modern Node.js, crashes the process via `unhandledRejection`.

**`async` inside `forEach` (production guidance).** `Array.prototype.forEach` does not await its callback. Writing `arr.forEach(async (x) => { await f(x); })` starts all the async callbacks concurrently and returns before any of them complete. Use `for...of` with `await`, or `Promise.all(arr.map(async (x) => f(x)))` when concurrent execution is intended.

**`no-misused-promises` (typescript-eslint).** Passing an `async` function where a `void`-returning callback is expected (event handlers, `setTimeout`, `Array.prototype.filter`) is a bug: the promise is unobserved and errors are lost. The `no-misused-promises` rule catches this at the call site. Fix by wrapping the async function (`() => void f()`) or by making the callback synchronous.

**Unhandled promise rejection (Node.js docs).** Node's default `--unhandled-rejections` mode is `throw`: an unhandled promise rejection terminates the process by default. In a long-running service, install a global `process.on('unhandledRejection', handler)` only as a safety net — the primary fix is to handle every rejection at the call site with `try`/`await`/`catch` or `.catch(...)`.

**`await` in `try`/`catch` (production guidance).** Wrap awaited calls that can fail in `try`/`catch`. A rejected promise inside an `async` function propagates as a thrown exception at the `await`; catching it at the call site is the only way to attach context and avoid a rejection that bubbles to the global handler.

**`require-await` (typescript-eslint).** An `async` function with no `await` returns a promise that resolves synchronously. The `async` keyword adds a microtask tick and hides the fact that the function is not actually asynchronous. The `require-await` rule catches this; fix by removing `async` or adding the missing `await`.

### §E. Runtime Safety: Assertions, Indexed Access & Error Typing

**`as` and `!` are unsafe (Google TypeScript Style Guide).** The `as` type assertion and the `!` non-null assertion tell the compiler to trust you. If you are wrong, the error surfaces at runtime, not at compile time. Prefer a runtime check (`if (x !== null)`, `if ('prop' in x)`) with narrowing, and state the reason in a comment. The `satisfies` operator checks a value against a type *without* widening it and is the right tool when you want to validate a literal. The `@typescript-eslint/no-non-null-assertion` rule rejects `!` outside tests.

**`noUncheckedIndexedAccess` (TSConfig docs).** With this flag on, `arr[i]` has type `T | undefined`. That is correct: an array index may be out of bounds. If the surrounding code guarantees the index is valid, narrow with an explicit check (`if (item === undefined) throw ...`) or use `.at(i)` with the same check. Do not `as T` the result.

**Error typing in `catch` (Google TypeScript Style Guide).** In `catch (e)`, `e` has type `unknown` under `useUnknownInCatchVariables` (part of `strict`). Narrow before accessing `.message`: `if (e instanceof Error) { ... }`. Do not cast `e` to `any`.

### §F. Module Hygiene & Imports

**`import type` (Google TypeScript Style Guide; TS handbook).** Use `import type { Foo } from './foo'` for type-only imports. This is erased at compile time, helps bundlers tree-shake, and makes the type-only intent explicit. Use `import { type Foo, bar }` when mixing types and values in one statement. The `@typescript-eslint/consistent-type-imports` rule enforces this.

**Naming (Google TypeScript Style Guide).** `UpperCamelCase` for classes, interfaces, types, enums, decorators, and type parameters. `lowerCamelCase` for variables, parameters, functions, methods, properties, and module aliases. `CONSTANT_CASE` for module-level constants and enum values that are genuinely immutable — not for every `const`. Treat acronyms as words: `loadHttpUrl`, not `loadHTTPURL`.

**Source file structure (Google TypeScript Style Guide).** One blank line between imports and the first declaration. Copyright header (if required by project) as a block comment. JSDoc `@fileoverview` for non-trivial files. Exports after the declarations they export; prefer named exports over default exports (`export class Foo`, not `export default class Foo`).
