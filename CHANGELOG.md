# Changelog

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
