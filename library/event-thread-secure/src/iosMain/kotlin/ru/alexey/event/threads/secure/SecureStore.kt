package ru.alexey.event.threads.secure

import com.liftric.kvault.KVault

actual fun secureStore(): KVault = KVault()