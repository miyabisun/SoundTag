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

/** Places 1×1 widgets in the real launcher; only Bluetooth and settings are fakes. */
@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val desk = "00:11:22:33:44:AA"
    private val living = "00:11:22:33:44:BB"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val widget get() = ComponentName(context, SpeakerWidget::class.java)

    @Test fun oneTargetWidgetsArePlacedSwitchedAndReconfigured() {
        val bluetooth = WidgetBluetooth().apply { connected = setOf(living) }
        val settings = WidgetSettings(bluetooth).apply { saved = setOf(desk, living) }
        SpeakerWidget.accessFactory = { settings }
        MainActivity.accessFactory = { settings }
        NfcService.bluetoothFactory = { _, changed -> bluetooth.apply { notify = changed } }
        try {
            // Widget registration from the open card is already bound to that card's target.
            var before = ids()
            ActivityScenario.launch(MainActivity::class.java).use {
                tap("全て切断")
                waitFor("Widget登録")
                tap("Widget登録")
                acceptPin()
            }
            eventually(10_000) { (ids() - before).size == 1 }
            val allWidget = (ids() - before).single()
            assertEquals(TagCommand.Phone, WidgetTargets(context).target(allWidget))
            assertFalse("Pinning asked for the target again", "WidgetConfigActivity" in front())

            // The OS configuration screen offers allowed speakers and disconnect-all; leaving it places nothing.
            before = ids()
            home("全て切断")
            addFromLauncher()
            waitFor("デスクのスピーカー、未接続", "リビング、接続中", "全て切断")
            assertFalse(screen().contains("時計"))
            snap("widget-config-light")
            shell("input keyevent KEYCODE_BACK")
            eventually(10_000) { "WidgetConfigActivity" !in front() }
            SystemClock.sleep(1_000)
            assertEquals("Cancelled configuration placed a widget", before, ids())

            home("全て切断")
            addFromLauncher()
            tap("デスクのスピーカー、未接続")
            eventually(10_000) { (ids() - before).size == 1 }
            val deskWidget = (ids() - before).single()
            assertEquals(TagCommand.Connect(desk), WidgetTargets(context).target(deskWidget))

            home("デスクのスピーカー、未接続", "全て切断")
            snap("widget-two-light")

            tap("デスクのスピーカー、未接続")
            eventually(10_000) { bluetooth.calls.isNotEmpty() }
            assertEquals(listOf("disconnect:$living"), bluetooth.calls)
            appThread { bluetooth.connected = emptySet(); bluetooth.notify() }
            eventually(10_000) { bluetooth.calls.size == 2 }
            assertEquals("connect:$desk", bluetooth.calls.last())
            appThread { bluetooth.connected = setOf(desk); bluetooth.notify() }
            waitFor("デスクのスピーカー、接続中")
            assertLauncherInFront()
            snap("widget-switched-light")

            tap("全て切断")
            eventually(10_000) { bluetooth.calls.last() == "disconnect:$desk" }
            appThread { bluetooth.connected = emptySet(); bluetooth.notify() }
            waitFor("デスクのスピーカー、未接続")
            assertLauncherInFront()

            shell("cmd uimode night yes")
            SystemClock.sleep(1_500)
            home("デスクのスピーカー、未接続", "全て切断")
            snap("widget-two-dark")

            // The launcher's reconfigure entry reopens the choice for the same widget.
            longPress("デスクのスピーカー、未接続")
            tapText(Regex("(?i)^(settings|設定|reconfigure|再設定)$"))
            waitFor("リビング、未接続")
            assertEquals("選択中", nodes().first { it.description == "デスクのスピーカー、未接続" }.info.stateDescription)
            snap("widget-reconfigure-dark")
            tap("リビング、未接続")
            home("リビング、未接続", "全て切断")
            assertEquals(TagCommand.Connect(living), WidgetTargets(context).target(deskWidget))
            shell("cmd uimode night no")
            SystemClock.sleep(1_500)

            // A target that can no longer be operated is dimmed and opens SoundTag instead of switching.
            settings.saved = setOf(desk)
            appThread { SpeakerWidget.update(context) }
            home("リビング、使用できません", "全て切断")
            snap("widget-unavailable-light")
            val calls = bluetooth.calls.size
            tap("リビング、使用できません")
            eventually(10_000) { "dev.miyabisun.soundtag/.MainActivity" in front() }
            assertEquals(calls, bluetooth.calls.size)

            settings.permission = false
            context.getSharedPreferences("MainActivity", 0).edit().putBoolean("permissionAsked", true).commit()
            appThread { SpeakerWidget.update(context) }
            home("リビング、使用できません", "全て切断、使用できません")
            snap("widget-no-permission-light")

            // A widget without a target (as left by v0.1.6) asks for one when tapped.
            WidgetTargets(context).remove(intArrayOf(deskWidget))
            appThread { SpeakerWidget.update(context) }
            home("対象を選択")
            snap("widget-unset-light")
            tap("対象を選択")
            eventually(10_000) { "WidgetConfigActivity" in front() }
            waitFor("SoundTagを開く")
            assertFalse(screen().contains("全て切断"))
            snap("widget-config-no-choice-light")
            shell("input keyevent KEYCODE_BACK")
        } finally {
            shell("cmd uimode night no")
            shell("input keyevent KEYCODE_HOME")
            SpeakerWidget.accessFactory = null
            MainActivity.accessFactory = null
            NfcService.bluetoothFactory = null
        }
    }

    private fun ids() = AppWidgetManager.getInstance(context).getAppWidgetIds(widget).toSet()

    /** Adds SoundTag from the launcher's own widget list, which starts the OS configuration screen. */
    private fun addFromLauncher() {
        shell("input swipe 250 1300 250 1300 1500")
        tapText(Regex("(?i)^(widgets|ウィジェット)$"))
        val search = Regex("(?i)^(search|検索)$")
        eventually(10_000) { nodes().any { search.matches(it.description) } }
        val field = nodes().first { search.matches(it.description) }
        shell("input tap ${field.x} ${field.y}")
        SystemClock.sleep(1_000)
        shell("input text SoundTag")
        SystemClock.sleep(1_500)
        shell("input keyevent KEYCODE_BACK")
        eventually(10_000) { nodes().any { it.text == "SoundTag" && it.y > field.bounds.bottom } }
        val app = nodes().first { it.text == "SoundTag" && it.y > field.bounds.bottom }
        shell("input tap ${app.x} ${app.y}")
        // The preview carries the widget's label; tapping it reveals the launcher's add button.
        eventually(10_000) { nodes().any { it.description == "SoundTag" && it.y > app.bounds.bottom } }
        nodes().first { it.description == "SoundTag" && it.y > app.bounds.bottom }.let { shell("input tap ${it.x} ${it.y}") }
        val add = Regex("(?i)^add SoundTag widget$|^SoundTag.*追加$")
        eventually(10_000) { nodes().any { add.matches(it.description) } }
        nodes().first { add.matches(it.description) }.let { shell("input tap ${it.x} ${it.y}") }
        eventually(10_000) { "WidgetConfigActivity" in front() }
    }

    private fun acceptPin() {
        val button = Regex("(?i)^(add to home screen|add|追加|ホーム画面に追加)$")
        eventually(10_000) { nodes().any { button.matches(it.text) } }
        val node = nodes().last { button.matches(it.text) }
        shell("input tap ${node.x} ${node.y}")
    }

    /** Shows the home screen page holding every label; pinned widgets may land on a later page. */
    private fun home(vararg labels: String) {
        fun shown() = nodes().let { all -> labels.all { label -> all.any { it.description == label } } }
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("NexusLauncherActivity" !in front()) shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(1_000)
            if (shown()) return
            for (page in 0 until 3) {
                shell("input swipe 900 1200 150 1200 300")
                SystemClock.sleep(1_000)
                if (shown()) return
            }
            shell("input keyevent KEYCODE_HOME")
        }
        fail("Widgets not on the home screen: ${labels.toList()}\n${screen()}")
    }

    private class Node(val text: String, val description: String, val x: Int, val y: Int,
                       val bounds: Rect, val info: AccessibilityNodeInfo)

    private fun nodes(): List<Node> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val found = mutableListOf<Node>()
        fun walk(node: AccessibilityNodeInfo?) {
            // Other home screen pages stay in the tree; only what is on screen can be tapped.
            if (node == null || !node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            found += Node(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
                bounds.centerX(), bounds.centerY(), bounds, node)
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
        waitFor(label)
        val node = nodes().first { it.description == label || it.text == label }
        shell("input tap ${node.x} ${node.y}")
    }
    private fun tapText(pattern: Regex) {
        eventually(10_000) { nodes().any { pattern.matches(it.text) } }
        val node = nodes().first { pattern.matches(it.text) }
        shell("input tap ${node.x} ${node.y}")
    }
    private fun longPress(label: String) {
        val node = nodes().first { it.description == label }
        shell("input swipe ${node.x} ${node.y} ${node.x} ${node.y} 1500")
    }
    private fun front() = shell("dumpsys activity activities").lines().firstOrNull { "topResumedActivity" in it }.orEmpty()
    private fun assertLauncherInFront() {
        SystemClock.sleep(500)
        assertFalse("Widget tap opened SoundTag", front().contains("dev.miyabisun.soundtag"))
    }
    private fun snap(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(500)
        shell("screencap -p /data/local/tmp/$name.png")
    }
    private fun eventually(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200)
        assertTrue("Timed out waiting for the launcher:\n${screen()}", condition())
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
    override fun speakers() = if (permission) names.map { (address, name) -> Speaker(address, name, address in bluetooth.connected) }
        else emptyList()
    override fun associations() = names.keys
    override fun allowed() = saved
    override fun saveAllowed(addresses: Set<String>) { saved = addresses }
    override fun associate(address: String, complete: (Boolean) -> Unit) = complete(true)
    override fun disassociate(address: String) {}
}
