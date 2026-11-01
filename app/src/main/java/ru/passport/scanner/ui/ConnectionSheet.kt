package ru.passport.scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.passport.scanner.data.ConnectionUiState

@Composable
internal fun ConnectionSummary(state: ConnectionUiState, onOpenSettings: () -> Unit) {
    val (title, detail, busy) = when (state) {
        ConnectionUiState.NeedsServer -> Triple("Нужно подключить сервер", "Укажите адрес сервера, чтобы отправить снимок.", false)
        is ConnectionUiState.Connecting -> Triple("Подключение к серверу…", state.serverUrl, true)
        is ConnectionUiState.Ready -> Triple("Сервер подключён", state.serverUrl, false)
        is ConnectionUiState.Unavailable -> Triple(
            "Сервер недоступен",
            when (state.reason) {
                ru.passport.scanner.data.ConnectionUnavailableReason.SERVER_UNAVAILABLE -> "Проверьте адрес и подключение к сети."
                ru.passport.scanner.data.ConnectionUnavailableReason.ACCESS_DENIED -> "Не удалось подтвердить подключение. Проверьте адрес сервера."
                ru.passport.scanner.data.ConnectionUnavailableReason.INVALID_SERVER -> "Проверьте адрес сервера."
            },
            false,
        )
        ConnectionUiState.StorageUnavailable -> Triple("Подключение недоступно", "Не удалось открыть защищённое хранилище на этом устройстве.", false)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
        else TextButton(onClick = onOpenSettings) { Text("Сервер") }
    }
}

@Composable
internal fun ConnectionSheet(
    visible: Boolean,
    state: ConnectionUiState,
    suggestedUrl: String,
    httpsRequired: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    if (!visible) return
    val initialUrl = when (state) {
        is ConnectionUiState.Ready -> state.serverUrl
        is ConnectionUiState.Connecting -> state.serverUrl
        is ConnectionUiState.Unavailable -> state.serverUrl
        ConnectionUiState.NeedsServer, ConnectionUiState.StorageUnavailable -> suggestedUrl
    }
    var url by remember(state, suggestedUrl) { mutableStateOf(initialUrl) }
    val message = when (state) {
        ConnectionUiState.NeedsServer -> "Укажите адрес сервера, чтобы отправить снимок."
        is ConnectionUiState.Connecting -> "Проверяем подключение к серверу."
        is ConnectionUiState.Ready -> "При смене адреса потребуется подтвердить удаление незавершённой локальной задачи. Задача на сервере продолжит выполняться."
        is ConnectionUiState.Unavailable -> "Не удалось завершить подключение. Проверьте адрес сервера и повторите попытку."
        ConnectionUiState.StorageUnavailable -> "Не удалось восстановить подключение. Повторите попытку позже."
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Подключение к серверу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("Адрес сервера") },
                    placeholder = { Text("https://server.example") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (httpsRequired) {
                    Text("В этой сборке разрешены только HTTPS-адреса.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = { onSubmit(url.trim()) }, enabled = !busy && url.isNotBlank()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Подключить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") } },
    )
}
