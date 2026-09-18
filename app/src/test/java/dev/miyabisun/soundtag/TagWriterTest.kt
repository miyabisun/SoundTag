package dev.miyabisun.soundtag

import org.junit.Assert.*
import org.junit.Test

class TagWriterTest {
    private val address = "00:11:22:33:44:AA"
    private fun settings() = FakeSettings(address).apply {
        saved = setOf(address)
        associated.add(address)
    }

    @Test fun explicitStartWritesEachCommandOnceAndWaitsForCompletion() {
        for (command in listOf(TagCommand.Connect(address), TagCommand.Phone)) {
            val nfc = FakeNfc()
            val writer = TagWriter(SettingsController(settings()), nfc) {}
            assertEquals(WritePhase.IDLE, writer.phase)
            assertTrue(nfc.writes.isEmpty())
            writer.start(command)
            assertEquals(WritePhase.WAITING, writer.phase)
            nfc.detected()
            nfc.detected()
            assertEquals(listOf(command.uri()), nfc.writes)
            assertEquals(command, TagCommand.parse(nfc.writes.single()))
            assertEquals(WritePhase.WRITING, writer.phase)
            nfc.complete(null)
            assertEquals(WritePhase.SUCCEEDED, writer.phase)
            nfc.detected()
            assertEquals(1, nfc.writes.size)
            writer.cancel()
            assertEquals(WritePhase.IDLE, writer.phase)
        }
    }

    @Test fun failuresAreVisibleAndRetryRequiresAnotherExplicitStart() {
        for (failure in WriteFailure.entries) {
            val nfc = FakeNfc()
            val writer = TagWriter(SettingsController(settings()), nfc) {}
            writer.start(TagCommand.Phone)
            nfc.detected()
            nfc.complete(failure)
            assertEquals(WritePhase.FAILED, writer.phase)
            assertEquals(failure, writer.failure)
            nfc.detected()
            assertEquals(1, nfc.writes.size)
            writer.start(TagCommand.Phone)
            nfc.detected()
            nfc.complete(null)
            assertEquals(WritePhase.SUCCEEDED, writer.phase)
            assertEquals(2, nfc.writes.size)
        }
        for (failure in listOf(WriteFailure.UNAVAILABLE, WriteFailure.DISABLED, WriteFailure.IO)) {
            val nfc = FakeNfc().apply { startFailure = failure }
            val writer = TagWriter(SettingsController(settings()), nfc) {}
            writer.start(TagCommand.Phone)
            assertEquals(failure, writer.failure)
            assertTrue(nfc.writes.isEmpty())
        }
    }

    @Test fun cancelledOrSupersededCallbacksCannotWriteOrChangeTheNewSelection() {
        val nfc = FakeNfc()
        val writer = TagWriter(SettingsController(settings()), nfc) {}
        writer.start(TagCommand.Connect(address))
        val oldTag = nfc.detected
        nfc.detected()
        val oldResult = nfc.complete
        writer.cancel()
        oldTag()
        oldResult(null)
        assertEquals(WritePhase.IDLE, writer.phase)
        writer.start(TagCommand.Phone)
        oldTag()
        oldResult(WriteFailure.IO)
        assertEquals(WritePhase.WAITING, writer.phase)
        assertEquals(listOf("soundtag://connect/$address"), nfc.writes)
        nfc.detected()
        assertEquals("soundtag://phone", nfc.writes.last())
    }

    @Test fun onlyCurrentlyAllowedConnectAndAllDisconnectCanBeWritten() {
        val access = settings()
        val nfc = FakeNfc()
        val writer = TagWriter(SettingsController(access), nfc) {}
        for (command in listOf(TagCommand.Disconnect(address), TagCommand.Connect("invalid"))) {
            writer.start(command)
            assertEquals(WriteFailure.NOT_ALLOWED, writer.failure)
            assertEquals(0, nfc.starts)
        }
        writer.start(TagCommand.Connect(address))
        access.saved = emptySet()
        nfc.detected()
        assertEquals(WriteFailure.NOT_ALLOWED, writer.failure)
        assertTrue(nfc.writes.isEmpty())
        writer.start(TagCommand.Phone)
        assertEquals(WriteFailure.NOT_ALLOWED, writer.failure)
        assertEquals(1, nfc.starts)
    }

    @Test fun capacityAndReadOnlyAreCheckedBeforeOverwriting() {
        assertEquals(WriteFailure.READ_ONLY, writableFailure(false, 128, 40))
        assertEquals(WriteFailure.TOO_SMALL, writableFailure(true, 39, 40))
        assertNull(writableFailure(true, 40, 40))
    }
}

private class FakeNfc : NfcWriting {
    var starts = 0
    var startFailure: WriteFailure? = null
    var detected: () -> Unit = {}
    var complete: (WriteFailure?) -> Unit = {}
    val writes = mutableListOf<String>()
    override fun start(discovered: () -> Unit): WriteFailure? {
        starts++
        detected = discovered
        return startFailure
    }
    override fun write(uri: String, complete: (WriteFailure?) -> Unit) {
        writes += uri
        this.complete = complete
    }
    override fun stop() {}
}
