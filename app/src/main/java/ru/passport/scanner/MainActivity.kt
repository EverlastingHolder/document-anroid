package ru.passport.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import ru.passport.scanner.data.DemoPassportRepository
import ru.passport.scanner.data.KtorPassportRepository
import ru.passport.scanner.export.PassportEmail
import ru.passport.scanner.export.PassportExporter
import ru.passport.scanner.image.ScanImageFiles
import ru.passport.scanner.presentation.ScanViewModel
import ru.passport.scanner.ui.PassportScannerScreen
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val scanViewModel: ScanViewModel by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ScanViewModel::class.java))
                val repository = if (BuildConfig.OCR_USE_STUB) {
                    DemoPassportRepository()
                } else {
                    KtorPassportRepository(
                        baseUrl = BuildConfig.OCR_BASE_URL,
                    )
                }
                val exporter = PassportExporter(cacheDir)
                return ScanViewModel(repository, exporter::createArchive) as T
            }
        })[ScanViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (startupCleanupStarted.compareAndSet(false, true)) {
            lifecycleScope.launch {
                runCatching {
                    PassportExporter(cacheDir).cleanupExpiredExports()
                    ScanImageFiles.cleanupExpiredScans(cacheDir)
                }
                    .onFailure { startupCleanupStarted.set(false) }
            }
        }
        setContent {
            MaterialTheme {
                PassportScannerScreen(
                    viewModel = scanViewModel,
                    isDemo = BuildConfig.OCR_USE_STUB,
                    onOpenEmail = ::openEmail,
                )
            }
        }
    }

    private fun openEmail(archive: java.io.File) {
        try {
            startActivity(PassportEmail.createChooserIntent(this, archive))
            android.widget.Toast.makeText(
                this,
                "Почтовый клиент открыт. Проверьте письмо и отправьте его вручную.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (_: PassportEmail.NoEmailHandlerException) {
            android.widget.Toast.makeText(
                this,
                "Не найдено почтовое приложение. Архив сохранён; установите клиент и повторите попытку.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                this,
                "Не найдено приложение для отправки письма. Установите почтовый клиент и повторите попытку.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private companion object {
        val startupCleanupStarted = AtomicBoolean(false)
    }
}
