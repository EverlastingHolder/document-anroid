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
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local image handling. The original pixels are retained for recognition; decoding is sampled for preview. */
object ScanImageFiles {
    private const val MAX_LOCAL_BYTES = 50L * 1024L * 1024L
    private const val MAX_LOCAL_PIXELS = 100_000_000L
    private const val MAX_ROTATION_PIXELS = 24_000_000L
    private const val MAX_NORMALIZATION_HEAP_FRACTION = 0.55
    private const val PREVIEW_LONG_EDGE = 1_600
    private const val SCAN_RETENTION_MILLIS = 48L * 60 * 60 * 1000

    fun copyFromPicker(context: Context, uri: Uri): ScanImage {
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val destination = File(File(context.cacheDir, "scans").apply { mkdirs() }, "$id.upload")
        try {
            val mime = resolvedMime(resolver, uri)
            require(mime in SUPPORTED_MIME_TYPES) { "Поддерживаются изображения JPEG, PNG и WebP." }
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_LOCAL_BYTES) { "Файл слишком большой для обработки на устройстве." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Не удалось прочитать выбранное изображение.")
            validateImage(destination)
            val uploadMime = normalizeOrientation(destination, mime)
            require(destination.length() <= MAX_LOCAL_BYTES) { "Нормализованное изображение слишком большое. Выберите файл меньшего размера." }
            return ScanImage(id, destination, uploadMime)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun newCameraFile(context: Context): File {
        val folder = File(context.cacheDir, "scans").apply { mkdirs() }
        return File(folder, "${UUID.randomUUID()}.jpg")
    }

    fun cameraImage(file: File): ScanImage {
        validateImage(file)
        val mime = normalizeOrientation(file, ContentResolverMime.JPEG)
        require(file.length() <= MAX_LOCAL_BYTES) { "Нормализованный снимок слишком большой. Сделайте снимок ещё раз." }
        return ScanImage(file.nameWithoutExtension, file, mime)
    }

    /** Remove abandoned private scan copies at startup; the active image is not touched during a session. */
    suspend fun cleanupExpiredScans(cacheDir: File) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - SCAN_RETENTION_MILLIS
        File(cacheDir, "scans").listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    fun decodePreview(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_LOCAL_PIXELS) return null
        val sample = sampleSize(width, height)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: return null
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation)
        if (matrix.isIdentity) return decoded
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }.getOrElse {
            decoded.recycle()
            null
        }
    }

    private fun validateImage(file: File) {
        require(file.isFile && file.length() in 1..MAX_LOCAL_BYTES) { "Изображение пустое или слишком большое." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение. Выберите JPEG, PNG или WebP." }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_LOCAL_PIXELS) { "Размер изображения превышает локальный предел 100 мегапикселей." }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 },
        )
        require(decoded != null) { "Файл не удалось декодировать как изображение." }
        decoded.recycle()
    }

    /** Apply EXIF orientation to pixels before upload. Normal images keep their original bytes and quality. */
    private fun normalizeOrientation(file: File, mime: String): String {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(orientation)
        if (matrix.isIdentity) return mime

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && pixels <= MAX_ROTATION_PIXELS) {
            "Снимок слишком большой для безопасной коррекции ориентации. Выберите изображение размером до 24 мегапикселей."
        }
        val isJpeg = mime == ContentResolverMime.JPEG
        val bytesPerPixel = if (isJpeg) 2L else 4L
        val bufferCount = if (isJpeg) 2L else 3L
        val estimatedBytes = pixels * bytesPerPixel * bufferCount + 12L * 1024 * 1024
        val safeHeapBudget = Runtime.getRuntime().maxMemory() * MAX_NORMALIZATION_HEAP_FRACTION
        require(estimatedBytes <= safeHeapBudget) {
            "Недостаточно памяти для безопасной коррекции ориентации этого снимка. Выберите изображение меньшего размера."
        }
        val decoded = try {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inPreferredConfig = if (isJpeg) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                },
            ) ?: error("Не удалось нормализовать ориентацию снимка. Выберите изображение ещё раз.")
        } catch (_: OutOfMemoryError) {
            error("Недостаточно памяти для коррекции ориентации. Выберите изображение меньшего размера.")
        }
        val oriented = try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            error("Недостаточно памяти для коррекции ориентации. Выберите изображение меньшего размера.")
        } catch (failure: Throwable) {
            decoded.recycle()
            throw failure
        }
        if (oriented !== decoded) decoded.recycle()

        val flattened = if (isJpeg) null else try {
            Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            oriented.recycle()
            error("Недостаточно памяти для коррекции ориентации. Выберите изображение меньшего размера.")
        }
        try {
            val bitmapToEncode = flattened ?: oriented
            if (flattened != null) {
                Canvas(flattened).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(oriented, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            val temporary = File(file.parentFile, "${file.name}.normalized")
            try {
                FileOutputStream(temporary).use { output ->
                    check(bitmapToEncode.compress(Bitmap.CompressFormat.JPEG, 96, output)) { "Не удалось подготовить снимок для отправки." }
                }
                check(temporary.length() in 1..MAX_LOCAL_BYTES) { "Нормализованное изображение слишком большое. Выберите файл меньшего размера." }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temporary.delete()
            }
        } finally {
            flattened?.recycle()
            oriented.recycle()
        }
        return ContentResolverMime.JPEG
    }

    private fun resolvedMime(resolver: ContentResolver, uri: Uri): String =
        resolver.getType(uri)?.lowercase()?.substringBefore(';') ?: when {
            uri.toString().endsWith(".png", ignoreCase = true) -> "image/png"
            uri.toString().endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > PREVIEW_LONG_EDGE) sample *= 2
        return sample
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

    private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

    private object ContentResolverMime { const val JPEG = "image/jpeg" }
}
