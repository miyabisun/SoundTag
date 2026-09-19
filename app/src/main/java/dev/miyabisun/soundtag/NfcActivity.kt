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
                startForegroundService(Intent(this, NfcService::class.java).setData(intent.data))
            }
        } catch (_: IllegalStateException) {
            // Android declined background execution; no connection changed and no UI is opened.
        } catch (_: SecurityException) {
            // Revoked OS permissions must never replace the user's current screen.
        } finally {
            finish()
        }
    }
}
