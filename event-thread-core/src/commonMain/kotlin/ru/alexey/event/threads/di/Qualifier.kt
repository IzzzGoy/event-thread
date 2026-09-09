package ru.alexey.event.threads.di

import kotlin.jvm.JvmInline

/** Disambiguates multiple [DependencyProvider] registrations for the same type - passed to
 * [DependencyProvider.get]/`register`. Build one with [named]. */
@JvmInline
value class Qualifier(val value: String) {
    companion object {
        /** Builds a [Qualifier] from a plain [name]. */
        fun named(name: String): Qualifier = Qualifier(name)
    }
}
