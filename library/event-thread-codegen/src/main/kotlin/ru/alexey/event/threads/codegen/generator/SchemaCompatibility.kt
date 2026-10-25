package ru.alexey.event.threads.codegen.generator

import ru.alexey.event.threads.codegen.model.HandlerModel
import ru.alexey.event.threads.codegen.model.HandlerOutput
import ru.alexey.event.threads.codegen.model.RemoteApiModel
import ru.alexey.event.threads.codegen.model.ScopeDocument
import ru.alexey.event.threads.codegen.model.ScopeModel
import ru.alexey.event.threads.codegen.model.SemVer

/**
 * Semver for schemas: how a schema change affects what's generated, and so which version bump
 * it needs against the baseline (the schema as last released).
 *
 * - **MAJOR** - something existing changes meaning or disappears: a scope, handler, container or
 *   relation removed; a state, params, event or output type changed; a handler's reads changed
 *   (its `Deps` signature); `override`/`onMismatch` changed (dispatch behaviour).
 * - **MINOR** - additions: scopes, handlers, containers (derived too), `dependsOn`/`consumes`
 *   entries. Nothing existing changes; regenerating adds members to bindings interfaces, so
 *   implementations must be extended - the compiler lists exactly what.
 * - **PATCH** - nothing generated changes its signature or behaviour: descriptions, `privacy`,
 *   examples.
 *
 * In `0.y.z`, as in semver, anything may change: MAJOR changes need a minor bump, others a patch.
 */
object SchemaCompatibility {

    enum class Level { NONE, PATCH, MINOR, MAJOR }

    data class Change(val level: Level, val description: String)

    data class Result(val changes: List<Change>, val required: Level, val problem: String?) {
        fun render(): String = buildString {
            appendLine(problem ?: "Schema version is consistent with its changes (requires ${required.name.lowercase()} or none).")
            Level.entries.reversed().forEach { level ->
                val of = changes.filter { it.level == level }
                if (of.isNotEmpty()) {
                    appendLine("${level.name}:")
                    of.forEach { appendLine(" - ${it.description}") }
                }
            }
        }
    }

    fun check(baseline: ScopeDocument, current: ScopeDocument): Result {
        val changes = diff(baseline.scopes, current.scopes) + remote(baseline.remote, current.remote)
        val required = changes.maxOfOrNull { it.level } ?: Level.NONE
        val problem = versionProblem(baseline.version, current.version, required)
        return Result(changes, required, problem)
    }

    /** Remote APIs: endpoints are interface members - removing or changing one breaks implementers. */
    fun remote(before: List<RemoteApiModel>, after: List<RemoteApiModel>): List<Change> {
        val out = mutableListOf<Change>()
        val old = before.associateBy { it.name }
        val new = after.associateBy { it.name }
        (old.keys - new.keys).forEach { out += Change(Level.MAJOR, "remote API '$it' removed") }
        (new.keys - old.keys).forEach { out += Change(Level.MINOR, "remote API '$it' added") }
        (old.keys intersect new.keys).forEach { name ->
            val a = old.getValue(name).endpoints.associateBy { it.name }
            val b = new.getValue(name).endpoints.associateBy { it.name }
            (a.keys - b.keys).forEach { out += Change(Level.MAJOR, "remote $name.$it removed") }
            (b.keys - a.keys).forEach { out += Change(Level.MINOR, "remote $name.$it added") }
            (a.keys intersect b.keys).forEach { e ->
                val x = a.getValue(e)
                val y = b.getValue(e)
                if (x.args != y.args || x.response != y.response) out += Change(Level.MAJOR, "remote $name.$e: signature changed")
                if (x.method != y.method || x.path != y.path || x.body != y.body) out += Change(Level.PATCH, "remote $name.$e: HTTP mapping changed")
                if (x.description != y.description) out += Change(Level.PATCH, "remote $name.$e: description changed")
            }
        }
        return out
    }

    fun diff(before: List<ScopeModel>, after: List<ScopeModel>): List<Change> {
        val out = mutableListOf<Change>()
        val old = before.associateBy { it.name }
        val new = after.associateBy { it.name }
        (old.keys - new.keys).forEach { out += Change(Level.MAJOR, "scope '$it' removed") }
        (new.keys - old.keys).forEach { out += Change(Level.MINOR, "scope '$it' added") }
        (old.keys intersect new.keys).forEach { name -> out += scope(old.getValue(name), new.getValue(name)) }
        return out
    }

