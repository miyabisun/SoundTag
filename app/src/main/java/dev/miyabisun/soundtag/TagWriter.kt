package dev.miyabisun.soundtag

/** Reader callbacks and completion are delivered on the main thread. I/O belongs to the adapter. */
internal interface NfcWriting {
    fun start(discovered: () -> Unit): WriteFailure?
    fun write(uri: String, complete: (WriteFailure?) -> Unit)
    fun stop()
}

internal enum class WritePhase { IDLE, WAITING, WRITING, SUCCEEDED, FAILED }
internal enum class WriteFailure { UNAVAILABLE, DISABLED, UNSUPPORTED, READ_ONLY, TOO_SMALL, LOST, IO, NOT_ALLOWED }

internal fun writableFailure(writable: Boolean, capacity: Int, size: Int): WriteFailure? = when {
    !writable -> WriteFailure.READ_ONLY
    size > capacity -> WriteFailure.TOO_SMALL
    else -> null
}

internal class TagWriter(
    private val settings: SettingsController,
    private val nfc: NfcWriting,
    private val changed: () -> Unit,
) {
    var phase = WritePhase.IDLE
        private set
    var failure: WriteFailure? = null
        private set
    private var revision = 0

    fun start(command: TagCommand) {
        cancel()
        val request = revision
        val uri = code(command)
        if (uri == null) { finish(WriteFailure.NOT_ALLOWED); return }
        phase = WritePhase.WAITING
        val error = nfc.start {
            if (request == revision && phase == WritePhase.WAITING) {
                if (code(command) != uri) finish(WriteFailure.NOT_ALLOWED)
                else {
                    phase = WritePhase.WRITING
                    changed()
                    nfc.write(uri) { result ->
                        if (request == revision && phase == WritePhase.WRITING) finish(result)
                    }
                }
            }
        }
        if (error != null) finish(error) else changed()
    }

    private fun code(command: TagCommand): String? = try {
        if (command is TagCommand.Disconnect) null else settings.code(command)
    } catch (_: RuntimeException) { null }

    private fun finish(error: WriteFailure?) {
        failure = error
        phase = if (error == null) WritePhase.SUCCEEDED else WritePhase.FAILED
        changed()
    }

    fun cancel() {
        revision++
        nfc.stop()
        phase = WritePhase.IDLE
        failure = null
    }
}
