package ru.passport.scanner.ui

import android.Manifest
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.passport.scanner.image.ScanImageFiles
import ru.passport.scanner.data.AutomaticConnectionManager
import ru.passport.scanner.data.ConnectionUiState
import ru.passport.scanner.presentation.AsyncScanState
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.presentation.ScanViewModel
import java.util.concurrent.Executor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportScannerScreen(
    viewModel: ScanViewModel,
    isDemo: Boolean = false,
    isLegacySync: Boolean = true,
    connectionManager: AutomaticConnectionManager? = null,
    serverAddressSuggestion: String = "",
    httpsRequired: Boolean = false,
    onConnectServer: (String) -> Unit = {},
    onChangeServer: (String) -> Unit = {},
    onOpenEmail: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fallbackConnectionState = remember { androidx.compose.runtime.mutableStateOf<ConnectionUiState>(ConnectionUiState.NeedsServer) }
    val connectionState by connectionManager?.state?.collectAsStateWithLifecycle() ?: fallbackConnectionState
    var cameraVisible by rememberSaveable { mutableStateOf(false) }
    var connectionSheetVisible by rememberSaveable { mutableStateOf(false) }
    var serverChangeConfirmation by remember { mutableStateOf<String?>(null) }
    var discardConfirmation by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember(state.image?.id) { mutableStateOf<Bitmap?>(null) }
    var previewLoading by remember(state.image?.id) { mutableStateOf(state.image != null) }
    var selectionGeneration by remember { mutableLongStateOf(0L) }
    var pickerGeneration by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    val connectionReady = isDemo || connectionManager == null || connectionState is ConnectionUiState.Ready
    val currentServerUrl = when (connectionState) {
        is ConnectionUiState.Ready -> (connectionState as ConnectionUiState.Ready).serverUrl
        is ConnectionUiState.Connecting -> (connectionState as ConnectionUiState.Connecting).serverUrl
        is ConnectionUiState.Unavailable -> (connectionState as ConnectionUiState.Unavailable).serverUrl
        ConnectionUiState.NeedsServer, ConnectionUiState.StorageUnavailable -> null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val requestGeneration = pickerGeneration
            cameraVisible = false
            cameraError = null
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { ScanImageFiles.copyFromPicker(context, uri) } }
                val image = result.getOrNull()
                if (image != null && requestGeneration != selectionGeneration) return@launch
                if (image != null) {
                    viewModel.pickImage(image)
                } else if (requestGeneration == selectionGeneration) {
                    cameraError = result.exceptionOrNull()?.message ?: "Не удалось открыть изображение."
                }
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraVisible = true
            cameraError = null
        } else {
            cameraVisible = false
            cameraError = "Камера недоступна без разрешения. Выберите снимок из галереи."
        }
    }

    LaunchedEffect(state.image?.id) {
        val selectedImage = state.image
        previewLoading = selectedImage != null
        previewBitmap = selectedImage?.preview
        previewLoading = false
    }
    LaunchedEffect(state.shareRequest?.id) {
        val request = state.shareRequest ?: return@LaunchedEffect
        val archive = viewModel.consumeShareRequest(request.id) ?: return@LaunchedEffect
        onOpenEmail(archive)
    }

    fun openPicker() {
        selectionGeneration += 1
        pickerGeneration = selectionGeneration
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Сканер документов", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        if (!isDemo && connectionManager != null) TextButton(onClick = { connectionSheetVisible = true }) { Text("Сервер") }
                        if (state.image != null || state.draft != null || state.asyncScan.blocksNewScan) TextButton(
                            enabled = !state.asyncScan.blocksNewScan,
                            onClick = {
                            selectionGeneration += 1
                            viewModel.newScan()
                            cameraVisible = false
                        }) { Text("Новый снимок") }
                    },
                )
                if (isDemo) DemoModeBanner()
                else if (connectionManager != null) ConnectionSummary(connectionState) { connectionSheetVisible = true }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!isDemo) {
                AsyncJobStatusPanel(
                    state = state,
                    onRetry = viewModel::retry,
                    onOpenServer = { connectionSheetVisible = true },
                    onDiscard = { discardConfirmation = true },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.draft != null -> ReviewScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        isExporting = state.isExporting,
                        error = state.error,
                        onDismissError = viewModel::dismissError,
                    )
                    state.asyncScan.blocksNewScan && state.image == null -> WaitingForJobScreen(
                        modifier = Modifier.fillMaxSize(),
                        enabled = connectionReady,
                        onOpenServer = { connectionSheetVisible = true },
                    )
                    cameraVisible -> CameraCaptureScreen(
                        modifier = Modifier.fillMaxSize(),
                        error = cameraError,
                        onBack = { selectionGeneration += 1; cameraVisible = false },
                        onCaptured = { image ->
                            selectionGeneration += 1
                            viewModel.pickImage(image)
                            cameraVisible = false
                            cameraError = null
                        },
                        onError = { cameraError = it },
                    )
                    state.image == null -> StartScreen(
                        modifier = Modifier.fillMaxSize(),
                        error = cameraError ?: state.error,
                        enabled = !state.asyncScan.blocksNewScan,
                        onCamera = {
                            selectionGeneration += 1
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                        onGallery = ::openPicker,
                    )
                    else -> ImageReviewScreen(
                        modifier = Modifier.fillMaxSize(),
                        bitmap = previewBitmap,
                        previewLoading = previewLoading,
                        isRecognizing = state.isRecognizing,
                        isDemo = isDemo,
                        isLegacySync = isLegacySync,
                        canSubmit = connectionReady && !state.asyncScan.blocksNewScan,
                        canReplaceImage = !state.asyncScan.blocksNewScan,
                        asyncPhase = state.asyncScan.phase,
                        error = cameraError ?: state.error.takeIf { state.asyncScan.phase == AsyncScanState.Phase.IDLE },
                        onRecognize = if (state.asyncScan.phase == AsyncScanState.Phase.FAILED) viewModel::retry else viewModel::recognize,
                        onRetry = viewModel::retry,
                        onOpenServer = { connectionSheetVisible = true },
                        onGallery = ::openPicker,
                        onDismissError = viewModel::dismissError,
                    )
                }
            }
        }
    }

    ConnectionSheet(
        visible = connectionSheetVisible && !isDemo,
        state = connectionState,
        suggestedUrl = serverAddressSuggestion,
        httpsRequired = httpsRequired,
        busy = connectionState is ConnectionUiState.Connecting,
        onDismiss = { connectionSheetVisible = false },
        onSubmit = { url ->
            val serverChanged = currentServerUrl != url
            if (serverChanged && state.asyncScan.phase != AsyncScanState.Phase.IDLE) {
                serverChangeConfirmation = url
            } else {
                connectionSheetVisible = false
                if (serverChanged) {
                    onChangeServer(url)
                } else {
                    onConnectServer(url)
                }
            }
        },
    )

    serverChangeConfirmation?.let { url ->
        AlertDialog(
            onDismissRequest = { serverChangeConfirmation = null },
            title = { Text("Сменить сервер?") },
            text = {
                Text("Приложение перестанет отслеживать задание на этом устройстве. Задание на сервере не отменяется и может продолжить выполняться.")
            },
            confirmButton = {
                TextButton(onClick = {
                    serverChangeConfirmation = null
                    connectionSheetVisible = false
                    onChangeServer(url)
                }) { Text("Остановить отслеживание и сменить") }
            },
            dismissButton = { TextButton(onClick = { serverChangeConfirmation = null }) { Text("Оставить") } },
        )
    }

    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text("Остановить отслеживание?") },
            text = { Text("Приложение забудет это задание в текущем сеансе. Серверное задание не отменяется.") },
            confirmButton = {
                TextButton(onClick = {
                    discardConfirmation = false
                    viewModel.abandonPendingOperation()
                }) { Text("Удалить на устройстве") }
            },
            dismissButton = { TextButton(onClick = { discardConfirmation = false }) { Text("Оставить") } },
        )
    }
}