    private fun scope(a: ScopeModel, b: ScopeModel): List<Change> {
        val out = mutableListOf<Change>()
        val s = "'${a.name}'"
        if (a.params != b.params) out += Change(Level.MAJOR, "$s params changed")
        if (a.description != b.description) out += Change(Level.PATCH, "$s description changed")
        out += setChange("$s dependsOn", a.dependsOn, b.dependsOn)
        out += setChange("$s consumes", a.consumes, b.consumes)

        val oldDerived = a.derived.associateBy { it.state }
        val newDerived = b.derived.associateBy { it.state }
        (oldDerived.keys - newDerived.keys).forEach { out += Change(Level.MAJOR, "$s derived ${label(it)} removed") }
        (newDerived.keys - oldDerived.keys).forEach { out += Change(Level.MINOR, "$s derived ${label(it)} added") }
        (oldDerived.keys intersect newDerived.keys).filter { oldDerived[it] != newDerived[it] }
            .forEach { out += Change(Level.MAJOR, "$s derived ${label(it)} computed from other containers") }

        (a.initial.keys + b.initial.keys).distinct().forEach { name ->
            val x = a.initial[name]
            val y = b.initial[name]
            when {
                x == y -> Unit
                x == null -> out += Change(Level.MINOR, "$s initial $name added")
                y == null -> out += Change(Level.MAJOR, "$s initial $name removed - bindings relying on its default no longer compile")
                else -> out += Change(Level.PATCH, "$s initial $name changed")
            }
        }

        val oldHandlers = a.handlers.associateBy(::handlerKey)
        val newHandlers = b.handlers.associateBy(::handlerKey)
        (oldHandlers.keys - newHandlers.keys).forEach { out += Change(Level.MAJOR, "$s handler $it removed") }
        (newHandlers.keys - oldHandlers.keys).forEach { out += Change(Level.MINOR, "$s handler $it added") }
        (oldHandlers.keys intersect newHandlers.keys).forEach { key ->
            val x = oldHandlers.getValue(key)
            val y = newHandlers.getValue(key)
            val h = "$s handler $key"
            if (x.input != y.input) out += Change(Level.MAJOR, "$h: input event changed")
            val xr = x.output as? HandlerOutput.Request
            val yr = y.output as? HandlerOutput.Request
            if (xr != null && yr != null) {
                // What the handler is (its call, container, events) vs how it behaves.
                if (xr.call != yr.call || xr.into != yr.into || xr.emits != yr.emits) out += Change(Level.MAJOR, "$h: request call, container or emitted events changed")
                if (xr.args != yr.args || xr.onSuccess != yr.onSuccess || xr.onFailure != yr.onFailure || xr.policy != yr.policy || xr.onMismatch != yr.onMismatch) {
                    out += Change(Level.PATCH, "$h: request behaviour changed")
                }
            } else if (x.output != y.output) {
                out += Change(Level.MAJOR, "$h: output changed")
            }
            if (x.reads != y.reads) out += Change(Level.MAJOR, "$h: reads changed")
            if (x.override != y.override) out += Change(Level.MAJOR, "$h: override changed")
            if (x.privacy != y.privacy) out += Change(Level.PATCH, "$h: privacy changed")
            if (x.examples != y.examples) out += Change(Level.PATCH, "$h: examples changed")
            // A rule is the binding's default: gaining one is a feature, losing one makes every
            // binding that relied on it abstract again.
            when {
                x.rule == y.rule -> Unit
                x.rule == null -> out += Change(Level.MINOR, "$h: rule added")
                y.rule == null -> out += Change(Level.MAJOR, "$h: rule removed - bindings relying on its default no longer compile")
                else -> out += Change(Level.PATCH, "$h: rule changed")
            }
        }
        return out
    }

    private fun setChange(what: String, old: List<String>, new: List<String>): List<Change> =
        (old - new.toSet()).map { Change(Level.MAJOR, "$what '$it' removed") } +
            (new - old.toSet()).map { Change(Level.MINOR, "$what '$it' added") }

    /** A handler's identity across versions: its generated base name. */
    private fun handlerKey(h: HandlerModel): String = h.name ?: h.input.simpleName + when (h.output) {
        is HandlerOutput.Data -> "StateProcessor"
        is HandlerOutput.Event -> "EventCascader"
        is HandlerOutput.Request -> "Request"
        HandlerOutput.None -> "SideEffect"
    }

    private fun label(state: ru.alexey.event.threads.codegen.model.ArgDef) = state.fieldName ?: state.toString()

    private fun versionProblem(baseline: String?, current: String?, required: Level): String? {
        if (baseline == null || current == null) {
            return if (required == Level.NONE) null
            else "Schema changed (${required.name.lowercase()}) but ${if (current == null) "it" else "the baseline"} has no `version` to compare"
        }
        val b = SemVer.parse(baseline)
        val c = SemVer.parse(current)
        if (c < b) return "Schema version $c is lower than the baseline's $b"
        val ok = when (required) {
            Level.NONE -> true
            Level.PATCH -> c > b
            Level.MINOR -> if (b.major == 0) c > b else c.major > b.major || (c.major == b.major && c.minor > b.minor)
            Level.MAJOR -> if (b.major == 0) c.major > 0 || c.minor > b.minor else c.major > b.major
        }
        if (ok) return null
        val next = when (required) {
            Level.MAJOR -> if (b.major == 0) SemVer(0, b.minor + 1, 0) else SemVer(b.major + 1, 0, 0)
            Level.MINOR -> if (b.major == 0) SemVer(0, b.minor, b.patch + 1) else SemVer(b.major, b.minor + 1, 0)
            else -> SemVer(b.major, b.minor, b.patch + 1)
        }
        return "Schema changes need a ${required.name.lowercase()} version bump from $b (at least $next), but the version is $c"
    }
}
