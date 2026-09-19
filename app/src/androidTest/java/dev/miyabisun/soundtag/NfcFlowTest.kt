package dev.miyabisun.soundtag

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class NfcFlowTest {
    private val address = "00:11:22:33:44:AA"
    private val b = "00:11:22:33:44:BB"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    @Test fun connectionAndDisconnectNotifyButRepeatedTagsNeverCoverTheOtherApp() {
        val fake = NfcFake(address)
        withFake(fake) {
            shell("am start -W -a android.settings.SETTINGS")
            deliverTag("soundtag://connect/$address")
            eventually { fake.calls.size == 1 }
            assertNull(resultNotification()) // API acceptance is not connection success.
            assertSettingsVisible()
            shell("screencap -p /data/local/tmp/nfc-background-working.png")
            appThread { fake.connected = setOf(address); fake.notify() }
            eventually { resultText() == "接続しました" && serviceStopped() }
            assertTrue(fake.closed)
            assertSettingsVisible()
            shell("screencap -p /data/local/tmp/nfc-background-complete.png")
            shell("input swipe 500 0 500 1500 500")
            SystemClock.sleep(1_000)
            shell("screencap -p /data/local/tmp/nfc-result-notification.png")
            shell("cmd statusbar collapse")
            clearNotifications()
            deliverTag("soundtag://connect/$address")
            eventually { serviceStopped() && NfcService.pendingSession == null }
            assertEquals(listOf("connect:$address"), fake.calls)
            assertNull(resultNotification())
            deliverTag("soundtag://phone")
            eventually { fake.calls.size == 2 }
            appThread { fake.connected = emptySet(); fake.notify() }
            eventually { resultText() == "全て切断しました" && serviceStopped() }
            assertSettingsVisible()
            clearNotifications()
            deliverTag("soundtag://phone")
            eventually { serviceStopped() }
            assertNull(resultNotification())
            assertEquals(2, fake.calls.size)
        }
    }

    @Test fun invalidDeniedAndUnchangedTimeoutAreSilentAndCancellationIsRetained() {
        val fake = NfcFake(address)
        withFake(fake) {
            deliverTag("soundtag://connect/invalid")
            deliverTag("soundtag://connect/$address", Intent.ACTION_VIEW)
            assertTrue(fake.calls.isEmpty())
            fake.permission = false
            deliverTag("soundtag://connect/$address")
            eventually { serviceStopped() }
            assertTrue(fake.calls.isEmpty())
            assertNull(resultNotification())
            fake.permission = true
            deliverTag("soundtag://connect/$address")
            eventually { fake.calls.size == 1 }
            appThread { NfcService.pendingSession!!.tick(SystemClock.elapsedRealtime() + SwitchController.TIMEOUT_MS) }
            eventually { serviceStopped() }
            assertEquals(listOf("connect:$address", "disconnect:$address"), fake.calls)
            assertNull(resultNotification())
            deliverTag("soundtag://connect/$b")
            eventually { NfcService.pendingSession?.controller?.status?.command == TagCommand.Connect(b) }
            assertEquals(2, fake.calls.size)
            appThread { fake.connected = setOf(address); fake.notify() }
            assertEquals("disconnect:$address", fake.calls.last())
            appThread { fake.connected = emptySet(); fake.confirmed = setOf(address); fake.notify() }
            assertEquals("connect:$b", fake.calls.last())
            appThread { fake.connected = setOf(b); fake.notify() }
            eventually { resultText() == "接続しました" && serviceStopped() }
            assertSettingsOrNoNfcVisible()
        }
    }

    @Test fun partialChangeReportsFailure() {
        val fake = NfcFake(address).apply { connected = setOf(address) }
        withFake(fake) {
            deliverTag("soundtag://connect/$b")
            eventually { fake.calls.size == 1 }
            appThread { fake.accept = false; fake.connected = emptySet(); fake.notify() }
            eventually { resultText()?.contains("操作を開始できませんでした") == true && serviceStopped() }
            assertTrue(resultText()!!.startsWith("接続状態が変わりました"))
        }
    }

    @Test fun deniedNotificationPermissionStillConnectsWithoutOpeningPermissionUi() {
        // Run this case separately after pm revoke; revoking from inside instrumentation kills its process.
        org.junit.Assume.assumeFalse(notifications.areNotificationsEnabled())
        val fake = NfcFake(address)
        withFake(fake, grantNotifications = false) {
            shell("am start -W -a android.settings.SETTINGS")
            deliverTag("soundtag://connect/$address")
            eventually { fake.calls.size == 1 }
            appThread { fake.connected = setOf(address); fake.notify() }
            eventually { serviceStopped() }
            assertNull(resultNotification())
            assertSettingsVisible()
        }
    }

    private fun withFake(fake: NfcFake, grantNotifications: Boolean = true, block: () -> Unit) {
        if (grantNotifications) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        clearNotifications()
        NfcService.bluetoothFactory = { _, changed -> fake.apply { notify = changed; closed = false } }
        try { block() } finally {
            appThread {
                NfcService.pendingSession?.controller?.cancel()
                fake.connected = emptySet()
                fake.confirmed = setOf(address, b)
                fake.notify()
                context.stopService(Intent(context, NfcService::class.java))
            }
            instrumentation.waitForIdleSync()
            eventually { serviceStopped() && NfcService.pendingSession == null }
            NfcService.bluetoothFactory = null
            clearNotifications()
        }
    }

    private fun clearNotifications() {
        notifications.cancelAll()
        eventually { resultNotification() == null }
    }
    private fun resultText() = resultNotification()?.extras?.getString(Notification.EXTRA_TITLE)
    private fun serviceStopped(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(20).none { it.service.className == NfcService::class.java.name }
    private fun assertSettingsVisible() {
        val top = shell("dumpsys activity activities").lineSequence().filter { "topResumedActivity=" in it }.joinToString()
        assertTrue(top, "com.android.settings" in top)
        assertFalse(top, "soundtag" in top)
    }
    private fun assertSettingsOrNoNfcVisible() {
        val windows = shell("dumpsys window windows")
        assertFalse(windows.lineSequence().any { "mCurrentFocus" in it && ("NfcActivity" in it || "permissioncontroller" in it) })
    }
}

internal fun appThread(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
internal fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
    .executeShellCommand(command).use { FileInputStream(it.fileDescriptor).readBytes().toString(Charsets.UTF_8) }
internal fun eventually(condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + 5_000
    while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
    assertTrue("Timed out waiting for Android operation", condition())
}
internal fun deliverTag(code: String, action: String = NfcAdapter.ACTION_NDEF_DISCOVERED) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    appThread {
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, NfcActivity::class.java)
            .setAction(action).setData(Uri.parse(code)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    instrumentation.waitForIdleSync()
    // Allow asynchronous Activity -> Service dispatch before testing a no-op.
    SystemClock.sleep(200)
}
internal fun resultNotification(): Notification? = InstrumentationRegistry.getInstrumentation().targetContext
    .getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == 2 }?.notification

private class NfcFake(private val address: String) : BluetoothAccess {
    var permission = true
    var connected = emptySet<String>()
    var confirmed = emptySet<String>()
    var notify: () -> Unit = {}
    var closed = false
    var accept = true
    val calls = mutableListOf<String>()
    override fun snapshot() = BluetoothSnapshot(permission, true, true,
        setOf(address, "00:11:22:33:44:BB"), setOf(address, "00:11:22:33:44:BB"),
        setOf(address, "00:11:22:33:44:BB"), connected, connected, confirmedDisconnections = confirmed)
    override fun connect(address: String): Boolean { calls += "connect:$address"; confirmed -= address; return accept }
    override fun disconnect(address: String): Boolean { calls += "disconnect:$address"; confirmed -= address; return accept }
    override fun close() { closed = true }
}
