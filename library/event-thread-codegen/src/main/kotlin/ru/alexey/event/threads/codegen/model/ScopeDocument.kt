package ru.alexey.event.threads.codegen.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A schema file: its scopes plus two versions, which mean different things.
 * - [format] - the version of the schema *language* this document is written in (`major.minor`).
 *   A plugin reads every format with its own major and a minor up to its own
 *   ([ScopeFormat.CURRENT]); a newer one fails with "update the plugin", since it may use fields
 *   this plugin doesn't know.
 * - [version] - the semver of *your* contract. Compared against a baseline (the schema as last
 *   released), it must be bumped as the changes require - see the compatibility check.
 *
 * Formats: 1.0 - scopes, handlers, relations, examples; 1.1 - handler `rule`s, container `initial`s. The earliest
 * shape - a bare array of scopes - still reads, as the current format with no version.
 */
@Serializable
data class ScopeDocument(
    /** For editors: the JSON Schema this document follows. Not used by the plugin. */
    @SerialName("\$schema") val schema: String? = null,
    val format: String = ScopeFormat.CURRENT,
    val version: String? = null,
    val scopes: List<ScopeModel>,
) {
    init {
        ScopeFormat.requireSupported(format)
        version?.let { SemVer.parse(it) }
        // Declaring the format a feature needs is what makes an older plugin fail with "update"
        // instead of not knowing the field.
        val ruled = scopes.flatMap { s -> s.handlers.filter { it.rule != null }.map { "${s.name}.${it.input.simpleName}" } + s.initial.keys.map { "${s.name}.initial.$it" } }
        require(ruled.isEmpty() || ScopeFormat.atLeast(format, "1.1")) {
            "Handler rules and initial values need \"format\": \"1.1\" (this document is $format) - used by $ruled"
        }
    }
}

/** The schema-language version this plugin speaks. */
object ScopeFormat {
    const val CURRENT = "1.1"

    /** URL of the JSON Schema for the current format, for a document's `$schema`. */
    const val SCHEMA_URL = "https://raw.githubusercontent.com/IzzzGoy/event-thread/main/library/event-thread-codegen/scopes.schema.json"

    fun requireSupported(format: String) {
        val (major, minor) = parse(format)
        val (currentMajor, currentMinor) = parse(CURRENT)
        require(major == currentMajor) {
            "Schema format $format is incompatible with this plugin (format $CURRENT) - a major format change needs the matching plugin"
        }
        require(minor <= currentMinor) {
            "Schema format $format is newer than this plugin understands ($CURRENT) - update event-thread-codegen"
        }
    }

    fun atLeast(format: String, required: String): Boolean {
        val (major, minor) = parse(format)
        val (rMajor, rMinor) = parse(required)
        return major > rMajor || (major == rMajor && minor >= rMinor)
    }

    private fun parse(format: String): Pair<Int, Int> {
        val parts = format.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        require(major != null && parts.size <= 2 && (parts.size == 1 || parts[1].toIntOrNull() != null)) {
            "Schema format '$format' isn't `major.minor`"
        }
        return major to minor
    }
}

/** `major.minor.patch[-prerelease]` - enough of SemVer 2.0 to order versions and check bumps. */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val prerelease: String? = null) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        .takeIf { it != 0 }
        // Same numbers: a prerelease sorts before the release.
        ?: when {
            prerelease == other.prerelease -> 0
            prerelease == null -> 1
            other.prerelease == null -> -1
            else -> prerelease.compareTo(other.prerelease)
        }

    override fun toString() = "$major.$minor.$patch" + (prerelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?""")

        fun parse(text: String): SemVer {
            val m = requireNotNull(PATTERN.matchEntire(text)) { "Schema version '$text' isn't semver (major.minor.patch)" }
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
        }
    }
}
