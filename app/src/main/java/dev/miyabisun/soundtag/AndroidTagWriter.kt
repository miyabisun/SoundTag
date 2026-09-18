package dev.miyabisun.soundtag

import android.app.Activity
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.TagTechnology
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

internal fun tagMessage(uri: String) = NdefMessage(arrayOf(NdefRecord.createUri(uri)))

internal class AndroidTagWriter(private val activity: Activity) : NfcWriting, AutoCloseable {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var revision = 0
    private var reading = false
    private var tag: Tag? = null
    private val lock = Any()
    private var technology: TagTechnology? = null

    override fun start(discovered: () -> Unit): WriteFailure? {
        stop()
        val nfc = adapter ?: return WriteFailure.UNAVAILABLE
        return try {
            if (!nfc.isEnabled) return WriteFailure.DISABLED
            val request = revision
            // Reader mode owns tag discovery: existing URI records cannot launch NfcActivity here.
            nfc.enableReaderMode(activity, { found ->
                handler.post {
                    if (request == revision && reading && tag == null) {
                        tag = found
                        discovered()
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
            reading = true
            null
        } catch (_: RuntimeException) { WriteFailure.IO }
    }

    override fun write(uri: String, complete: (WriteFailure?) -> Unit) {
        val found = tag ?: return complete(WriteFailure.LOST)
        val request = revision
        val message = tagMessage(uri)
        worker.execute {
            if (request != revision) return@execute
            val result = writeTag(found, message, request)
            handler.post { if (request == revision) complete(result) }
        }
    }

    private fun writeTag(found: Tag, message: NdefMessage, request: Int): WriteFailure? {
        val ndef = Ndef.get(found)
        val target: TagTechnology = ndef ?: NdefFormatable.get(found) ?: return WriteFailure.UNSUPPORTED
        synchronized(lock) {
            if (request != revision) return WriteFailure.IO
            technology = target
        }
        return try {
            target.connect()
            if (request != revision) return WriteFailure.IO
            if (ndef != null) {
                writableFailure(ndef.isWritable, ndef.maxSize, message.toByteArray().size)?.let { return it }
                if (request != revision) return WriteFailure.IO
                ndef.writeNdefMessage(message)
            } else {
                (target as NdefFormatable).format(message)
            }
            null
        } catch (_: TagLostException) { WriteFailure.LOST }
        catch (_: IOException) { WriteFailure.IO }
        catch (_: FormatException) { WriteFailure.UNSUPPORTED }
        catch (_: RuntimeException) { WriteFailure.IO }
        finally {
            try { target.close() } catch (_: Exception) { /* The write result remains authoritative. */ }
            synchronized(lock) { if (technology === target) technology = null }
        }
    }

    override fun stop() {
        revision++
        tag = null
        if (reading) {
            try { adapter?.disableReaderMode(activity) } catch (_: RuntimeException) { /* Activity may be paused. */ }
            reading = false
        }
        val pending = synchronized(lock) { technology.also { technology = null } }
        // close() aborts blocked native I/O; do not queue it behind that same I/O or block the UI.
        if (pending != null) Thread {
            try { pending.close() } catch (_: Exception) { /* A removed tag is already closed. */ }
        }.start()
    }

    override fun close() {
        stop()
        worker.shutdown()
    }
}
