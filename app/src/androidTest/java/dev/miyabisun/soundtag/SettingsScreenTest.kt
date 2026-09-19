package dev.miyabisun.soundtag

import android.nfc.NdefMessage
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    private val address = "00:11:22:33:44:AA"

    @Test fun allowedSpeakerAndAllDisconnectAreWrittenThenReceivedWithoutClipboard() {
        val fake = WriterSettings(address)
        val nfc = ScreenNfc()
        val bluetooth = object : BluetoothAccess {
            var connected = emptySet<String>()
            val calls = mutableListOf<String>()
            var notify: () -> Unit = {}
            override fun snapshot() = BluetoothSnapshot(true, true, true,
                setOf(address), fake.saved, fake.saved, connected, connected)
            override fun connect(address: String): Boolean { calls += "connect:$address"; return true }
            override fun disconnect(address: String): Boolean { calls += "disconnect:$address"; return true }
        }
        MainActivity.accessFactory = { fake }
        MainActivity.nfcFactory = { nfc }
        NfcService.bluetoothFactory = { _, changed -> bluetooth.apply { notify = changed } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                screen.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Switch>().single().performClick()
                    assertEquals(setOf(address), fake.saved)
                    click(activity.window.decorView, "デスクのスピーカー")
                    assertLabel(activity.window.decorView, "特定の機器の接続")
                    assertTrue(nfc.messages.isEmpty())
                    assertTrue(bluetooth.calls.isEmpty())
                    click(activity.window.decorView, "タグに書き込む")
                    assertLabel(activity.window.decorView, "タグをかざしてください")
                }
                screenshot("writer-waiting")
                screen.onActivity { activity ->
                    nfc.detected()
                    assertLabel(activity.window.decorView, "書込み中…")
                    nfc.complete(null)
                    assertLabel(activity.window.decorView, "書き込みました")
                    assertTrue(bluetooth.calls.isEmpty())
                }
                screenshot("writer-success")
                screen.onActivity { activity ->
                    click(activity.window.decorView, "タグを離して戻る")
                    click(activity.window.decorView, "スピーカー一覧")
                    click(activity.window.decorView, "全て切断")
                    click(activity.window.decorView, "タグに書き込む")
                    nfc.detected()
                    nfc.complete(WriteFailure.READ_ONLY)
                    assertLabel(activity.window.decorView, "このタグは書込みできません")
                }
                screenshot("writer-failure")
                screen.onActivity { activity ->
                    click(activity.window.decorView, "もう一度書き込む")
                    nfc.detected()
                    nfc.complete(null)
                    assertLabel(activity.window.decorView, "書き込みました")
                    click(activity.window.decorView, "タグを離して戻る")
                }
            }
            val codes = nfc.messages.map { message ->
                assertEquals(1, message.records.size)
                message.records.single().toUri().toString()
            }
            assertEquals(listOf("soundtag://connect/$address", "soundtag://phone", "soundtag://phone"), codes)
            for ((index, code) in listOf(codes.first(), codes.last()).withIndex()) {
                deliverTag(code)
                eventually { bluetooth.calls.size == index + 1 }
                appThread {
                    assertEquals(if (index == 0) "connect:$address" else "disconnect:$address", bluetooth.calls.last())
                    bluetooth.connected = if (index == 0) setOf(address) else emptySet()
                    bluetooth.notify()
                }
                eventually { NfcService.pendingSession == null }
            }

            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                screen.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Switch>().single().performClick()
                    assertTrue(fake.saved.isEmpty())
                    assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.text.toString() == "デスクのスピーカー" }.isEnabled)
                }
            }
        } finally {
            MainActivity.accessFactory = null
            MainActivity.nfcFactory = null
            NfcService.bluetoothFactory = null
        }
    }

    @Test fun screenRecreationCancelsWaitingAndOldCallbacksCannotWriteANewSelection() {
        val fake = WriterSettings(address).apply { saved = setOf(address) }
        val nfc = ScreenNfc()
        MainActivity.accessFactory = { fake }
        MainActivity.nfcFactory = { nfc }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                screen.onActivity { activity ->
                    click(activity.window.decorView, "全て切断")
                    click(activity.window.decorView, "タグに書き込む")
                }
                val old = nfc.detected
                screen.recreate()
                screen.onActivity { activity ->
                    old()
                    assertTrue(nfc.messages.isEmpty())
                    assertLabel(activity.window.decorView, "タグに書き込む")
                    nfc.startFailure = WriteFailure.DISABLED
                    click(activity.window.decorView, "タグに書き込む")
                    assertLabel(activity.window.decorView, "NFCがOFFです")
                }
                screenshot("writer-nfc-off")
            }
        } finally {
            MainActivity.accessFactory = null
            MainActivity.nfcFactory = null
        }
    }

    private fun click(view: View, text: String) = descendants(view).filterIsInstance<Button>()
        .first { it.text.toString() == text }.performClick()
    private fun assertLabel(view: View, text: String) = assertTrue(text,
        descendants(view).filterIsInstance<TextView>().any { it.text.toString() == text })
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$name.png")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
    }
}

private class WriterSettings(private val address: String) : SettingsAccess {
    var saved = emptySet<String>()
    override fun hasPermission() = true
    override fun bluetoothEnabled() = true
    override fun speakers() = listOf(Speaker(address, "デスクのスピーカー", true))
    override fun associations() = setOf(address)
    override fun allowed() = saved
    override fun saveAllowed(addresses: Set<String>) { saved = addresses }
    override fun associate(address: String, complete: (Boolean) -> Unit) = complete(true)
    override fun disassociate(address: String) {}
}

private class ScreenNfc : NfcWriting {
    var detected: () -> Unit = {}
    var complete: (WriteFailure?) -> Unit = {}
    var startFailure: WriteFailure? = null
    val messages = mutableListOf<NdefMessage>()
    override fun start(discovered: () -> Unit): WriteFailure? { detected = discovered; return startFailure }
    override fun write(uri: String, complete: (WriteFailure?) -> Unit) {
        messages += tagMessage(uri)
        this.complete = complete
    }
    override fun stop() {}
}
