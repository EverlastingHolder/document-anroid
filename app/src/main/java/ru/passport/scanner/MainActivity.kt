package ru.passport.scanner

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.passport.scanner.data.AutomaticConnectionManager
import ru.passport.scanner.data.ClientInstallationRegistrar
import ru.passport.scanner.data.DemoPassportRepository
import ru.passport.scanner.data.KtorPassportRepository
import ru.passport.scanner.data.InMemoryPendingScanStore
import ru.passport.scanner.data.secure.EncryptedConnectionStore
import ru.passport.scanner.data.secure.LegacyDocumentCleanup
import ru.passport.scanner.export.PassportEmail
import ru.passport.scanner.export.PassportExporter
import ru.passport.scanner.export.MemoryExportStore
import ru.passport.scanner.image.ScanImageFiles
import ru.passport.scanner.presentation.ScanViewModel
import ru.passport.scanner.ui.PassportScannerScreen
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val clientInstallationRegistrar: ClientInstallationRegistrar? by lazy {
        if (BuildConfig.OCR_USE_STUB) null else synchronized(processConnectionLock) {
            processRegistrar ?: ClientInstallationRegistrar().also { processRegistrar = it }
        }
    }
    private val connectionStore: EncryptedConnectionStore? by lazy {
        if (BuildConfig.OCR_USE_STUB) null else synchronized(processConnectionLock) {
            processStore ?: EncryptedConnectionStore(
                applicationContext,
                allowDebugLocalHttp = BuildConfig.DEBUG,
            ).also { processStore = it }
        }
    }
    private val connectionManager: AutomaticConnectionManager? by lazy {
        if (BuildConfig.OCR_USE_STUB) null else synchronized(processConnectionLock) {
            processManager ?: AutomaticConnectionManager(
                connectionStore = requireNotNull(connectionStore),
                registrar = requireNotNull(clientInstallationRegistrar),
                allowDebugLocalHttp = BuildConfig.DEBUG,
            ).also { processManager = it }
        }
    }
    private val pendingScanStore: InMemoryPendingScanStore? by lazy {
        if (BuildConfig.OCR_USE_STUB) null else synchronized(processConnectionLock) {
            processPendingScanStore ?: InMemoryPendingScanStore().also { processPendingScanStore = it }
        }
    }
    private var connectionJob: Job? = null

    private val scanViewModel: ScanViewModel by lazy {
        val repository = if (BuildConfig.OCR_USE_STUB) DemoPassportRepository() else {
            KtorPassportRepository(baseUrl = BuildConfig.OCR_BASE_URL)
        }
        val exporter = PassportExporter()
        val manager = connectionManager
        ViewModelProvider(
            this,
            ScanViewModel.Factory(
                repository = repository,
                createArchive = exporter::createArchive,
                pendingScanStore = pendingScanStore,
                apiConnectionProvider = manager?.let { { it.currentConnection() } },
                savedConnectionIdProvider = { connectionStore?.load()?.connectionId },
                onAccessDenied = manager?.let { activeManager ->
                    { connectionId -> activeManager.markAccessDenied(connectionId) }
                } ?: {},
            ),
        )[ScanViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (startupCleanupStarted.compareAndSet(false, true)) {
            lifecycleScope.launch {
                runCatching {
                    LegacyDocumentCleanup.clearPendingJobRecord(applicationContext)
                    PassportExporter().cleanupLegacyExportStaging(cacheDir)
                    ScanImageFiles.cleanupLegacyScanFiles(cacheDir)
                }
                    .onFailure { startupCleanupStarted.set(false) }
            }
        }
        setContent {
            MaterialTheme {
                PassportScannerScreen(
                    viewModel = scanViewModel,
                    isDemo = BuildConfig.OCR_USE_STUB,
                    isLegacySync = BuildConfig.OCR_USE_STUB,
                    connectionManager = connectionManager,
                    serverAddressSuggestion = initialServerAddress,
                    httpsRequired = !BuildConfig.DEBUG,
                    onConnectServer = ::connectServer,
                    onChangeServer = ::changeServer,
                    onOpenEmail = ::openEmail,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        scanViewModel.setForeground(true)
        val manager = connectionManager
        if (manager == null) return
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            val savedServerAddress = try {
                connectionStore?.load()?.baseUrl
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            manager.connect(savedServerAddress ?: initialServerAddress)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scanViewModel.onConnectionReady()
        }
    }

    override fun onStop() {
        connectionJob?.cancel()
        scanViewModel.setForeground(false)
        super.onStop()
    }

    private fun connectServer(serverUrl: String) {
        val manager = connectionManager ?: return
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            var connection = manager.connect(serverUrl)
            if (connection == null && manager.state.value is ru.passport.scanner.data.ConnectionUiState.NeedsServer) {
                connection = try {
                    manager.changeServer(serverUrl) {
                        scanViewModel.abandonPendingOperationAndAwait()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (connection != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                scanViewModel.onConnectionReady()
            }
        }
    }

    private fun changeServer(serverUrl: String) {
        val manager = connectionManager ?: return
        connectionJob?.cancel()
        connectionJob = lifecycleScope.launch {
            val connection = try {
                manager.changeServer(serverUrl) {
                    scanViewModel.abandonPendingOperationAndAwait()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (connection != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                scanViewModel.onApiConnectionChanged()
                scanViewModel.onConnectionReady()
            } else if (connection == null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Не удалось завершить подключение. Проверьте адрес сервера и повторите попытку.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private val initialServerAddress: String
        get() = BuildConfig.OCR_BASE_URL.takeIf { BuildConfig.DEBUG || it.startsWith("https://", ignoreCase = true) }.orEmpty()

    private fun openEmail(archive: ByteArray) {
        var archiveUri: Uri? = null
        try {
            val publishedUri = MemoryExportStore.publish(this, archive)
            archiveUri = publishedUri
            startActivity(PassportEmail.createChooserIntent(this, publishedUri))
            android.widget.Toast.makeText(
                this,
                "Почтовый клиент открыт. Проверьте письмо и отправьте его вручную.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (_: PassportEmail.NoEmailHandlerException) {
            archiveUri?.let { MemoryExportStore.clear(this, it) }
            android.widget.Toast.makeText(
                this,
                "Не найдено совместимое почтовое приложение. Установите его и повторите экспорт.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (_: android.content.ActivityNotFoundException) {
            archiveUri?.let { MemoryExportStore.clear(this, it) }
            android.widget.Toast.makeText(
                this,
                "Не найдено приложение для отправки письма. Установите почтовый клиент и повторите попытку.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } finally {
            archive.fill(0)
        }
    }

    private companion object {
        val startupCleanupStarted = AtomicBoolean(false)
        val processConnectionLock = Any()
        var processRegistrar: ClientInstallationRegistrar? = null
        var processStore: EncryptedConnectionStore? = null
        var processManager: AutomaticConnectionManager? = null
        var processPendingScanStore: InMemoryPendingScanStore? = null
    }
}
