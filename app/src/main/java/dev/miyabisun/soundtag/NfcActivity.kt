package dev.miyabisun.soundtag

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle

/** Only Android's NFC dispatcher (or this app's own UID) can open this windowless entry. */
class NfcActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (savedInstanceState == null && intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED &&
                intent.dataString?.let(TagCommand::parse) != null) {
                YouTubeRecovery.schedule(this)
                startForegroundService(Intent(this, NfcService::class.java).setData(intent.data))
            }
        } catch (_: IllegalStateException) {
            // Android declined background Bluetooth execution; do not open settings.
        } catch (_: SecurityException) {
            // Revoked Bluetooth permissions must not open a permission screen from a tag.
        } finally {
            finish()
        }
    }
}
