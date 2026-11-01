package ru.passport.scanner.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Exercises the production OkHttp transport against a synthetic loopback HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class AsyncJobHttpTransportInstrumentedTest {
    @Test
    fun postsMultipartAndReadsAllFourLifecycleStatesOverAndroidOkHttp() = runBlocking {
        val fixture = LoopbackFixture(listOf(
            Response(202, acceptedBody()),
            Response(200, statusBody("queued")),
            Response(200, statusBody("processing")),
            Response(200, statusBody("completed")),
            Response(200, statusBody("failed")),
        ))
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread({
            try {
                fixture.serve()
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }, "async-job-http-fixture").apply { start() }
        val repo = KtorPassportRepository("http://127.0.0.1:${fixture.port}")
        val token = "pbt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val connection = ApiConnection("http://127.0.0.1:${fixture.port}", token, "instrumented-test", allowDebugLocalHttp = true)
        val jobId = JOB_ID
        val requestIds = listOf(
            "123e4567-e89b-42d3-a456-426614174001",
            "123e4567-e89b-42d3-a456-426614174002",
            "123e4567-e89b-42d3-a456-426614174003",
            "123e4567-e89b-42d3-a456-426614174004",
            "123e4567-e89b-42d3-a456-426614174005",
        )
        val imageBytes = byteArrayOf(0x10, 0x20, 0x30, 0x40)

        try {
            val accepted = repo.submit(
                AsyncUploadPayload(imageBytes, "image/jpeg; charset=binary"),
                requestIds.first(),
                "instrumented-retry-key-1",
                connection,
            )
            assertEquals(jobId, accepted.jobId)
            assertEquals(JobState.QUEUED, accepted.status)
            assertEquals("/v1/passport/jobs/$jobId", accepted.statusUrl)

            val queued = repo.readStatus(jobId, requestIds[1], connection)
            val processing = repo.readStatus(jobId, requestIds[2], connection)
            val completed = repo.readStatus(jobId, requestIds[3], connection)
            val failed = repo.readStatus(jobId, requestIds[4], connection)

            assertTrue(queued is JobStatus.Queued)
            assertTrue(processing is JobStatus.Processing)
            assertTrue(completed is JobStatus.Completed)
            assertEquals(jobId, (completed as JobStatus.Completed).jobId)
            assertEquals("TEST", completed.result.values.getValue(ru.passport.scanner.model.PassportField.SURNAME).value)
            assertTrue(failed is JobStatus.Failed)
            assertEquals("processing_timeout", (failed as JobStatus.Failed).errorCode)

            serverThread.join(5_000)
            assertTrue("Fixture server did not finish its bounded request sequence.", !serverThread.isAlive)
            serverFailure.get()?.let { throw AssertionError("Loopback fixture failed.", it) }
            assertEquals(5, fixture.requests.size)

            val post = fixture.requests.first()
            assertEquals("POST", post.method)
            assertEquals("/v1/passport/jobs", post.path)
            assertTrue("Bearer header was not preserved.", post.headers["authorization"] == "Bearer $token")
            assertEquals("instrumented-retry-key-1", post.headers["idempotency-key"])
            assertEquals(requestIds.first(), post.headers["x-request-id"])
            val multipart = String(post.body, StandardCharsets.ISO_8859_1)
            assertTrue(multipart.contains("name=\"file\"; filename=\"scan\""))
            assertTrue(multipart.contains("Content-Type: image/jpeg\r\n"))
            assertTrue(multipart.contains(String(imageBytes, StandardCharsets.ISO_8859_1)))

            fixture.requests.drop(1).forEachIndexed { index, request ->
                assertEquals("GET", request.method)
                assertEquals("/v1/passport/jobs/$jobId", request.path)
                assertTrue("Bearer header was not preserved on status read.", request.headers["authorization"] == "Bearer $token")
                assertEquals(requestIds[index + 1], request.headers["x-request-id"])
                assertEquals(null, request.headers["idempotency-key"])
            }
        } finally {
            repo.close()
            fixture.close()
            serverThread.join(1_000)
        }
    }

    private data class Response(val status: Int, val body: String)
    private data class CapturedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private class LoopbackFixture(private val responses: List<Response>) : AutoCloseable {
        private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val port: Int get() = server.localPort
        val requests = CopyOnWriteArrayList<CapturedRequest>()

        fun serve() {
            repeat(responses.size) { index ->
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val request = readRequest(socket)
                    requests += request
                    val response = responses[index]
                    val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
                    val statusText = if (response.status == 202) "Accepted" else "OK"
                    val headers = buildString {
                        append("HTTP/1.1 ${response.status} $statusText\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Cache-Control: no-store\r\n")
                        append("Connection: close\r\n")
                        append("Content-Length: ${bytes.size}\r\n\r\n")
                    }.toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().apply {
                        write(headers)
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        private fun readRequest(socket: Socket): CapturedRequest {
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
            return CapturedRequest(requestLine[0], requestLine[1], headers, body)
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

        override fun close() {
            runCatching { server.close() }
        }
    }

    private companion object {
        const val JOB_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val CREATED = "2026-10-31T10:00:00Z"
        const val STARTED = "2026-10-31T10:01:00Z"
        const val FINISHED = "2026-10-31T10:02:00Z"

        fun acceptedBody() = """{"job_id":"$JOB_ID","status":"queued","status_url":"/v1/passport/jobs/$JOB_ID","created_at":"$CREATED"}"""

        fun statusBody(state: String): String = when (state) {
            "queued" -> """{"job_id":"$JOB_ID","status":"queued","created_at":"$CREATED","updated_at":"$CREATED","started_at":null,"finished_at":null}"""
            "processing" -> """{"job_id":"$JOB_ID","status":"processing","created_at":"$CREATED","updated_at":"$STARTED","started_at":"$STARTED","finished_at":null}"""
            "completed" -> """{"job_id":"$JOB_ID","status":"completed","created_at":"$CREATED","updated_at":"$FINISHED","started_at":"$STARTED","finished_at":"$FINISHED","result":${canonicalResult()}}"""
            else -> """{"job_id":"$JOB_ID","status":"failed","created_at":"$CREATED","updated_at":"$FINISHED","started_at":null,"finished_at":"$FINISHED","error":{"code":"processing_timeout","message":"safe test failure"}}"""
        }

        fun canonicalResult(): String = """
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
              },
              "warnings":[]
            }
        """.trimIndent()
    }
}
