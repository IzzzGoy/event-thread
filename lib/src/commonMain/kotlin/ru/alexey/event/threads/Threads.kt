package ru.alexey.event.threads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.reflect.KClass

@OptIn(ExperimentalStdlibApi::class)
abstract class  EventThread<T>: AutoCloseable where T: Event {

    private val actionsMutable: MutableList<(Event) -> Unit> = mutableListOf()

    val actions: List<(Event) -> Unit>
        get() = actionsMutable

    operator fun invoke(block: (Event) -> Unit) {
        actionsMutable.add(block)
    }

}

