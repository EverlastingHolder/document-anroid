package ru.passport.scanner.data

import ru.passport.scanner.model.PassportDraft
import kotlinx.coroutines.sync.withLock

/** One asynchronous submission held only in this process's RAM. */
sealed interface PendingScanOperation {
    val operationId: String
    val connectionId: String

    /** Exact bytes and idempotency key are retained only while this process lives. */
    class Submitting(
        override val operationId: String,
        override val connectionId: String,
        val idempotencyKey: String,
        val upload: AsyncUploadPayload,
    ) : PendingScanOperation {
        override fun toString(): String = "PendingScanOperation.Submitting([redacted])"
    }

    /** Once accepted, only the opaque job ID is retained in RAM for foreground polling. */
    class Accepted(
        override val operationId: String,
        override val connectionId: String,
        val jobId: String,
    ) : PendingScanOperation {
        override fun toString(): String = "PendingScanOperation.Accepted([redacted])"
    }

    /** Terminal data is applied to live UI state before the server receipt is sent. */
    data class Received(
        override val operationId: String,
        override val connectionId: String,
        val jobId: String,
        val terminal: TerminalJobSnapshot,
        val receiptAcknowledged: Boolean,
    ) : PendingScanOperation {
        override fun toString(): String = "PendingScanOperation.Received([redacted])"
    }
}

sealed interface TerminalJobSnapshot {
    data class Completed(val pipelineVersion: String, val result: PassportDraft) : TerminalJobSnapshot {
        override fun toString(): String = "TerminalJobSnapshot.Completed([redacted])"
    }

    data class Failed(val errorCode: String, val message: String) : TerminalJobSnapshot {
        override fun toString(): String = "TerminalJobSnapshot.Failed([redacted])"
    }
}

class PendingScanStoreException : Exception("Локальное состояние задания временно недоступно.")

/**
 * Process-local one-active-operation state. Implementations compare operation
 * IDs before replacing/clearing state; no implementation writes document data
 * or job state to disk.
 */
interface PendingScanStore {
    suspend fun load(): PendingScanOperation?

    /** Fails safely if another operation is still retained. */
    suspend fun beginSubmitting(operation: PendingScanOperation.Submitting)

    /** Atomically replaces the submitting record and erases its image payload. */
    suspend fun markAccepted(operationId: String, jobId: String): Boolean

    /** Replaces Accepted with an encrypted terminal snapshot, atomically. */
    suspend fun markReceived(operationId: String, jobId: String, terminal: TerminalJobSnapshot): Boolean = false

    /** Marks the server receipt as confirmed without deleting the local result. */
    suspend fun markReceiptAcknowledged(operationId: String, jobId: String): Boolean = false

    /** Clears only the matching operation; a stale caller cannot erase a newer one. */
    suspend fun clear(operationId: String): Boolean
}

/** In-memory only; process death intentionally discards the pending operation. */
class InMemoryPendingScanStore : PendingScanStore {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var current: PendingScanOperation? = null

    override suspend fun load(): PendingScanOperation? = mutex.withLock { current }

    override suspend fun beginSubmitting(operation: PendingScanOperation.Submitting) = mutex.withLock {
        if (current != null) throw PendingScanStoreException()
        current = operation
    }

    override suspend fun markAccepted(operationId: String, jobId: String): Boolean = mutex.withLock {
        when (val operation = current) {
            is PendingScanOperation.Submitting -> if (operation.operationId == operationId) {
                operation.upload.erase()
                current = PendingScanOperation.Accepted(operationId, operation.connectionId, jobId)
                true
            } else false
            is PendingScanOperation.Accepted -> operation.operationId == operationId && operation.jobId == jobId
            else -> false
        }
    }

    override suspend fun markReceived(operationId: String, jobId: String, terminal: TerminalJobSnapshot): Boolean = mutex.withLock {
        val operation = current as? PendingScanOperation.Accepted ?: return@withLock false
        if (operation.operationId != operationId || operation.jobId != jobId) return@withLock false
        current = PendingScanOperation.Received(operationId, operation.connectionId, jobId, terminal, receiptAcknowledged = false)
        true
    }

    override suspend fun markReceiptAcknowledged(operationId: String, jobId: String): Boolean = mutex.withLock {
        val operation = current as? PendingScanOperation.Received ?: return@withLock false
        if (operation.operationId != operationId || operation.jobId != jobId) return@withLock false
        current = operation.copy(receiptAcknowledged = true)
        true
    }

    override suspend fun clear(operationId: String): Boolean = mutex.withLock {
        if (current?.operationId != operationId) return@withLock false
        (current as? PendingScanOperation.Submitting)?.upload?.erase()
        current = null
        true
    }
}

private fun AsyncUploadPayload.erase() = clearInMemory()
