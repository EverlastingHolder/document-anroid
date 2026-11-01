package ru.passport.scanner.image

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import ru.passport.scanner.model.ScanImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Image bytes and sampled previews stay in RAM; the camera output file is removed as soon as it is read. */
object ScanImageFiles {
    private const val MAX_BYTES = 50L * 1024 * 1024
    private const val MAX_PIXELS = 100_000_000L
    private const val MAX_ROTATION_PIXELS = 24_000_000L
    private const val MAX_NORMALIZATION_HEAP_FRACTION = 0.55
    private const val PREVIEW_LONG_EDGE = 1_600

    fun copyFromPicker(context: Context, uri: Uri): ScanImage {
        val resolver = context.contentResolver
        val mime = resolvedMime(resolver, uri)
        require(mime in SUPPORTED_MIME_TYPES) { "Поддерживаются изображения JPEG, PNG и WebP." }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ZeroingByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= MAX_BYTES) { "Файл слишком большой для обработки на устройстве." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
                output.erase()
            }
        } ?: error("Не удалось прочитать выбранное изображение.")
        try {
            return createImage(UUID.randomUUID().toString(), bytes, mime)
        } finally {
            bytes.fill(0)
        }
    }

    /** Remove only known legacy and in-progress scan staging directories; never walk app exports or settings. */
    fun cleanupLegacyScanFiles(cacheDir: java.io.File) {
        listOf("scans", "camera-capture").forEach { childName ->
            val child = java.io.File(cacheDir, childName)
            if (child.parentFile?.canonicalFile == cacheDir.canonicalFile && child.isDirectory && !child.isSymbolicLink()) {
                child.deleteRecursively()
            }
        }
    }

    fun createImage(id: String, source: ByteArray, sourceMime: String, rotationDegrees: Int = 0): ScanImage {
        require(sourceMime in SUPPORTED_MIME_TYPES) { "Поддерживаются изображения JPEG, PNG и WebP." }
        require(source.size in 1..MAX_BYTES) { "Изображение пустое или слишком большое." }
        val bytes = source.copyOf()
        var normalizedBytes: ByteArray? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение. Выберите JPEG, PNG или WebP." }
            require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "Размер изображения превышает локальный предел 100 мегапикселей." }
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = orientationMatrix(orientation).let { exifMatrix ->
                if (orientation != ExifInterface.ORIENTATION_NORMAL || rotationDegrees % 360 == 0) exifMatrix
                else Matrix().apply { setRotate(rotationDegrees.toFloat()) }
            }
            val uploadBytes: ByteArray
            val mime: String
            if (matrix.isIdentity) {
                uploadBytes = bytes
                mime = sourceMime
            } else {
                val pixels = bounds.outWidth.toLong() * bounds.outHeight
                require(pixels <= MAX_ROTATION_PIXELS) {
                    "Снимок слишком большой для безопасной коррекции ориентации. Выберите изображение размером до 24 мегапикселей."
                }
                val jpeg = sourceMime == ContentResolverMime.JPEG
                val bytesPerPixel = if (jpeg) 2L else 4L
                val bufferCount = if (jpeg) 2L else 3L
                val estimated = pixels * bytesPerPixel * bufferCount + 12L * 1024 * 1024
                require(estimated <= Runtime.getRuntime().maxMemory() * MAX_NORMALIZATION_HEAP_FRACTION) {
                    "Недостаточно памяти для безопасной коррекции ориентации этого снимка. Выберите изображение меньшего размера."
                }
                val decoded = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = if (jpeg) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888 },
                ) ?: error("Не удалось нормализовать ориентацию снимка. Выберите изображение ещё раз.")
                val oriented = try {
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                } finally {
                    decoded.recycle()
                }
                val flattened = if (jpeg) null else Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                try {
                    if (flattened != null) Canvas(flattened).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(oriented, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    val output = ZeroingByteArrayOutputStream()
                    try {
                        check((flattened ?: oriented).compress(Bitmap.CompressFormat.JPEG, 96, output)) { "Не удалось подготовить снимок для отправки." }
                        uploadBytes = output.toByteArray()
                    } finally {
                        output.erase()
                    }
                    require(uploadBytes.size in 1..MAX_BYTES) { "Нормализованное изображение слишком большое." }
                } finally {
                    flattened?.recycle()
                    oriented.recycle()
                }
                mime = ContentResolverMime.JPEG
            }
            if (uploadBytes !== bytes) normalizedBytes = uploadBytes
            val preview = decodePreview(uploadBytes)
                ?: error("Не удалось подготовить предварительный просмотр снимка.")
            return ScanImage(id, uploadBytes, mime, preview)
        } finally {
            normalizedBytes?.fill(0)
            bytes.fill(0)
            source.fill(0)
        }
    }

    fun decodePreview(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        var sample = 1
        while (maxOf(width, height) / sample > PREVIEW_LONG_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 },
        )
    }

    private fun resolvedMime(resolver: ContentResolver, uri: Uri): String =
        resolver.getType(uri)?.lowercase()?.substringBefore(';') ?: when {
            uri.toString().endsWith(".png", ignoreCase = true) -> "image/png"
            uri.toString().endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> ContentResolverMime.JPEG
        }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private class ZeroingByteArrayOutputStream : ByteArrayOutputStream() {
        fun erase() {
            buf.fill(0)
            reset()
        }
    }

    private fun java.io.File.isSymbolicLink(): Boolean = runCatching { java.nio.file.Files.isSymbolicLink(toPath()) }.getOrDefault(true)
    private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
}

private object ContentResolverMime { const val JPEG = "image/jpeg" }
