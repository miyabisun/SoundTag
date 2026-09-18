package dev.miyabisun.soundtag

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class NfcFlowTest {
    private val address = "00:11:22:33:44:AA"

    @Test fun copiedTagCompletesAndSurvivesDuplicateAndRecreation() {
        val fake = NfcFake(address)
        val settings = SettingsController(fake)
        settings.setAllowed(address, true) { assertEquals(SettingsResult.SAVED, it) }
        assertTrue(settings.copy(TagCommand.Connect(address)))
        NfcActivity.bluetoothFactory = { _, changed -> fake.apply { notify = changed } }
        try {
            ActivityScenario.launch<NfcActivity>(tag(fake.copied)).use { screen ->
                screen.onActivity { activity ->
                    assertLabel(activity.window.decorView, "切替中…")
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag(fake.copied))
                    assertEquals(listOf("connect:$address"), fake.calls)
                }
                screen.recreate()
                screen.onActivity { activity ->
                    assertLabel(activity.window.decorView, "切替中…")
                    assertEquals(listOf("connect:$address"), fake.calls)
                    fake.connections = setOf(address)
                    fake.notify()
                    assertLabel(activity.window.decorView, "接続しました")
                }
                screenshot("soundtag-connected")
                screen.onActivity { activity ->
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag(fake.copied))
                    assertEquals(1, fake.calls.size)
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag("soundtag://connect/invalid"))
                    assertLabel(activity.window.decorView, "タグを確認してください")
                    assertEquals(1, fake.calls.size)
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag("soundtag://phone"))
                    assertEquals("disconnect:$address", fake.calls.last())
                    fake.connections = emptySet()
                    fake.notify()
                    assertLabel(activity.window.decorView, "スマホに戻しました")
                    settings.setAllowed(address, false) { assertEquals(SettingsResult.SAVED, it) }
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag(fake.copied))
                    assertLabel(activity.window.decorView, "機器の許可を確認してください")
                    assertEquals(2, fake.calls.size)
                }
                screenshot("soundtag-denied")
            }
        } finally { NfcActivity.bluetoothFactory = null }
    }

    @Test fun invalidActionAndTimeoutFailAndClosingCancelsThePendingOperation() {
        val fake = NfcFake(address).apply { saved = setOf(address); associated = saved }
        NfcActivity.bluetoothFactory = { _, changed -> fake.apply { notify = changed } }
        try {
            ActivityScenario.launch<NfcActivity>(tag("soundtag://connect/$address").setAction(Intent.ACTION_VIEW)).use { screen ->
                screen.onActivity { activity ->
                    assertLabel(activity.window.decorView, "タグを確認してください")
                    assertTrue(fake.calls.isEmpty())
                    fake.permission = false
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag("soundtag://connect/$address"))
                    assertLabel(activity.window.decorView, "権限を確認してください")
                    assertTrue(fake.calls.isEmpty())
                    fake.permission = true
                    InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, tag("soundtag://connect/$address"))
                    activity.session.tick(SystemClock.elapsedRealtime() + SwitchController.TIMEOUT_MS)
                    assertLabel(activity.window.decorView, "接続を確認できませんでした")
                    assertEquals(listOf("connect:$address", "disconnect:$address"), fake.calls)
                    fake.confirmed = setOf(address)
                    fake.notify()
                }
            }
        } finally { NfcActivity.bluetoothFactory = null }
    }

    @Test fun closingOrBackgroundTimeoutCannotLetTheNextTagPassAnUnfinishedCancellation() {
        val b = "00:11:22:33:44:BB"
        for (backgroundTimeout in listOf(false, true)) {
            val fake = NfcFake(address).apply { saved = setOf(address, b); associated = saved }
            NfcActivity.bluetoothFactory = { _, changed -> fake.apply { notify = changed } }
            try {
                ActivityScenario.launch<NfcActivity>(tag("soundtag://connect/$address")).use { screen ->
                    if (backgroundTimeout) {
                        screen.moveToState(Lifecycle.State.CREATED)
                        screen.onActivity { it.session.tick(SystemClock.elapsedRealtime() + SwitchController.TIMEOUT_MS) }
                    }
                }
                assertEquals(listOf("connect:$address", "disconnect:$address"), fake.calls)
                ActivityScenario.launch<NfcActivity>(tag("soundtag://connect/$b")).use { screen ->
                    screen.onActivity { activity ->
                        assertEquals(2, fake.calls.size)
                        assertLabel(activity.window.decorView, "切替中…")
                        fake.connections = setOf(address)
                        fake.notify()
                        assertEquals("disconnect:$address", fake.calls.last())
                        fake.connections = emptySet()
                        fake.confirmed = setOf(address)
                        fake.notify()
                        assertEquals("connect:$b", fake.calls.last())
                        fake.connections = setOf(b)
                        fake.notify()
                        assertLabel(activity.window.decorView, "接続しました")
                    }
                }
            } finally { NfcActivity.bluetoothFactory = null }
        }
    }

    private fun tag(code: String?) = Intent(InstrumentationRegistry.getInstrumentation().targetContext, NfcActivity::class.java)
        .setAction(NfcAdapter.ACTION_NDEF_DISCOVERED).setData(Uri.parse(checkNotNull(code)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun assertLabel(view: View, text: String) {
        fun labels(view: View): List<String> =
            (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { labels(view.getChildAt(it)) } else emptyList()
        assertTrue("Missing $text", text in labels(view))
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
    }
}

private class NfcFake(private val address: String) : SettingsAccess, BluetoothAccess {
    var permission = true
    var saved = emptySet<String>()
    var associated = emptySet<String>()
    var connections = emptySet<String>()
    var confirmed = emptySet<String>()
    var copied: String? = null
    var notify: () -> Unit = {}
    val calls = mutableListOf<String>()
    override fun hasPermission() = permission
    override fun bluetoothEnabled() = true
    override fun speakers() = listOf(Speaker(address, "デスクのスピーカー", address in connections))
    override fun associations() = associated
    override fun allowed() = saved
    override fun saveAllowed(addresses: Set<String>) { saved = addresses }
    override fun associate(address: String, complete: (Boolean) -> Unit) { associated += address; complete(true) }
    override fun disassociate(address: String) { associated -= address }
    override fun copy(text: String) { copied = text }
    override fun snapshot() = BluetoothSnapshot(permission, true, true,
        setOf(address, "00:11:22:33:44:BB"), saved, associated, connections, connections,
        confirmedDisconnections = confirmed)
    override fun connect(address: String): Boolean { calls += "connect:$address"; confirmed -= address; return true }
    override fun disconnect(address: String): Boolean { calls += "disconnect:$address"; confirmed -= address; return true }
}
