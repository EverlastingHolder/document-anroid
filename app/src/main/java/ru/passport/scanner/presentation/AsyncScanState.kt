package ru.passport.scanner.presentation

/** UI-safe progress only; job IDs, idempotency keys, payloads, and credentials stay out of UI state. */
data class AsyncScanState(
    val phase: Phase = Phase.IDLE,
    val errorCode: String? = null,
    val message: String? = null,
) {
    enum class Phase {
        IDLE,
        SUBMITTING,
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED,
        CONNECTION_REQUIRED,
        RECOVERY_REQUIRED,
    }

    val isActive: Boolean
        get() = phase in setOf(Phase.SUBMITTING, Phase.QUEUED, Phase.PROCESSING)

    /** A saved operation still blocks a second scan when connection/recovery needs attention. */
    val blocksNewScan: Boolean
        get() = isActive || phase in setOf(Phase.CONNECTION_REQUIRED, Phase.RECOVERY_REQUIRED)

    companion object {
        val Idle = AsyncScanState()
        val Submitting = AsyncScanState(Phase.SUBMITTING)
        val Queued = AsyncScanState(Phase.QUEUED)
        val Processing = AsyncScanState(Phase.PROCESSING)
        val Completed = AsyncScanState(Phase.COMPLETED)
        val ConnectionRequired = AsyncScanState(Phase.CONNECTION_REQUIRED)
        val RecoveryRequired = AsyncScanState(Phase.RECOVERY_REQUIRED)

        fun Failed(code: String, message: String) = AsyncScanState(Phase.FAILED, code, message)
    }
}
