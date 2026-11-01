package ru.passport.scanner.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus

class InMemoryPendingScanStoreTest {
    @Test
    fun exactUploadAndKeyRemainAvailableForSameProcessRetryThenAreWipedAfter202() = runTest {
        val store = InMemoryPendingScanStore()
        val bytes = byteArrayOf(7, 8, 9)
        val payload = AsyncUploadPayload(bytes, "image/jpeg")
        bytes.fill(0)
        val operation = PendingScanOperation.Submitting(OPERATION_ID, CONNECTION_ID, "retry-key", payload)

        store.beginSubmitting(operation)
        val retry = store.load() as PendingScanOperation.Submitting
        assertEquals("retry-key", retry.idempotencyKey)
        assertArrayEquals(byteArrayOf(7, 8, 9), retry.upload.copyBytes())
        assertTrue(store.markAccepted(OPERATION_ID, JOB_ID))
        assertArrayEquals(byteArrayOf(0, 0, 0), payload.copyBytes())
        assertTrue(store.load() is PendingScanOperation.Accepted)
        assertNull(InMemoryPendingScanStore().load())
    }

    @Test
    fun terminalResultAndReceiptStateAreOnlyInCurrentStoreInstance() = runTest {
        val store = InMemoryPendingScanStore()
        store.beginSubmitting(PendingScanOperation.Submitting(OPERATION_ID, CONNECTION_ID, "retry-key", AsyncUploadPayload(byteArrayOf(1), "image/png")))
        assertTrue(store.markAccepted(OPERATION_ID, JOB_ID))
        val terminal = TerminalJobSnapshot.Completed("fixture-pipeline", draft())

        assertTrue(store.markReceived(OPERATION_ID, JOB_ID, terminal))
        assertFalse(store.markReceived("11111111-1111-4111-8111-111111111111", JOB_ID, terminal))
        assertTrue(store.markReceiptAcknowledged(OPERATION_ID, JOB_ID))
        val received = store.load() as PendingScanOperation.Received
        assertTrue(received.receiptAcknowledged)
        assertEquals(terminal, received.terminal)
        assertNull(InMemoryPendingScanStore().load())
    }

    private fun draft() = PassportDraft(PassportField.entries.associateWith { PassportValue("", RecognitionStatus.MISSING) })

    private companion object {
        const val CONNECTION_ID = "7e4f6ad1-8a0f-4cc4-8c33-483477f9ae83"
        const val OPERATION_ID = "9cfe2e2f-135d-42f4-8db5-ddabc5a2f831"
        const val JOB_ID = "b305da58-4913-4805-9094-e80c1dc27d36"
    }
}
