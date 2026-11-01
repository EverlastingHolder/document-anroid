package ru.passport.scanner.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.passport.scanner.model.PassportDraft
import java.time.Instant
import java.util.Locale

/** Exact immutable upload bytes retained only for ambiguous POST retries in the current process. */
class AsyncUploadPayload(bytes: ByteArray, mimeType: String) {
    private val content: ByteArray = bytes.copyOf()
    val mimeType: String = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val sizeBytes: Int get() = content.size

    init {
        require(content.size in 1..MAX_UPLOAD_BYTES) { "Upload image size is outside the supported range." }
        require(this.mimeType in SUPPORTED_MIME_TYPES) { "Unsupported upload media type." }
    }

    internal fun copyBytes(): ByteArray = content.copyOf()
    internal fun clearInMemory() = content.fill(0)

    override fun toString(): String = "AsyncUploadPayload(mimeType=$mimeType, sizeBytes=$sizeBytes, bytes=[redacted])"

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        const val MAX_UPLOAD_BYTES = 50 * 1024 * 1024
    }
}

data class AcceptedJob(
    val jobId: String,
    val status: JobState,
    /** Validated server path retained for display/diagnostics only; callers use the repository for reads. */
    val statusUrl: String,
    val createdAt: Instant,
) {
    override fun toString(): String = "AcceptedJob([redacted])"
}

enum class JobState { QUEUED, PROCESSING, COMPLETED, FAILED }

enum class AsyncFailureCategory {
    TRANSPORT,
    TIMEOUT,
    UNAUTHORIZED,
    ACCESS_DENIED,
    IDEMPOTENCY_CONFLICT,
    REQUEST_REJECTED,
    SERVER_UNAVAILABLE,
    JOB_NOT_FOUND,
    JOB_NOT_TERMINAL,
    JOB_RESULT_GONE,
    INVALID_RESPONSE,
}

sealed interface JobStatus {
    val jobId: String
    val createdAt: Instant
    val updatedAt: Instant
    val startedAt: Instant?
    val finishedAt: Instant?

    data class Queued(
        override val jobId: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val startedAt: Instant?,
        override val finishedAt: Instant?,
    ) : JobStatus {
        override fun toString(): String = "JobStatus.Queued([redacted])"
    }

    data class Processing(
        override val jobId: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val startedAt: Instant,
        override val finishedAt: Instant?,
    ) : JobStatus {
        override fun toString(): String = "JobStatus.Processing([redacted])"
    }

    data class Completed(
        override val jobId: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val startedAt: Instant,
        override val finishedAt: Instant,
        val pipelineVersion: String,
        val result: PassportDraft,
    ) : JobStatus {
        override fun toString(): String = "JobStatus.Completed([redacted])"
    }

    data class Failed(
        override val jobId: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val startedAt: Instant?,
        override val finishedAt: Instant,
        val errorCode: String,
        val message: String,
    ) : JobStatus {
        override fun toString(): String = "JobStatus.Failed([redacted])"
    }
}

@Serializable
internal data class AcceptedJobResponse(
    @SerialName("job_id") val jobId: String,
    val status: String,
    @SerialName("status_url") val statusUrl: String,
    @SerialName("created_at") val createdAt: String,
) {
    override fun toString(): String = "AcceptedJobResponse([redacted])"
}

@Serializable
internal data class JobStatusEnvelope(
    @SerialName("job_id") val jobId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("started_at") val startedAt: String?,
    @SerialName("finished_at") val finishedAt: String?,
    val result: CanonicalRecognitionResponse? = null,
    val error: JobErrorResponse? = null,
) {
    override fun toString(): String = "JobStatusEnvelope([redacted])"
}

@Serializable
internal data class JobErrorResponse(val code: String, val message: String) {
    override fun toString(): String = "JobErrorResponse([redacted])"
}

@Serializable
internal data class AsyncErrorResponse(
    @SerialName("request_id") val requestId: String,
    val code: String,
    val message: String,
) {
    override fun toString(): String = "AsyncErrorResponse([redacted])"
}
