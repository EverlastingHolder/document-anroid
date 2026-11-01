package ru.passport.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ru.passport.scanner.data.AcceptedJob
import ru.passport.scanner.data.ApiConnection
import ru.passport.scanner.data.AsyncFailureCategory
import ru.passport.scanner.data.AsyncJobRepositoryException
import ru.passport.scanner.data.AsyncUploadPayload
import ru.passport.scanner.data.JobStatus
import ru.passport.scanner.data.PendingScanOperation
import ru.passport.scanner.data.PendingScanStore
import ru.passport.scanner.data.PendingScanStoreException
import ru.passport.scanner.data.TerminalJobSnapshot
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PassportRepositoryException
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.FieldValidationError
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValidator
import ru.passport.scanner.model.ScanImage
import java.util.UUID

private val DEFAULT_POLL_DELAYS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 5_000)

private suspend fun defaultAsyncPollPause(attempt: Int) {
    delay(DEFAULT_POLL_DELAYS_MILLIS[attempt.coerceIn(0, DEFAULT_POLL_DELAYS_MILLIS.lastIndex)])
}

data class ShareRequest(val id: Long, val archive: ByteArray)

data class ScanUiState(
    val image: ScanImage? = null,
    val isRecognizing: Boolean = false,
    val isExporting: Boolean = false,
    val draft: PassportDraft? = null,
    val edits: Map<PassportField, String> = emptyMap(),
    val validationErrors: List<FieldValidationError> = emptyList(),
    val error: String? = null,
    val shareRequest: ShareRequest? = null,
    val editRevision: Long = 0L,
    val asyncScan: AsyncScanState = AsyncScanState.Idle,
) {
    fun currentValues(): Map<PassportField, String> = PassportField.entries.associateWith { field ->
        edits[field] ?: draft?.values?.get(field)?.value.orEmpty()
    }

    fun value(field: PassportField): String = edits[field] ?: draft?.values?.get(field)?.value.orEmpty()
}

