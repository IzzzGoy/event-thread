package ru.alexey.event.threads.di

import kotlin.jvm.JvmInline

@JvmInline
value class Qualifier(val value: String) {
    companion object {
        fun named(name: String): Qualifier = Qualifier(name)
    }
}
