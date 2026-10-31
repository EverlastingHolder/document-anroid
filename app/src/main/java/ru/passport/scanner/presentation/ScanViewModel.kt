package ru.passport.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PassportRepositoryException
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.FieldValidationError
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValidator
import ru.passport.scanner.model.ScanImage
import java.io.File
import java.util.UUID

data class ShareRequest(val id: Long, val archive: File)

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
) {
    fun currentValues(): Map<PassportField, String> = PassportField.entries.associateWith { field ->
        edits[field] ?: draft?.values?.get(field)?.value.orEmpty()
    }

    fun value(field: PassportField): String = edits[field] ?: draft?.values?.get(field)?.value.orEmpty()
}

class ScanViewModel(
    private val repository: PassportRepository,
    private val createArchive: suspend (PassportSnapshot) -> File,
) : ViewModel() {
    class Factory(
        private val repository: PassportRepository,
        private val createArchive: suspend (PassportSnapshot) -> File,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ScanViewModel::class.java))
            return ScanViewModel(repository, createArchive) as T
        }
    }

    private val mutableState = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = mutableState.asStateFlow()

    private var operationGeneration = 0L
    private var recognitionJob: Job? = null
    private var exportJob: Job? = null
    private var nextShareRequestId = 0L

    fun pickImage(image: ScanImage) {
        operationGeneration++
        recognitionJob?.cancel()
        exportJob?.cancel()
        mutableState.value = ScanUiState(image = image)
    }

    fun recognize() {
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

    fun retry() = recognize()

    fun editField(field: PassportField, value: String) {
        val current = mutableState.value
        if (current.isExporting) {
            operationGeneration++
            exportJob?.cancel()
        }
        val newEdits = current.edits + (field to value)
        val values = current.currentValues() + (field to value)
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
    fun consumeShareRequest(id: Long): File? {
        val current = mutableState.value
        val request = current.shareRequest?.takeIf { it.id == id } ?: return null
        mutableState.value = current.copy(shareRequest = null)
        return request.archive
    }

    fun dismissError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    fun newScan() {
        operationGeneration++
        recognitionJob?.cancel()
        exportJob?.cancel()
        mutableState.value = ScanUiState()
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        exportJob?.cancel()
        repository.close()
        super.onCleared()
    }
}
