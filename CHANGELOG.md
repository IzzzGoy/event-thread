# Changelog

## 1.2.0

### Added
- **Generated test support** (`event-thread-codegen`, into the test source set; `generateTests`):
  - `<Name>ScopeContract` - extend with your bindings; checks every binding is provided, handlers
    and relations are registered, and every schema `example` holds against your implementation.
  - Schema `examples` per handler (`given`/`reads`/`event`/`expect`/`emits`, JSON turned into
    Kotlin at generation - no serialization needed).
  - `<Name>ScopeProbe` - typed access to a scope's containers for behaviour tests, on the new
    `TypedScopeProbe` (`event-thread-test`: `send`, `await`, `awaitEmitted`, `emitted`).
- `EventHandler.EventCascader.preview(input)` - the events a cascader would emit, without a bus.
- **Versioned schemas**: object root `{ "$schema", "format", "version", "scopes" }` (the 1.1 bare
  array still reads). `format` = schema-language version (newer than the plugin fails with "update
  the plugin"); `version` = semver of your contract.
- **Compatibility check** `checkScopesCompatibility` (part of `check`, with `baseline`): classifies
  changes as MAJOR/MINOR/PATCH and fails on an insufficient version bump.
- Generated files are stamped with plugin/schema/format versions; generated `ScopesSchema` object.
- **JSON Schema** of the format (`event-thread-codegen/scopes.schema.json`, derived from the parser's
  serializers) for editors and AI agents.
- **Reports** in `build/reports/eventThread`: Mermaid event-flow diagram (`scopes.md`), per-scope
  implementation task files (`tasks/<Scope>.md`).
- Cascade-loop detection (warnings; `strictSchema` makes them errors).
- `ScopeHolder.activeInstances` - one entry per live scope instance, with its params. Holder
  metadata snapshots include instance params.

- `ConfigSource.document(context)` - open, defaults to wrapping `schema`, so custom sources keep
  working; the built-in file/URL sources return the whole versioned document through it.
- Generated contract test `eventFlowMatchesSchema`: the live scope's event flow (via
  `event-thread-test`'s event graph) is exactly the schema's; `handlersAreRegistered` now checks the
  number of handlers per event.
- `TypedScopeProbe` records into a `ScenarioRecorder`: `probe.scenario { expectEvents(...) }`.
- Generated files carry `@file:Suppress("unused", "RedundantVisibilityModifier")`.
- `TESTING.md` (event-thread-codegen): what generated test support runs and checks.

### Fixed
- Event bus metadata listed only the first handler of an event: with several (`thread<E>()` twice,
  or several typed handlers), the others' actions and outputs were missing - and so from the
  event-graph tooling.

### Deprecated
- `ScopeHolder.activeMetadata` - one entry per key, losing instances; use `activeInstances`.

## 1.1.0

### Added
- **Typed scopes** (`event-thread-core`, `scopeholder.typed`): scopes as compile-time-checked
  classes, next to the DSL.
  - `ScopeTyped<P>` keyed by `ScopeKey<P>`, wired in `ScopeWiring` (`container`, `on`, `handler`).
  - `DatacontainerTyped<T>`: class-based containers with `transforms` and `onEach`.
  - `EventHandler.StateProcessor` / `EventCascader` (declared `outputs`, enforced like
    `output<T>()`) / `SideEffect`; several handlers per event, run in declaration order.
  - `ContainerSnapshot`: read-only, typed access to another container's state.
  - `ScopeHolderBuilder.scopeTyped(key, factory)` registers typed scopes in a `ScopeHolder`.
  - Containers, handlers, the bus and scope params can come from the `DependencyProvider`.
- **Scope instances per params**: `load(key, params)` with different params gives independent
  instances. New `find(key, params)`, `free(key, params)` and `free(scope)`. The typed Compose
  `scope(target, params)` mounts, counts and frees per instance.
- **`event-thread-codegen`**: Gradle plugin `io.github.izzzgoy.event-thread.codegen` generating
  typed scopes from a JSON schema (resources, build script, URL, or a custom `ConfigSource`).
  - Generates keys, scopes and their wiring; container and handler bases; a per-scope
    `<Name>ScopeBindings` interface (completeness checked by the compiler) plus a
    DependencyProvider adapter; `dependsOn`/`consume` fragments and `register<Name>Scope()`.
  - Verifies the schema before generating: references, name collisions, and event flow (every
    cascaded event reaches a handler).
  - See `library/event-thread-codegen/README.md`.

### Changed
- `ScopeHolder.load` returns the live instance if it's already loaded, instead of building a
  second scope under the same key.
- `ScopeHolder.find(key)` throws if several per-params instances of the key are live. Use
  `find(key, params)` instead.
- `free(key)` frees every live instance of the key.
- `include(...)` merges fragments' `dependsOn`/`implements`/`consume` with relations declared
  directly in `scopeHolder { }`. Before, it replaced them.
- `ScopeCounter`'s keys widened from `String` to `Any`. Source-compatible, but not binary-compatible.
- `ScopeHolder`'s constructor gained a parameter with a default. Construct it through
  `scopeHolder { }`.

### Fixed
- Loading a live key twice left a duplicate active scope behind. `free` closed only one of them,
  leaking the other's bus and routing collector.
- `include` silently dropped hand-written `dependsOn`/`consume` declarations for the same scope
  or event.
- Builds that include the library as a composite build could fail to restore the configuration
  cache. Publishing is now configured only when the library is the root build.
- Published POM name typo for `event-thread-cache`.

### Build
- Repository split: `library/` is the published build; the repo root is the sample app, which
  includes it as a composite build. CI runs library and sample tests before publishing.
