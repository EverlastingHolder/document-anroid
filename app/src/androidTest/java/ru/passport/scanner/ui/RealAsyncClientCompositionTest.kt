package ru.passport.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.ApiConnection
import ru.passport.scanner.data.AutomaticConnectionManager
import ru.passport.scanner.data.ClientInstallationRegistrar
import ru.passport.scanner.data.ConnectionStore
import ru.passport.scanner.data.KtorPassportRepository
import ru.passport.scanner.data.PendingScanOperation
import ru.passport.scanner.data.PendingScanStore
import ru.passport.scanner.data.TerminalJobSnapshot
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.ScanImage
import ru.passport.scanner.presentation.ScanViewModel

/** Runs the real client manager, registrar, Ktor repository, and VM against a synthetic local API. */
@RunWith(AndroidJUnit4::class)
class RealAsyncClientCompositionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun realManagerRegistrationAndKtorJobFlowReachesEditableDraft() = runBlocking {
        val fixture = LoopbackApi()
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread({
            try {
                fixture.serve()
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }, "real-async-client-api-fixture").apply { start() }

        val serverUrl = "http://127.0.0.1:${fixture.port}"
        val store = MemoryConnectionStore()
        val registrar = ClientInstallationRegistrar()
        val manager = AutomaticConnectionManager(store, registrar, allowDebugLocalHttp = true)
        val connection = manager.connect(serverUrl)
        assertNotNull("Registration must complete before the API connection is available. state=${manager.state.value}, routes=${fixture.requests.map { it.method to it.path }}", connection)
        assertEquals(serverUrl, manager.currentConnection()?.baseUrl)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "real-async-client-synthetic.jpg").apply {
            writeBytes(byteArrayOf(11, 22, 33, 44, 55))
        }
        val archive = byteArrayOf(9, 8, 7)
        val openedArchive = AtomicReference<ByteArray?>()
        val repository = KtorPassportRepository(serverUrl)
        val viewModel = ScanViewModel(
            repository = repository,
            createArchive = { snapshot: PassportSnapshot ->
                assertEquals("TEST-COMPOSED", snapshot.values.getValue(PassportField.SURNAME))
                archive
            },
            pendingScanStore = MemoryPendingScanStore(),
            apiConnectionProvider = { manager.currentConnection() },
            pollPause = {},
        )
        viewModel.pickImage(ScanImage("real-async-client-synthetic", image, "image/jpeg"))

        try {
            compose.setContent {
                MaterialTheme {
                    PassportScannerScreen(
                        viewModel = viewModel,
                        isLegacySync = false,
                        connectionManager = manager,
                        serverAddressSuggestion = serverUrl,
                        httpsRequired = false,
                        onOpenEmail = openedArchive::set,
                    )
                }
            }
            compose.onNodeWithText(serverUrl).assertIsDisplayed()
            compose.onNodeWithText("Отправить на распознавание").performClick()
            compose.waitUntil(10_000) { viewModel.state.value.draft != null }
            compose.onNodeWithText("Данные документа").assertIsDisplayed()
            compose.onNodeWithTag("field_surname").performTextReplacement("TEST-COMPOSED")
            compose.onNodeWithText("Создать архив и открыть почту").performClick()
            compose.waitUntil(5_000) { openedArchive.get() != null }

            serverThread.join(5_000)
            assertTrue("Fixture API did not finish its bounded request sequence.", !serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Fixture API failed.", it) }
            val requests = fixture.requests.toList()
            assertEquals(6, requests.size)
            assertEquals("POST", requests[0].method)
            assertEquals("/v1/client-installations", requests[0].path)
            assertTrue("Registration sends only a token digest.", requests[0].bodyText.contains("\"token_sha256\""))
            assertTrue("Registration must not include the bearer credential.", !requests[0].bodyText.contains("pbt1_"))
            assertEquals("POST", requests[1].method)
            assertEquals("/v1/passport/jobs", requests[1].path)
            assertTrue(requests[1].headers["authorization"]?.startsWith("Bearer pbt1_") == true)
            assertTrue(String(requests[1].body, StandardCharsets.ISO_8859_1).contains("name=\"file\"; filename=\"scan\""))
            requests.drop(2).take(3).forEach { request ->
                assertEquals("GET", request.method)
                assertEquals("/v1/passport/jobs/$JOB_ID", request.path)
                assertTrue(request.headers["authorization"]?.startsWith("Bearer pbt1_") == true)
            }
            val receipt = requests.last()
            assertEquals("POST", receipt.method)
            assertEquals("/v1/passport/jobs/$JOB_ID/receipt", receipt.path)
            assertTrue(receipt.headers["authorization"]?.startsWith("Bearer pbt1_") == true)
            assertTrue(receipt.headers["x-request-id"].orEmpty().isNotBlank())
            assertEquals(null, receipt.headers["idempotency-key"])
            assertEquals(0, receipt.body.size)
            assertTrue(archive.contentEquals(openedArchive.get()))
        } finally {
            viewModel.setForeground(false)
            repository.close()
            registrar.close()
            fixture.close()
            serverThread.join(1_000)
            image.delete()
            archive.fill(0)
        }
    }

    private class MemoryConnectionStore : ConnectionStore {
        private var saved: ApiConnection? = null
        override suspend fun load(): ApiConnection? = saved
        override suspend fun save(connection: ApiConnection) { saved = connection }
        override suspend fun clear() { saved = null }
    }

