package dev.miyabisun.soundtag

import android.nfc.NdefMessage
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject

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
                    assertLabel(activity.window.decorView, "デスクのスピーカー")
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

    @Test fun themesAndBackKeepDevicesReadableAndCancelWaiting() {
        val fake = WriterSettings(address).apply {
            saved = setOf(address)
            devices = listOf(Speaker("00:11:22:33:44:BB", "お風呂で使う防水スピーカー — Bose Flex SoundLink", false),
                Speaker(address, "デスクのスピーカー", true),
                Speaker("00:11:22:33:44:CC", "リビング", false))
        }
        val nfc = ScreenNfc()
        MainActivity.accessFactory = { fake }
        MainActivity.nfcFactory = { nfc }
        val backgrounds = mutableListOf<Int>()
        try {
            for (mode in listOf("no", "yes")) {
                shell("cmd uimode night $mode")
                ActivityScenario.launch(MainActivity::class.java).use { screen ->
                    screen.onActivity { activity ->
                        val views = descendants(activity.window.decorView)
                        backgrounds += (views.filterIsInstance<ScrollView>().single().background as ColorDrawable).color
                        assertLabel(activity.window.decorView, "接続中")
                        assertEquals(3, views.filterIsInstance<Switch>().size)
                        assertEquals("デスクのスピーカー", views.filterIsInstance<Button>().first().text.toString())
                    }
                    capture(screen, "settings-$mode")
                    screen.onActivity { activity -> click(activity.window.decorView, "デスクのスピーカー") }
                    capture(screen, "preview-$mode")
                    screen.onActivity { activity -> click(activity.window.decorView, "タグに書き込む") }
                    capture(screen, "waiting-$mode")
                    screen.onActivity { activity ->
                        nfc.detected()
                        nfc.complete(WriteFailure.READ_ONLY)
                    }
                    capture(screen, "failure-$mode")
                    screen.onActivity { activity ->
                        click(activity.window.decorView, "もう一度書き込む")
                        nfc.detected()
                        nfc.complete(null)
                    }
                    capture(screen, "success-$mode")
                    screen.onActivity { activity ->
                        click(activity.window.decorView, "タグを離して戻る")
                        assertLabel(activity.window.decorView, "デスクのスピーカー")
                    }
                }
            }
            assertEquals(listOf(Color.rgb(250, 246, 239), Color.rgb(25, 25, 25)), backgrounds)
            val writesBeforeBack = nfc.messages.size
            ActivityScenario.launch(MainActivity::class.java).use { screen ->
                var position = 0
                screen.onActivity { activity ->
                    val scroll = descendants(activity.window.decorView).filterIsInstance<ScrollView>().single()
                    scroll.scrollTo(0, 200)
                    position = scroll.scrollY
                    descendants(activity.window.decorView).filterIsInstance<Switch>().last().performClick()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                screen.onActivity { activity ->
                    assertEquals(position, descendants(activity.window.decorView).filterIsInstance<ScrollView>().single().scrollY)
                    click(activity.window.decorView, "全て切断")
                    click(activity.window.decorView, "タグに書き込む")
                }
                val old = nfc.detected
                shell("input keyevent KEYCODE_BACK")
                screen.onActivity { activity ->
                    old()
                    assertEquals(writesBeforeBack, nfc.messages.size)
                    assertLabel(activity.window.decorView, "デスクのスピーカー")
                }
            }
        } finally {
            shell("cmd uimode night no")
            MainActivity.accessFactory = null
            MainActivity.nfcFactory = null
        }
    }

    @Test fun unavailableSettingsExplainRecoveryInBothThemes() {
        val fake = WriterSettings(address).apply { devices = emptyList() }
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("MainActivity", 0)
            .edit().putBoolean("permissionAsked", true).commit()
        MainActivity.accessFactory = { fake }
        MainActivity.nfcFactory = { ScreenNfc() }
        try {
            for (mode in listOf("no", "yes")) {
                shell("cmd uimode night $mode")
                fake.permission = true
                fake.enabled = true
                ActivityScenario.launch(MainActivity::class.java).use { screen ->
                    screen.onActivity { activity ->
                        assertLabel(activity.window.decorView, "ペアリング済みの機器がありません")
                        assertFalse(descendants(activity.window.decorView).filterIsInstance<Button>()
                            .first { it.text.toString() == "全て切断" }.isEnabled)
                    }
                    capture(screen, "empty-$mode")
                    fake.enabled = false
                    screen.recreate()
                    screen.onActivity { assertLabel(it.window.decorView, "Bluetoothを開く") }
                    capture(screen, "bluetooth-off-$mode")
                    fake.permission = false
                    screen.recreate()
                    screen.onActivity { assertLabel(it.window.decorView, "権限を設定") }
                    capture(screen, "permission-$mode")
                }
            }
        } finally {
            shell("cmd uimode night no")
            MainActivity.accessFactory = null
            MainActivity.nfcFactory = null
        }
    }

    private fun capture(screen: ActivityScenario<MainActivity>, name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        screen.onActivity { activity ->
            val views = descendants(activity.window.decorView)
            assertEquals("Screen opens at top", 0, views.filterIsInstance<ScrollView>().single().scrollY)
            val metrics = JSONArray()
            for (view in views.filterIsInstance<TextView>()) {
                val layout = view.layout ?: continue
                for (line in 0 until layout.lineCount) {
                    assertEquals("Ellipsis: ${view.text}", 0, layout.getEllipsisCount(line))
                    assertTrue("Clipped: ${view.text}", layout.getLineWidth(line) <=
                        view.width - view.compoundPaddingLeft - view.compoundPaddingRight + 1)
                }
                if (view is Button || view is Switch) {
                    assertTrue("Small target: ${view.text}", view.height >= (48 * activity.resources.displayMetrics.density).toInt())
                }
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                metrics.put(JSONObject().put("text", view.text.toString()).put("x", location[0])
                    .put("y", location[1]).put("width", view.width).put("height", view.height))
            }
            val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "")
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommandRw("tee /data/local/tmp/$prefix$name.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(metrics.toString().toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        }
        screenshot(name)
        if (name.startsWith("settings")) {
            screen.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<ScrollView>().single().apply { scrollTo(0, getChildAt(0).height) }
                val last = views.filterIsInstance<Button>().last()
                val bounds = android.graphics.Rect()
                assertTrue("Last action reachable", last.getGlobalVisibleRect(bounds))
                assertEquals(last.height, bounds.height())
            }
            screenshot("$name-bottom")
            screen.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<ScrollView>().single().scrollTo(0, 0)
            }
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { FileInputStream(it.fileDescriptor).readBytes() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun click(view: View, text: String) = descendants(view).filterIsInstance<Button>()
        .first { it.text.toString() == text }.performClick()
    private fun assertLabel(view: View, text: String) = assertTrue(text,
        descendants(view).filterIsInstance<TextView>().any { it.text.toString() == text })
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix", "")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/$prefix$name.png")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
    }
}

private class WriterSettings(private val address: String) : SettingsAccess {
    var saved = emptySet<String>()
    var devices = listOf(Speaker(address, "デスクのスピーカー", true))
    var permission = true
    var enabled = true
    override fun hasPermission() = permission
    override fun bluetoothEnabled() = enabled
    override fun speakers() = devices
    override fun associations() = devices.map { it.address }.toSet()
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