class ScanViewModel(
    private val repository: PassportRepository,
    private val createArchive: suspend (PassportSnapshot) -> ByteArray,
    private val pendingScanStore: PendingScanStore? = null,
    private val apiConnectionProvider: (suspend () -> ApiConnection?)? = null,
    private val pollPause: suspend (Int) -> Unit = ::defaultAsyncPollPause,
    private val onAccessDenied: suspend (connectionId: String) -> Unit = {},
    private val savedConnectionIdProvider: suspend () -> String? = { apiConnectionProvider?.invoke()?.connectionId },
) : ViewModel() {
    class Factory(
        private val repository: PassportRepository,
        private val createArchive: suspend (PassportSnapshot) -> ByteArray,
        private val pendingScanStore: PendingScanStore? = null,
        private val apiConnectionProvider: (suspend () -> ApiConnection?)? = null,
        private val onAccessDenied: suspend (connectionId: String) -> Unit = {},
        private val savedConnectionIdProvider: suspend () -> String? = { apiConnectionProvider?.invoke()?.connectionId },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ScanViewModel::class.java))
            return ScanViewModel(
                repository = repository,
                createArchive = createArchive,
                pendingScanStore = pendingScanStore,
                apiConnectionProvider = apiConnectionProvider,
                onAccessDenied = onAccessDenied,
                savedConnectionIdProvider = savedConnectionIdProvider,
            ) as T
        }
    }

    private val mutableState = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private var operationGeneration = 0L
    private var recognitionJob: Job? = null
    private var asyncJob: Job? = null
    private var exportJob: Job? = null
    private var nextShareRequestId = 0L
    private var foreground = false
    private var activeOperation: PendingScanOperation? = null
    private var submittingOperationId: String? = null
    private var operationToReplaceId: String? = null

    fun pickImage(image: ScanImage) {
        if (mutableState.value.asyncScan.blocksNewScan) return
        retainTerminalOperationForReplacement()
        operationGeneration++
        recognitionJob?.cancel()
        asyncJob?.cancel()
        exportJob?.cancel()
        mutableState.value.image?.eraseInMemory()
        mutableState.value = ScanUiState(image = image, asyncScan = AsyncScanState.Idle)
    }

    fun recognize() {
        if (pendingScanStore != null || apiConnectionProvider != null) {
            if (pendingScanStore == null || apiConnectionProvider == null) {
                mutableState.value = mutableState.value.copy(
                    isRecognizing = false,
                    asyncScan = AsyncScanState.RecoveryRequired,
                    error = "Асинхронная отправка не настроена. Обновите подключение и повторите попытку.",
                )
                return
            }
            startAsyncSubmission()
            return
        }
        recognizeSynchronously()
    }

    private fun recognizeSynchronously() {
        val image = mutableState.value.image ?: run {
            mutableState.value = mutableState.value.copy(error = "Сначала выберите изображение.")
            return
        }
        recognitionJob?.cancel()
        exportJob?.cancel()
        val generation = ++operationGeneration
        val revisionAtRequest = mutableState.value.editRevision
        val requestId = UUID.randomUUID().toString()
        fun canApplyRecognitionResult(current: ScanUiState): Boolean =
            generation == operationGeneration &&
                current.image?.id == image.id &&
                current.editRevision == revisionAtRequest

        mutableState.value.shareRequest?.archive?.fill(0)
        mutableState.value = mutableState.value.copy(
            isRecognizing = true,
            isExporting = false,
            draft = null,
            edits = emptyMap(),
            validationErrors = emptyList(),
            shareRequest = null,
            error = null,
        )
        recognitionJob = viewModelScope.launch {
            try {
                val draft = repository.recognize(image, requestId)
                val current = mutableState.value
                if (canApplyRecognitionResult(current)) {
                    mutableState.value = current.copy(isRecognizing = false, draft = draft)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PassportRepositoryException) {
                val current = mutableState.value
                if (canApplyRecognitionResult(current)) {
                    mutableState.value = current.copy(
                        isRecognizing = false,
                        error = failure.message ?: "Не удалось распознать изображение.",
                    )
                }
            } catch (_: Exception) {
                val current = mutableState.value
                if (canApplyRecognitionResult(current)) {
                    mutableState.value = current.copy(
                        isRecognizing = false,
                        error = "Не удалось распознать изображение. Проверьте соединение и повторите запрос.",
                    )
                }
            } finally {
                val current = mutableState.value
                if (generation == operationGeneration && current.image?.id == image.id && current.isRecognizing) {
                    mutableState.value = current.copy(isRecognizing = false)
                }
            }
        }
    }

    fun retry() {
        if (pendingScanStore != null || apiConnectionProvider != null) {
            if (pendingScanStore == null || apiConnectionProvider == null) {
                recognize()
                return
            }
            when (mutableState.value.asyncScan.phase) {
                AsyncScanState.Phase.FAILED -> startAsyncSubmission()
                AsyncScanState.Phase.COMPLETED -> return
                else -> if (activeOperation == null) startAsyncSubmission() else resumePendingOperation()
            }
            return
        }
        recognizeSynchronously()
    }

    /** Call from the Activity's started/stopped lifecycle; it pauses local HTTP only. */
    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        if (!isForeground) {
            if (asyncJob?.isActive == true) operationGeneration++
            asyncJob?.cancel()
            asyncJob = null
            if (mutableState.value.asyncScan.blocksNewScan) {
                mutableState.value = mutableState.value.copy(isRecognizing = false)
            }
            return
        }
        if (pendingScanStore == null || asyncJob?.isActive == true) return
        if (operationToReplaceId != null) {
            asyncJob = viewModelScope.launch {
                try {
                    clearReplacedOperationIfNeeded(pendingScanStore)
                } catch (_: Exception) {
                    showRecoveryRequired("Не удалось удалить завершённое задание. Повторите попытку.")
                }
            }
            return
        }
        resumePendingOperation()
    }

    /** Called after registration/profile restoration succeeds, even if already foreground. */
    fun onConnectionReady() {
        if (foreground) resumePendingOperation()
    }

    /** Fence any in-flight response when the selected server/credential profile changes. */
    fun onApiConnectionChanged() {
        if (activeOperation == null && operationToReplaceId == null) return
        operationGeneration++
        asyncJob?.cancel()
        asyncJob = null
        showConnectionRequired("Подключение изменилось. Вернитесь к исходному подключению, чтобы продолжить задание.")
    }

    fun abandonPendingOperation() {
        viewModelScope.launch {
            if (!abandonPendingOperationAndAwait()) {
                showRecoveryRequired("Не удалось остановить отслеживание задания. Повторите попытку.")
            }
        }
    }

    /**
     * Fences in-flight work, waits for it to stop, then clears only its exact
     * journal record. A true result permits changing the selected server;
     * the remote job itself is not cancelled.
     */
    suspend fun abandonPendingOperationAndAwait(): Boolean {
        val capturedOperationId = activeOperation?.operationId
            ?: submittingOperationId
            ?: operationToReplaceId
        operationGeneration++
        asyncJob?.cancelAndJoin()
        asyncJob = null
        recognitionJob?.cancel()
        exportJob?.cancel()

        val store = pendingScanStore
        if (store != null) {
            try {
                val stored = store.load()
                val operationId = capturedOperationId ?: stored?.operationId
                if (operationId != null) {
                    store.clear(operationId)
                    val afterClear = store.load()
                    if (afterClear != null) return false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
        }

        activeOperation = null
        submittingOperationId = null
        operationToReplaceId = null
        mutableState.value = ScanUiState()
        return true
    }

    private fun startAsyncSubmission() {
        val image = mutableState.value.image ?: run {
            mutableState.value = mutableState.value.copy(error = "Сначала выберите изображение.")
            return
        }
        if (mutableState.value.asyncScan.blocksNewScan &&
            mutableState.value.asyncScan.phase != AsyncScanState.Phase.FAILED &&
            activeOperation != null
        ) return
        val store = pendingScanStore ?: return
        val connectionProvider = apiConnectionProvider ?: return
        foreground = true
        if (mutableState.value.asyncScan.phase == AsyncScanState.Phase.FAILED) {
            retainTerminalOperationForReplacement()
        }
        operationGeneration++
        val generation = operationGeneration
        val revisionAtRequest = mutableState.value.editRevision
        recognitionJob?.cancel()
        asyncJob?.cancel()
        exportJob?.cancel()
        mutableState.value.shareRequest?.archive?.fill(0)
        mutableState.value = mutableState.value.copy(
            isRecognizing = true,
            isExporting = false,
            draft = null,
            edits = emptyMap(),
            validationErrors = emptyList(),
            shareRequest = null,
            error = null,
            asyncScan = AsyncScanState.Submitting,
        )
        asyncJob = viewModelScope.launch {
            try {
                clearReplacedOperationIfNeeded(store)
                val existing = store.load()
                if (existing != null) {
                    if (activeOperation?.operationId != existing.operationId) {
                        activeOperation = existing
                    }
                    resumeOperation(existing, connectionProvider, generation, revisionAtRequest)
                    return@launch
                }
                val connection = connectionProvider()
                if (connection == null) {
                    showConnectionRequired("Настройте подключение к серверу и повторите попытку.")
                    return@launch
                }
                val imageBytes = image.bytes
                val payload = try {
                    AsyncUploadPayload(imageBytes, image.mimeType)
                } finally {
                    imageBytes.fill(0)
                }
                val operation = PendingScanOperation.Submitting(
                    operationId = UUID.randomUUID().toString(),
                    connectionId = connection.connectionId,
                    idempotencyKey = UUID.randomUUID().toString(),
                    upload = payload,
                )
                submittingOperationId = operation.operationId
                store.beginSubmitting(operation)
                activeOperation = operation
                submittingOperationId = null
                if (!isCurrent(generation, operation.operationId)) return@launch
                submitUntilAccepted(operation, connectionProvider, generation, revisionAtRequest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == operationGeneration) showRecoveryRequired("Не удалось подготовить отправку в текущем сеансе. Повторите попытку.")
            } finally {
                if (generation == operationGeneration) submittingOperationId = null
                if (generation == operationGeneration &&
                    mutableState.value.asyncScan.phase == AsyncScanState.Phase.SUBMITTING &&
                    !foreground
                ) {
                    mutableState.value = mutableState.value.copy(isRecognizing = false)
                }
            }
        }
    }

    private fun resumePendingOperation() {
        val store = pendingScanStore ?: return
        if (asyncJob?.isActive == true) return
        operationGeneration++
        val generation = operationGeneration
        val revision = mutableState.value.editRevision
        asyncJob = viewModelScope.launch {
            try {
                val operation = activeOperation ?: store.load()
                if (operation == null) {
                    activeOperation = null
                    if (mutableState.value.asyncScan.phase == AsyncScanState.Phase.SUBMITTING) {
                        showRecoveryRequired("Отправка больше не доступна в текущем сеансе. Выберите изображение и начните заново.")
                    }
                    return@launch
                }
                activeOperation = operation
                if (operation is PendingScanOperation.Received) {
                    restoreReceived(operation, preserveCurrentDraft = shouldPreserveCurrentDraft())
                    if (!operation.receiptAcknowledged) retryReceiptWhenReady(operation, generation)
                    return@launch
                }
                val connectionProvider = apiConnectionProvider ?: run {
                    showConnectionRequired("Подключитесь к серверу, чтобы продолжить задание.")
                    return@launch
                }
                resumeOperation(operation, connectionProvider, generation, revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == operationGeneration) showRecoveryRequired("Не удалось продолжить задание в текущем сеансе. Повторите попытку.")
            }
        }
    }

    private suspend fun resumeOperation(
        operation: PendingScanOperation,
        connectionProvider: suspend () -> ApiConnection?,
        generation: Long,
        revisionAtRequest: Long,
    ) {
        if (operation is PendingScanOperation.Received) {
            restoreReceived(operation, preserveCurrentDraft = shouldPreserveCurrentDraft())
            if (!operation.receiptAcknowledged) retryReceiptWhenReady(operation, generation)
            return
        }
        val connection = connectionProvider()
        if (connection == null || connection.connectionId != operation.connectionId) {
            showConnectionRequired("Вернитесь к исходному подключению, чтобы продолжить задание.")
            return
        }
        when (operation) {
            is PendingScanOperation.Submitting -> {
                setAsyncStatus(AsyncScanState.Submitting, recognizing = true)
                submitUntilAccepted(operation, connectionProvider, generation, revisionAtRequest)
            }
            is PendingScanOperation.Accepted -> {
                setAsyncStatus(AsyncScanState.Queued, recognizing = true)
                pollUntilTerminal(operation, connectionProvider, generation, revisionAtRequest)
            }
            is PendingScanOperation.Received -> Unit
        }
    }

    private suspend fun submitUntilAccepted(
        operation: PendingScanOperation.Submitting,
        connectionProvider: suspend () -> ApiConnection?,
        generation: Long,
        revisionAtRequest: Long,
    ) {
        var retryAttempt = 0
        while (foreground && isCurrent(generation, operation.operationId)) {
            val connection = connectionProvider()
            if (connection == null || connection.connectionId != operation.connectionId) {
                showConnectionRequired("Вернитесь к исходному подключению, чтобы повторить отправку.")
                return
            }
            try {
                val accepted = repository.submit(
                    operation.upload,
                    requestId = UUID.randomUUID().toString(),
                    idempotencyKey = operation.idempotencyKey,
                    connection = connection,
                )
                if (!isCurrent(generation, operation.operationId)) return
                val store = pendingScanStore ?: return
                if (!store.markAccepted(operation.operationId, accepted.jobId)) return
                val acceptedOperation = PendingScanOperation.Accepted(
                    operationId = operation.operationId,
                    connectionId = operation.connectionId,
                    jobId = accepted.jobId,
                )
                activeOperation = acceptedOperation
                mutableState.value.image?.eraseInMemory()
                mutableState.value = mutableState.value.copy(image = null)
                if (!isCurrentConnection(operation.connectionId, connectionProvider)) {
                    showConnectionRequired("Вернитесь к исходному подключению, чтобы продолжить задание.")
                    return
                }
                val initialStatus = when (accepted.status) {
                    ru.passport.scanner.data.JobState.QUEUED -> AsyncScanState.Queued
                    ru.passport.scanner.data.JobState.PROCESSING -> AsyncScanState.Processing
                    ru.passport.scanner.data.JobState.COMPLETED,
                    ru.passport.scanner.data.JobState.FAILED,
                    -> AsyncScanState.Processing
                }
                setAsyncStatus(initialStatus, recognizing = true)
                pollUntilTerminal(acceptedOperation, connectionProvider, generation, revisionAtRequest)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AsyncJobRepositoryException) {
                when (failure.category) {
                    AsyncFailureCategory.TRANSPORT,
                    AsyncFailureCategory.TIMEOUT,
                    AsyncFailureCategory.SERVER_UNAVAILABLE,
                    -> retryAmbiguousPost(failure, retryAttempt++)
                    AsyncFailureCategory.INVALID_RESPONSE -> {
                        if (failure.httpStatus == 202) retryAmbiguousPost(failure, retryAttempt++)
                        else {
                            showRecoveryRequired("Сервер вернул неподдерживаемый ответ задания.")
                            return
                        }
                    }
                    AsyncFailureCategory.UNAUTHORIZED -> {
                        notifyAccessDenied(operation.connectionId)
                        showConnectionRequired(failure.message ?: "Проверьте подключение и учётные данные.")
                        return
                    }
                    AsyncFailureCategory.ACCESS_DENIED -> {
                        showConnectionRequired(failure.message ?: "Проверьте подключение и учётные данные.")
                        return
                    }
                    AsyncFailureCategory.IDEMPOTENCY_CONFLICT,
                    AsyncFailureCategory.REQUEST_REJECTED,
                    AsyncFailureCategory.JOB_NOT_FOUND,
                    AsyncFailureCategory.JOB_NOT_TERMINAL,
                    AsyncFailureCategory.JOB_RESULT_GONE,
                    -> {
                        showRecoveryRequired(failure.message ?: "Не удалось безопасно продолжить отправку.")
                        return
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                retryAmbiguousPost(null, retryAttempt++)
            }
        }
    }

    private suspend fun retryAmbiguousPost(failure: AsyncJobRepositoryException?, attempt: Int) {
        if (failure != null) mutableState.value = mutableState.value.copy(error = failure.message)
        pollPause(attempt)
    }

    private suspend fun pollUntilTerminal(
        operation: PendingScanOperation.Accepted,
        connectionProvider: suspend () -> ApiConnection?,
        generation: Long,
        revisionAtRequest: Long,
    ) {
        var pollAttempt = 0
        while (foreground && isCurrent(generation, operation.operationId)) {
            val connection = connectionProvider()
            if (connection == null || connection.connectionId != operation.connectionId) {
                showConnectionRequired("Вернитесь к исходному подключению, чтобы продолжить проверку.")
                return
            }
            try {
                val status = repository.readStatus(
                    jobId = operation.jobId,
                    requestId = UUID.randomUUID().toString(),
                    connection = connection,
                )
                if (!isCurrent(generation, operation.operationId)) return
                if (!isCurrentConnection(operation.connectionId, connectionProvider)) {
                    showConnectionRequired("Вернитесь к исходному подключению, чтобы продолжить задание.")
                    return
                }
                when (status) {
                    is JobStatus.Queued -> {
                        setAsyncStatus(AsyncScanState.Queued, recognizing = true, clearError = true)
                        pollPause(pollAttempt++)
                    }
                    is JobStatus.Processing -> {
                        setAsyncStatus(AsyncScanState.Processing, recognizing = true, clearError = true)
                        pollPause(pollAttempt++)
                    }
                    is JobStatus.Completed -> {
                        receiveTerminal(operation, TerminalJobSnapshot.Completed(status.pipelineVersion, status.result), generation, revisionAtRequest)
                        return
                    }
                    is JobStatus.Failed -> {
                        receiveTerminal(operation, TerminalJobSnapshot.Failed(status.errorCode, status.message), generation, revisionAtRequest)
                        return
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AsyncJobRepositoryException) {
                when (failure.category) {
                    AsyncFailureCategory.TRANSPORT,
                    AsyncFailureCategory.TIMEOUT,
                    AsyncFailureCategory.SERVER_UNAVAILABLE,
                    -> {
                        mutableState.value = mutableState.value.copy(error = failure.message)
                        pollPause(pollAttempt++)
                    }
                    AsyncFailureCategory.UNAUTHORIZED -> {
                        notifyAccessDenied(operation.connectionId)
                        showConnectionRequired(failure.message ?: "Проверьте подключение и учётные данные.")
                        return
                    }
                    AsyncFailureCategory.ACCESS_DENIED -> {
                        showConnectionRequired(failure.message ?: "Проверьте подключение и учётные данные.")
                        return
                    }
                    AsyncFailureCategory.JOB_RESULT_GONE -> {
                        mutableState.value.image?.eraseInMemory()
                        mutableState.value = mutableState.value.copy(
                            image = null,
                            isRecognizing = false,
                            asyncScan = AsyncScanState.Failed("job_result_gone", failure.message ?: "Результат больше недоступен. Начните новое распознавание."),
                            error = failure.message,
                        )
                        return
                    }
                    AsyncFailureCategory.JOB_NOT_FOUND,
                    AsyncFailureCategory.JOB_NOT_TERMINAL,
                    AsyncFailureCategory.IDEMPOTENCY_CONFLICT,
                    AsyncFailureCategory.REQUEST_REJECTED,
                    AsyncFailureCategory.INVALID_RESPONSE,
                    -> {
                        showRecoveryRequired(failure.message ?: "Не удалось восстановить состояние задания.")
                        return
                    }
                }
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Не удалось обновить состояние задания. Повторяем попытку.")
                pollPause(pollAttempt++)
            }
        }
    }

    private suspend fun receiveTerminal(
        accepted: PendingScanOperation.Accepted,
        terminal: TerminalJobSnapshot,
        generation: Long,
        revisionAtRequest: Long,
    ) {
        if (!isCurrent(generation, accepted.operationId)) return
        val store = pendingScanStore ?: return
        val applied = try {
            store.markReceived(accepted.operationId, accepted.jobId, terminal)
        } catch (_: Exception) {
            false
        }
        if (!applied) {
            mutableState.value.image?.eraseInMemory()
            mutableState.value = mutableState.value.copy(
                image = null,
                isRecognizing = false,
                draft = (terminal as? TerminalJobSnapshot.Completed)?.result,
                asyncScan = AsyncScanState.RecoveryRequired,
                error = "Результат получен, но не удалось применить его к текущему экрану. Подтверждение не отправлено.",
            )
            return
        }
        val received = PendingScanOperation.Received(accepted.operationId, accepted.connectionId, accepted.jobId, terminal, false)
        activeOperation = received
        mutableState.value.image?.eraseInMemory()
        restoreReceived(received, preserveCurrentDraft = mutableState.value.editRevision != revisionAtRequest)
        if (mutableState.value.editRevision != revisionAtRequest) {
            mutableState.value = mutableState.value.copy(error = "Результат получен, текущие изменения сохранены.")
        }
        retryReceiptWhenReady(received, generation)
    }

    private suspend fun restoreReceived(operation: PendingScanOperation.Received, preserveCurrentDraft: Boolean = false) {
        val current = mutableState.value
        val terminal = operation.terminal
        val terminalMessage = (terminal as? TerminalJobSnapshot.Failed)?.message
        mutableState.value = current.copy(
            image = null,
            isRecognizing = false,
            draft = if (preserveCurrentDraft) current.draft else (terminal as? TerminalJobSnapshot.Completed)?.result,
            edits = if (preserveCurrentDraft) current.edits else emptyMap(),
            validationErrors = if (preserveCurrentDraft) current.validationErrors else emptyList(),
            asyncScan = when (terminal) {
                is TerminalJobSnapshot.Completed -> AsyncScanState.Completed
                is TerminalJobSnapshot.Failed -> AsyncScanState.Failed(terminal.errorCode, terminal.message)
            },
            error = current.error ?: terminalMessage,
        )
    }

    private fun shouldPreserveCurrentDraft(): Boolean = mutableState.value.draft != null || mutableState.value.editRevision > 0

    private suspend fun retryReceiptWhenReady(operation: PendingScanOperation.Received, generation: Long) {
        val store = pendingScanStore ?: return
        val provider = apiConnectionProvider ?: return
        var attempt = 0
        while (foreground && isCurrent(generation, operation.operationId)) {
            val savedConnectionId = try { savedConnectionIdProvider() } catch (_: Exception) { null }
            if (savedConnectionId != operation.connectionId) return
            val connection = try { provider() } catch (_: Exception) { null }
            if (connection == null || connection.connectionId != operation.connectionId) return
            try {
                repository.acknowledgeReceipt(operation.jobId, UUID.randomUUID().toString(), connection)
                if (!isCurrent(generation, operation.operationId)) return
                if (store.markReceiptAcknowledged(operation.operationId, operation.jobId)) {
                    activeOperation = operation.copy(receiptAcknowledged = true)
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AsyncJobRepositoryException) {
                when (failure.category) {
                    AsyncFailureCategory.UNAUTHORIZED -> {
                        notifyAccessDenied(operation.connectionId)
                        mutableState.value = mutableState.value.copy(error = failure.message)
                        return
                    }
                    AsyncFailureCategory.JOB_NOT_TERMINAL -> {
                        mutableState.value = mutableState.value.copy(error = failure.message)
                        var terminalConfirmed = false
                        while (foreground && isCurrent(generation, operation.operationId) && !terminalConfirmed) {
                            pollPause(attempt++)
                            try {
                                when (repository.readStatus(operation.jobId, UUID.randomUUID().toString(), connection)) {
                                    is JobStatus.Completed, is JobStatus.Failed -> terminalConfirmed = true
                                    is JobStatus.Queued, is JobStatus.Processing -> Unit
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (statusFailure: AsyncJobRepositoryException) {
                                when (statusFailure.category) {
                                    AsyncFailureCategory.UNAUTHORIZED -> {
                                        notifyAccessDenied(operation.connectionId)
                                        mutableState.value = mutableState.value.copy(error = statusFailure.message)
                                        return
                                    }
                                    AsyncFailureCategory.TRANSPORT,
                                    AsyncFailureCategory.TIMEOUT,
                                    AsyncFailureCategory.SERVER_UNAVAILABLE,
                                    -> mutableState.value = mutableState.value.copy(error = statusFailure.message)
                                    else -> {
                                        mutableState.value = mutableState.value.copy(error = statusFailure.message)
                                        return
                                    }
                                }
                            } catch (_: Exception) {
                                mutableState.value = mutableState.value.copy(error = "Результат открыт в приложении. Проверим подтверждение позже.")
                            }
                        }
                        if (terminalConfirmed) continue
                        return
                    }
                    AsyncFailureCategory.ACCESS_DENIED -> {
                        mutableState.value = mutableState.value.copy(error = failure.message)
                        return
                    }
                    AsyncFailureCategory.TRANSPORT,
                    AsyncFailureCategory.TIMEOUT,
                    AsyncFailureCategory.SERVER_UNAVAILABLE,
                    -> mutableState.value = mutableState.value.copy(error = failure.message)
                    else -> {
                        mutableState.value = mutableState.value.copy(error = failure.message)
                        return
                    }
                }
                pollPause(attempt++)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Результат открыт в приложении. Проверьте подключение к сети.")
                pollPause(attempt++)
            }
        }
    }

    private suspend fun clearReplacedOperationIfNeeded(store: PendingScanStore) {
        val operationId = operationToReplaceId ?: return
        if (!store.clear(operationId)) {
            if (store.load() != null) throw PendingScanStoreException()
        }
        operationToReplaceId = null
        activeOperation = null
    }

    private fun isCurrent(generation: Long, operationId: String): Boolean =
        generation == operationGeneration &&
            activeOperation?.operationId == operationId

    private suspend fun isCurrentConnection(
        expectedConnectionId: String,
        connectionProvider: suspend () -> ApiConnection?,
    ): Boolean = try {
        connectionProvider()?.connectionId == expectedConnectionId
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun notifyAccessDenied(connectionId: String) {
        try {
            onAccessDenied(connectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Credential state remains fail-closed even if the profile store is unavailable.
        }
    }

    private fun setAsyncStatus(status: AsyncScanState, recognizing: Boolean, clearError: Boolean = false) {
        val current = mutableState.value
        mutableState.value = current.copy(
            asyncScan = status,
            isRecognizing = recognizing,
            error = if (clearError) null else current.error,
        )
    }

    private fun showConnectionRequired(message: String) {
        mutableState.value = mutableState.value.copy(
            isRecognizing = false,
            asyncScan = AsyncScanState.ConnectionRequired,
            error = message,
        )
    }

    private fun showRecoveryRequired(message: String) {
        mutableState.value = mutableState.value.copy(
            isRecognizing = false,
            asyncScan = AsyncScanState.RecoveryRequired,
            error = message,
        )
    }

    private fun retainTerminalOperationForReplacement() {
        val phase = mutableState.value.asyncScan.phase
        if (phase == AsyncScanState.Phase.COMPLETED || phase == AsyncScanState.Phase.FAILED) {
            activeOperation?.let { operationToReplaceId = it.operationId }
            activeOperation = null
        }
    }


    fun editField(field: PassportField, value: String) {
        val current = mutableState.value
        if (current.isExporting) {
            operationGeneration++
            exportJob?.cancel()
        }
        val newEdits = current.edits + (field to value)
        val values = current.currentValues() + (field to value)
        current.shareRequest?.archive?.fill(0)
        mutableState.value = current.copy(
            edits = newEdits,
            isExporting = false,
            editRevision = current.editRevision + 1,
            validationErrors = PassportValidator.validate(values),
            shareRequest = null,
            error = null,
        )
    }

    fun prepareShare() {
        val current = mutableState.value
        if (current.draft == null || current.isRecognizing) {
            mutableState.value = current.copy(error = "Сначала получите черновик распознавания.")
            return
        }
        val values = current.currentValues()
        val validationErrors = PassportValidator.validate(values)
        if (validationErrors.isNotEmpty()) {
            mutableState.value = current.copy(
                validationErrors = validationErrors,
                error = "Исправьте отмеченные поля перед экспортом.",
            )
            return
        }
        val snapshot = PassportSnapshot(values.toMap(), current.editRevision).defensiveCopy()
        exportJob?.cancel()
        current.shareRequest?.archive?.fill(0)
        val generation = ++operationGeneration
        mutableState.value = current.copy(
            isExporting = true,
            validationErrors = emptyList(),
            shareRequest = null,
            error = null,
        )
        exportJob = viewModelScope.launch {
            try {
                val archive = createArchive(snapshot.defensiveCopy())
                val latest = mutableState.value
                if (generation == operationGeneration && latest.editRevision == snapshot.revision) {
                    mutableState.value = latest.copy(
                        isExporting = false,
                        shareRequest = ShareRequest(++nextShareRequestId, archive),
                    )
                } else {
                    archive.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val latest = mutableState.value
                if (generation == operationGeneration && latest.editRevision == snapshot.revision) {
                    mutableState.value = latest.copy(isExporting = false, error = "Не удалось подготовить архив. Повторите экспорт.")
                }
            } finally {
                val latest = mutableState.value
                if (generation == operationGeneration && latest.editRevision == snapshot.revision && latest.isExporting) {
                    mutableState.value = latest.copy(isExporting = false)
                }
            }
        }
    }

    /** Clear the one-shot event synchronously before the caller starts an email intent. */
    fun consumeShareRequest(id: Long): ByteArray? {
        val current = mutableState.value
        val request = current.shareRequest?.takeIf { it.id == id } ?: return null
        mutableState.value = current.copy(shareRequest = null)
        return request.archive
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun newScan() {
        if (mutableState.value.asyncScan.blocksNewScan) return
        retainTerminalOperationForReplacement()
        operationGeneration++
        recognitionJob?.cancel()
        asyncJob?.cancel()
        exportJob?.cancel()
        mutableState.value.image?.eraseInMemory()
        mutableState.value = ScanUiState()
        if (operationToReplaceId != null) {
            asyncJob = viewModelScope.launch {
                try {
                    clearReplacedOperationIfNeeded(pendingScanStore ?: return@launch)
                } catch (_: Exception) {
                    showRecoveryRequired("Не удалось удалить завершённое задание. Повторите попытку.")
                }
            }
        }
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        exportJob?.cancel()
        mutableState.value.shareRequest?.archive?.fill(0)
        mutableState.value.image?.eraseInMemory()
        repository.close()
        super.onCleared()
    }
}
