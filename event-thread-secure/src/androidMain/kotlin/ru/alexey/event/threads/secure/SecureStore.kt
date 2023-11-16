package ru.alexey.event.threads.secure

import com.liftric.kvault.KVault
import ru.alexey.event.threads.ContextProvider

actual fun secureStore(): KVault = KVault(ContextProvider.provider())