package ru.alexey.thread

import android.content.Context

object ContextProvider {
    private var _provider: (() -> Context)? = null
    val provider: () -> Context
        get() = _provider ?: error("Missing Context")

    operator fun invoke(block: () -> Context) {
        _provider = block
    }
}
