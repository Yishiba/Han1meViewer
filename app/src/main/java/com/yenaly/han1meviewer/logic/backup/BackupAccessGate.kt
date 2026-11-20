package com.yenaly.han1meviewer.logic.backup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BackupBusyException : IllegalStateException("Pause downloads and wait for pending tasks before restoring")

/** Multiple downloads may proceed together; restoring requires exclusive access. */
class BackupAccessGate {
    private val mutex = Mutex()
    private var readers = 0
    private val exclusive = MutableStateFlow(false)

    suspend fun <T> download(action: suspend () -> T): T {
        while (true) {
            val entered = mutex.withLock { if (exclusive.value) false else { readers++; true } }
            if (entered) break
            exclusive.first { !it }
        }
        try { return action() }
        finally { withContext(NonCancellable) { mutex.withLock { readers-- } } }
    }

    suspend fun <T> restore(action: suspend () -> T): T {
        mutex.withLock {
            if (exclusive.value || readers != 0) throw BackupBusyException()
            exclusive.value = true
        }
        try { return action() }
        finally { withContext(NonCancellable) { mutex.withLock { exclusive.value = false } } }
    }
}
