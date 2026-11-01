package ru.passport.scanner.data.secure

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Serializes every instance that targets the same private record in this app process. */
internal object SecureRecordLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forKey(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }
}