@Composable
private fun DemoModeBanner() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("demo_mode_banner"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Демо-режим · тестовые данные", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Фото не отправляется на сервер. Подставляются данные из примера.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StartScreen(modifier: Modifier, error: String?, enabled: Boolean = true, onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("DOC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(22.dp))
        Text("Сканирование документа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Снимите разворот или выберите готовое фото. Перед отправкой можно проверить и исправить каждое поле.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(26.dp))
        Button(onClick = onCamera, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Снять фото") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGallery, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Выбрать из галереи") }
        error?.let { ErrorCard(it) }
        Spacer(Modifier.height(16.dp))
        Text("Распознавание создаёт черновик, который можно отредактировать.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageReviewScreen(
    modifier: Modifier,
    bitmap: Bitmap?,
    previewLoading: Boolean,
    isRecognizing: Boolean,
    isDemo: Boolean,
    isLegacySync: Boolean,
    canSubmit: Boolean,
    canReplaceImage: Boolean,
    asyncPhase: AsyncScanState.Phase,
    error: String?,
    onRecognize: () -> Unit,
    onRetry: () -> Unit,
    onOpenServer: () -> Unit,
    onGallery: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Проверьте снимок", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("На фото должен быть виден весь разворот.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Выбранное изображение", modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)))
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                if (previewLoading) CircularProgressIndicator() else Text("Предпросмотр недоступен. Можно выбрать другое фото или продолжить распознавание.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { ErrorCard(it, onDismissError) }
        Button(
            onClick = if (!canSubmit) onOpenServer else onRecognize,
            enabled = !isRecognizing,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (isRecognizing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(when {
                !canSubmit -> "Подключить сервер"
                asyncPhase == AsyncScanState.Phase.FAILED -> "Новая попытка"
                isDemo -> "Получить тестовые данные"
                isLegacySync -> "Распознать"
                else -> "Отправить на распознавание"
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onGallery, enabled = canReplaceImage && !isRecognizing) { Text("Выбрать другое фото") }
            if (error != null) TextButton(onClick = onRetry, enabled = !isRecognizing) { Text("Повторить") }
        }
    }
}

@Composable
private fun AsyncJobStatusPanel(
    state: ru.passport.scanner.presentation.ScanUiState,
    onRetry: () -> Unit,
    onOpenServer: () -> Unit,
    onDiscard: () -> Unit,
) {
    val phase = state.asyncScan.phase
    if (phase == AsyncScanState.Phase.IDLE || phase == AsyncScanState.Phase.COMPLETED) return
    val title = when (phase) {
        AsyncScanState.Phase.SUBMITTING -> "Отправка снимка"
        AsyncScanState.Phase.QUEUED -> "В очереди"
        AsyncScanState.Phase.PROCESSING -> "Распознавание"
        AsyncScanState.Phase.FAILED -> "Не удалось распознать"
        AsyncScanState.Phase.CONNECTION_REQUIRED -> "Нужно восстановить подключение"
        AsyncScanState.Phase.RECOVERY_REQUIRED -> "Не удалось продолжить задание"
        AsyncScanState.Phase.IDLE, AsyncScanState.Phase.COMPLETED -> return
    }
    val active = state.asyncScan.isActive
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (active) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            val message = state.asyncScan.message ?: state.error
            if (!message.isNullOrBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
            when (phase) {
                AsyncScanState.Phase.FAILED -> if (state.image != null) TextButton(onClick = onRetry) { Text("Новая попытка") }
                AsyncScanState.Phase.CONNECTION_REQUIRED -> TextButton(onClick = onOpenServer) { Text("Проверить подключение") }
                AsyncScanState.Phase.RECOVERY_REQUIRED -> Row {
                    TextButton(onClick = onRetry) { Text("Продолжить") }
                    TextButton(onClick = onOpenServer) { Text("Сервер") }
                }
                AsyncScanState.Phase.SUBMITTING -> if (!active) TextButton(onClick = onRetry) { Text("Проверить отправку") }
                else -> Unit
            }
            if (state.asyncScan.blocksNewScan) {
                TextButton(onClick = onDiscard) { Text("Остановить отслеживание") }
            }
        }
    }
}

@Composable
private fun WaitingForJobScreen(modifier: Modifier, enabled: Boolean, onOpenServer: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ожидание результата", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("Новое задание можно отправить после завершения текущего.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enabled) {
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onOpenServer) { Text("Проверить подключение") }
        }
    }
}

