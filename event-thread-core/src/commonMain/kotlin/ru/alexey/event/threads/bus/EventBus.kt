@file:Suppress("UNCHECKED_CAST")

package ru.alexey.event.threads.bus

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import ru.alexey.event.threads.EventThread
import ru.alexey.event.threads.EventThreadInfo
import kotlin.reflect.KClass

interface Event

interface StrictEvent : Event
interface ExtendableEvent : Event

interface IEventBus {
    val output: SharedFlow<Event>
    val metadata: Map<String, EventThreadInfo>

    operator fun plusAssign(event: Event)

    fun collectToEventBus(events: Flow<Event>)
}

@OptIn(ExperimentalStdlibApi::class)
class EventBus(
    private val coroutineScope: CoroutineScope,
    private val watchers: List<Interceptor>
): AutoCloseable, IEventBus {

    private val channel: Channel<Event> = Channel(Channel.BUFFERED)
    private val subscribers: MutableMap<KClass<out Event>, EventThread<out Event>> = mutableMapOf()
    private val mergedSubscribers: MutableMap<KClass<out Event>, MutableList<EventThread<out Event>>> = mutableMapOf()
    private val _output = MutableSharedFlow<Event>()
    override val output = _output.asSharedFlow()

    override val metadata
        get () = subscribers.map { it.key.simpleName.orEmpty() to it.value.eventMetadatas }.toMap()

    /**
     * Actions come from the primary registration plus any later-merged registrations for
     * [clazz], read live (not snapshotted) so actions chained onto a thread after it was
     * registered (the standard `thread<T>().end { }` DSL pattern) are still picked up.
     */
    private fun actionsFor(thread: EventThread<out Event>?, clazz: KClass<out Event>): List<suspend (Event) -> Unit> {
        val primary = thread?.actions ?: emptyList()
        val merged = mergedSubscribers[clazz]?.flatMap { it.actions } ?: emptyList()
        return primary + merged
    }

    init {
        coroutineScope.launch {
            for (event in channel) {
                launch {
                    watchers.forEach {
                        it(event)
                    }
                }
                launch {
                    when (event) {
                        is StrictEvent -> {
                            actionsFor(subscribers[event::class], event::class).forEach {
                                it(event)
                            }
                        }

                        is ExtendableEvent -> {
                            for ((key, value) in subscribers.entries) {
                                if (key.isInstance(event)) {
                                    actionsFor(value, key).forEach {
                                        it(event)
                                    }
                                }
                            }
                        }

                        else -> {
                            for ((key, value) in subscribers.entries) {
                                if (key.isInstance(event)) {
                                    actionsFor(value, key).forEach {
                                        it(event)
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    _output.emit(event)
                }
            }
        }
    }

    override operator fun plusAssign(event: Event) {
        coroutineScope.launch {
            channel.send(event)
        }
    }

    @PublishedApi
    internal operator fun<T> invoke(clazz: KClass<T>, action: () -> EventThread<T>) where T: Event {
        val thread = action()
        if (thread.eventMetadatas.metadata.override || subscribers[clazz] == null) {
            subscribers[clazz] = thread
        } else {
            mergedSubscribers.getOrPut(clazz) { mutableListOf() }.add(thread)
        }
    }

    @PublishedApi
    internal inline operator fun<reified T> invoke(noinline action: () -> EventThread<T>) where T: Any, T: Event {
        invoke(T::class, action)
    }

    override fun collectToEventBus(events: Flow<Event>) {
        coroutineScope.launch {
            events.collect { event -> this@EventBus += event }
        }
    }

    override fun close() {
        coroutineScope.cancel()
    }

    companion object {
        fun defaultFactory(): EventBus {
            return EventBus(CoroutineScope(Dispatchers.Default), emptyList())
        }
    }
}