    private class MemoryPendingScanStore : PendingScanStore {
        private var record: PendingScanOperation? = null
        override suspend fun load(): PendingScanOperation? = record
        override suspend fun beginSubmitting(operation: PendingScanOperation.Submitting) { record = operation }
        override suspend fun markAccepted(operationId: String, jobId: String): Boolean {
            val current = record ?: return false
            if (current.operationId != operationId) return false
            record = PendingScanOperation.Accepted(operationId, current.connectionId, jobId)
            return true
        }
        override suspend fun markReceived(operationId: String, jobId: String, terminal: TerminalJobSnapshot): Boolean {
            val current = record as? PendingScanOperation.Accepted ?: return false
            if (current.operationId != operationId || current.jobId != jobId) return false
            record = PendingScanOperation.Received(operationId, current.connectionId, jobId, terminal, false)
            return true
        }
        override suspend fun markReceiptAcknowledged(operationId: String, jobId: String): Boolean {
            val current = record as? PendingScanOperation.Received ?: return false
            if (current.operationId != operationId || current.jobId != jobId) return false
            record = current.copy(receiptAcknowledged = true)
            return true
        }
        override suspend fun clear(operationId: String): Boolean {
            if (record?.operationId != operationId) return false
            record = null
            return true
        }
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
        val bodyText: String get() = String(body, StandardCharsets.UTF_8)
    }

    private class LoopbackApi : AutoCloseable {
        private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<Request>()
        private var statusReads = 0

        fun serve() {
            repeat(6) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val request = readRequest(socket)
                    requests += request
                    val response = when {
                        request.method == "POST" && request.path == "/v1/client-installations" ->
                            Response(201, """{"client_id":"123e4567-e89b-42d3-a456-426614174099","created_at":"$CREATED"}""")
                        request.method == "POST" && request.path == "/v1/passport/jobs" -> Response(202, acceptedBody())
                        request.method == "GET" && request.path == "/v1/passport/jobs/$JOB_ID" -> {
                            statusReads++
                            Response(200, statusBody(statusReads))
                        }
                        request.method == "POST" && request.path == "/v1/passport/jobs/$JOB_ID/receipt" -> Response(204, "")
                        else -> Response(404, "{}")
                    }
                    val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
                    val status = when (response.status) {
                        201 -> "Created"
                        202 -> "Accepted"
                        204 -> "No Content"
                        else -> "OK"
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${response.status} $status\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        private fun readRequest(socket: Socket): Request {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input).split(' ')
            check(requestLine.size == 3) { "Malformed fixture request line." }
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                check(separator > 0) { "Malformed fixture request header." }
                headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                check(count > 0) { "Truncated fixture request body." }
                offset += count
            }
            return Request(requestLine[0], requestLine[1], headers, body)
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                check(value >= 0) { "Truncated fixture HTTP headers." }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString(StandardCharsets.US_ASCII.name())
        }

        override fun close() { runCatching { server.close() } }

        private data class Response(val status: Int, val body: String)

        private fun acceptedBody() = """{"job_id":"$JOB_ID","status":"queued","status_url":"/v1/passport/jobs/$JOB_ID","created_at":"$CREATED"}"""

        private fun statusBody(read: Int): String = when (read) {
            1 -> """{"job_id":"$JOB_ID","status":"queued","created_at":"$CREATED","updated_at":"$CREATED","started_at":null,"finished_at":null}"""
            2 -> """{"job_id":"$JOB_ID","status":"processing","created_at":"$CREATED","updated_at":"$STARTED","started_at":"$STARTED","finished_at":null}"""
            else -> """{"job_id":"$JOB_ID","status":"completed","created_at":"$CREATED","updated_at":"$FINISHED","started_at":"$STARTED","finished_at":"$FINISHED","result":$CANONICAL_RESULT}"""
        }
    }

    private companion object {
        const val JOB_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val CREATED = "2026-10-31T10:00:00Z"
        const val STARTED = "2026-10-31T10:01:00Z"
        const val FINISHED = "2026-10-31T10:02:00Z"
        val CANONICAL_RESULT = """
            {
              "request_id":"$JOB_ID","status":"completed","review_required":true,"pipeline_version":"synthetic-test",
              "fields":{
                "surname":{"value":"TEST","state":"recognized","issues":[]},
                "given_name":{"value":"SAMPLE","state":"recognized","issues":[]},
                "patronymic":{"value":null,"state":"missing","issues":[]},
                "gender":{"value":"M","state":"uncertain","issues":[{"code":"manual_review_required","message":"Synthetic review"}]},
                "birth_date":{"value":"1990-04-12","state":"recognized","issues":[]},
                "birth_place":{"value":"TEST CITY","state":"recognized","issues":[]},
                "series":{"value":"0012","state":"recognized","issues":[]},
                "number":{"value":"000345","state":"recognized","issues":[]},
                "issue_date":{"value":"2010-06-18","state":"recognized","issues":[]},
                "issuer":{"value":"TEST OFFICE","state":"recognized","issues":[]},
                "department_code":{"value":"000-001","state":"recognized","issues":[]}
              },"warnings":[]
            }
        """.trimIndent()
    }
}
