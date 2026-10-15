package dev.miyabisun.soundtag

import android.accessibilityservice.AccessibilityServiceInfo
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Places the widget in the real launcher; only Bluetooth and settings are fakes. */
@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val desk = "00:11:22:33:44:AA"
    private val living = "00:11:22:33:44:BB"
    private val watch = "00:11:22:33:44:CC"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val widget get() = ComponentName(context, SpeakerWidget::class.java)

    @Test fun tapsSwitchWithoutOpeningTheAppAndIconsFollowTheResult() {
        val bluetooth = WidgetBluetooth().apply { connected = setOf(living) }
        val settings = WidgetSettings(bluetooth).apply { saved = setOf(desk, living) }
        SpeakerWidget.accessFactory = { settings }
        MainActivity.accessFactory = { settings }
        NfcService.bluetoothFactory = { _, changed -> bluetooth.apply { notify = changed } }
        try {
            placeWidget()
            shell("input keyevent KEYCODE_HOME")
            appThread { SpeakerWidget.update(context) }
            waitFor("デスクのスピーカー、未接続", "リビング、接続中", "全て切断")
            assertFalse(screen().contains("時計"))
            SystemClock.sleep(1_000)
            snap("widget-initial-light")

            tap("デスクのスピーカー、未接続")
            eventually(10_000) { bluetooth.calls.isNotEmpty() }
            assertEquals(listOf("disconnect:$living"), bluetooth.calls)
            appThread { bluetooth.connected = emptySet(); bluetooth.notify() }
            eventually(10_000) { bluetooth.calls.size == 2 }
            assertEquals("connect:$desk", bluetooth.calls.last())
            appThread { bluetooth.connected = setOf(desk); bluetooth.notify() }
            waitFor("デスクのスピーカー、接続中", "リビング、未接続")
            assertLauncherInFront()
            snap("widget-switched-light")

            tap("全て切断")
            eventually(10_000) { bluetooth.calls.last() == "disconnect:$desk" }
            appThread { bluetooth.connected = emptySet(); bluetooth.notify() }
            waitFor("デスクのスピーカー、未接続", "リビング、未接続")
            assertLauncherInFront()
            shrinkAndScroll()

            shell("cmd uimode night yes")
            SystemClock.sleep(1_500)
            waitFor("デスクのスピーカー、未接続")
            snap("widget-dark")

            settings.saved = emptySet()
            appThread { SpeakerWidget.update(context) }
            waitFor("SoundTagを開く")
            assertFalse(screen().contains("全て切断"))
            snap("widget-no-allowed-dark")
            shell("cmd uimode night no")
            SystemClock.sleep(1_500)
            settings.permission = false
            context.getSharedPreferences("MainActivity", 0).edit().putBoolean("permissionAsked", true).commit()
            appThread { SpeakerWidget.update(context) }
            waitFor("SoundTagを開く")
            snap("widget-no-permission-light")
            tap("SoundTagを開く")
            eventually(10_000) { front().contains("dev.miyabisun.soundtag/.MainActivity") }
        } finally {
            shell("cmd uimode night no")
            shell("input keyevent KEYCODE_HOME")
            SpeakerWidget.accessFactory = null
            MainActivity.accessFactory = null
            NfcService.bluetoothFactory = null
        }
    }

    /** The smallest size keeps one row; the rest stays reachable by scrolling the list. */
    private fun shrinkAndScroll() {
        val list = nodes().first { it.type == "android.widget.ListView" }.bounds
        shell("input swipe ${list.centerX()} ${list.centerY()} ${list.centerX()} ${list.centerY()} 1500")
        SystemClock.sleep(1_000)
        shell("input swipe ${list.centerX()} ${list.bottom + 8} ${list.centerX()} ${list.top} 1500")
        SystemClock.sleep(1_000)
        shell("input keyevent KEYCODE_BACK")
        eventually(10_000) { nodes().none { it.description == "全て切断" || it.text == "全て切断" } }
        snap("widget-small-top")
        repeat(3) {
            nodes().firstOrNull { it.type == "android.widget.ListView" }
                ?.info?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(500)
        }
        waitFor("全て切断")
        snap("widget-small-scrolled")
        tap("全て切断")
        assertLauncherInFront()
        nodes().first { it.type == "android.widget.ListView" }.info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        SystemClock.sleep(500)
    }

    private fun placeWidget() {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.getAppWidgetIds(widget).isNotEmpty()) return
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(manager.requestPinAppWidget(widget, null, null)) }
            val button = Regex("(?i)^(add to home screen|add|追加|ホーム画面に追加)$")
            eventually(10_000) { nodes().any { button.matches(it.text) } }
            val node = nodes().last { button.matches(it.text) }
            shell("input tap ${node.x} ${node.y}")
            eventually(10_000) { manager.getAppWidgetIds(widget).isNotEmpty() }
        }
    }

    private class Node(val text: String, val description: String, val x: Int, val y: Int,
                       val bounds: Rect, val type: String, val info: AccessibilityNodeInfo)

    private fun nodes(): List<Node> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val found = mutableListOf<Node>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            val bounds = Rect().also(node::getBoundsInScreen)
            found += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                bounds.centerX(), bounds.centerY(), bounds, node.className?.toString().orEmpty(), node)
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        automation.windows.forEach { walk(it.root) }
        return found
    }
    private fun screen() = nodes().joinToString("\n") { "${it.text}|${it.description}" }
    private fun waitFor(vararg labels: String) = eventually(10_000) {
        val shown = nodes()
        labels.all { label -> shown.any { it.text == label || it.description == label } }
    }
    private fun tap(label: String) {
        val node = nodes().first { it.text == label || it.description == label }
        shell("input tap ${node.x} ${node.y}")
    }
    private fun front() = shell("dumpsys activity activities").lines().first { "topResumedActivity" in it }
    private fun assertLauncherInFront() {
        SystemClock.sleep(500)
        assertFalse("Widget tap opened SoundTag", front().contains("dev.miyabisun.soundtag"))
    }
    private fun snap(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        shell("screencap -p /data/local/tmp/$name.png")
    }
    private fun eventually(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        assertTrue("Timed out waiting for the launcher", condition())
    }
}

