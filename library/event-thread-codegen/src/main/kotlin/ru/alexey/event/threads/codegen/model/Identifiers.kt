package ru.alexey.event.threads.codegen.model

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** Qualified names (`kotlin.collections.List`) are allowed wherever a type is named. */
private val QUALIFIED = Regex("${IDENTIFIER.pattern}(\\.${IDENTIFIER.pattern})*")

internal fun requireIdentifier(value: String, what: String) =
    require(IDENTIFIER.matches(value)) { "$what '$value' is not a valid Kotlin identifier" }

internal fun requireTypeName(value: String, what: String) =
    require(QUALIFIED.matches(value)) { "$what '$value' is not a valid Kotlin type name" }