@Composable
private fun ReviewScreen(modifier: Modifier, viewModel: ScanViewModel, isExporting: Boolean, error: String?, onDismissError: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Данные документа", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Распознанные значения можно отредактировать перед отправкой.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.draft?.warnings.orEmpty().isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text("В распознавании есть предупреждение.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("review_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PassportField.entries, key = { it.apiKey }) { field ->
                PassportFieldEditor(
                    field = field,
                    value = state.value(field),
                    error = state.validationErrors.firstOrNull { it.field == field }?.message,
                    onValueChange = { viewModel.editField(field, it) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        error?.let { ErrorCard(it, onDismissError, Modifier.padding(horizontal = 16.dp)) }
        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::prepareShare,
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("send_passport"),
                ) {
                    if (isExporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Создать архив и открыть почту")
                }
                Text("Отчество можно оставить пустым. Будет создан TXT в 7z с паролем и откроется почтовый клиент.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PassportFieldEditor(
    field: PassportField,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
) {
    val isDate = field == PassportField.BIRTH_DATE || field == PassportField.ISSUE_DATE
    val keyboard = when (field) {
        PassportField.SERIES, PassportField.NUMBER -> KeyboardType.Number
        else -> KeyboardType.Text
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (field == PassportField.GENDER) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("M" to "МУЖ.", "F" to "ЖЕН.").forEach { (code, label) ->
                        val selected = value == code
                        OutlinedButton(
                            onClick = { onValueChange(code) },
                            modifier = Modifier.weight(1f).testTag("gender_$code"),
                            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Text(label)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().testTag("field_${field.apiKey}"),
                singleLine = field != PassportField.BIRTH_PLACE && field != PassportField.ISSUER,
                minLines = if (field == PassportField.BIRTH_PLACE || field == PassportField.ISSUER) 2 else 1,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                placeholder = { Text(placeholder(field)) },
                supportingText = when {
                    error != null -> ({ Text(error) })
                    isDate -> ({ Text("Введите дату как YYYY-MM-DD, например 1990-04-12") })
                    field == PassportField.SERIES -> ({ Text("4 цифры; ведущие нули сохраняются") })
                    field == PassportField.NUMBER -> ({ Text("6 цифр; ведущие нули сохраняются") })
                    field == PassportField.PATRONYMIC -> ({ Text("Необязательное поле") })
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}

@Composable
private fun CameraCaptureScreen(modifier: Modifier, error: String?, onBack: () -> Unit, onCaptured: (ru.passport.scanner.model.ScanImage) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val coroutineScope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("Снимите весь разворот", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text("Разместите обе страницы в кадре.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CameraPreview(
            lifecycleOwner,
            mainExecutor,
            onCaptureReady = { capture = it },
            onError = onError,
            modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
        )
        error?.let { ErrorCard(it) }
        Button(
            onClick = {
                val imageCapture = capture ?: return@Button
                if (busy) return@Button
                busy = true
                coroutineScope.launch {
                    imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            busy = false
                            val rotation = image.imageInfo.rotationDegrees
                            val jpeg = try {
                                val buffer = image.planes.firstOrNull()?.buffer?.duplicate()
                                    ?: error("Снимок недоступен.")
                                ByteArray(buffer.remaining()).also(buffer::get)
                            } catch (_: Exception) {
                                onError("Не удалось подготовить снимок.")
                                return
                            } finally {
                                image.close()
                            }
                            coroutineScope.launch {
                                val result = withContext(Dispatchers.Default) { runCatching {
                                    ScanImageFiles.createImage(
                                        id = java.util.UUID.randomUUID().toString(),
                                        source = jpeg,
                                        sourceMime = "image/jpeg",
                                        rotationDegrees = rotation,
                                    )
                                } }
                                result.onSuccess(onCaptured)
                                    .onFailure { onError(it.message ?: "Не удалось подготовить снимок.") }
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            busy = false
                            onError("Не удалось сделать снимок. Попробуйте ещё раз.")
                        }
                    })
                }
            },
            enabled = capture != null && !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Сделать снимок")
        }
    }
}

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    executor: Executor,
    onCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(lifecycleOwner, previewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val listener = Runnable {
            runCatching {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                    .setTargetRotation(rotation)
                    .build()
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                onCaptureReady(capture)
            }.onFailure { onError("Не удалось запустить камеру. Выберите снимок из галереи или попробуйте ещё раз.") }
        }
        providerFuture.addListener(listener, executor)
        onDispose { provider?.unbindAll() }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { previewView }, modifier = modifier.aspectRatio(0.72f))
}

private fun placeholder(field: PassportField): String = when (field) {
    PassportField.SERIES -> "0000"
    PassportField.NUMBER -> "000000"
    PassportField.DEPARTMENT_CODE -> "000-000"
    PassportField.GENDER -> "M или F"
    PassportField.BIRTH_DATE, PassportField.ISSUE_DATE -> "YYYY-MM-DD"
    else -> "Введите значение"
}