private class WidgetBluetooth : BluetoothAccess {
    val devices = setOf("00:11:22:33:44:AA", "00:11:22:33:44:BB", "00:11:22:33:44:CC")
    var allowed = emptySet<String>()
    var connected = emptySet<String>()
    var notify: () -> Unit = {}
    val calls = mutableListOf<String>()
    override fun snapshot() = BluetoothSnapshot(true, true, true, devices, allowed, allowed, connected, connected,
        confirmedDisconnections = devices - connected)
    override fun connect(address: String): Boolean { calls += "connect:$address"; return true }
    override fun disconnect(address: String): Boolean { calls += "disconnect:$address"; return true }
}

private class WidgetSettings(private val bluetooth: WidgetBluetooth) : SettingsAccess {
    var permission = true
    var saved: Set<String>
        get() = bluetooth.allowed
        set(value) { bluetooth.allowed = value }
    private val names = mapOf("00:11:22:33:44:AA" to "デスクのスピーカー", "00:11:22:33:44:BB" to "リビング",
        "00:11:22:33:44:CC" to "時計")
    override fun hasPermission() = permission
    override fun bluetoothEnabled() = permission
    override fun speakers() = names.map { (address, name) -> Speaker(address, name, address in bluetooth.connected) }
    override fun associations() = names.keys
    override fun allowed() = saved
    override fun saveAllowed(addresses: Set<String>) { saved = addresses }
    override fun associate(address: String, complete: (Boolean) -> Unit) = complete(true)
    override fun disassociate(address: String) {}
}
