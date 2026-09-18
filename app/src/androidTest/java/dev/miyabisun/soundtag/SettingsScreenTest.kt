package dev.miyabisun.soundtag

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @Test fun mockedSpeakerCanBeAllowedThenCopiedAndDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = "00:11:22:33:44:AA"
        val fake = object : SettingsAccess {
            var saved = emptySet<String>()
            var associated = emptySet<String>()
            var copied: String? = null
            override fun hasPermission() = true
            override fun bluetoothEnabled() = true
            override fun speakers() = listOf(Speaker(address, "デスクのスピーカー", true))
            override fun associations() = associated
            override fun allowed() = saved
            override fun saveAllowed(addresses: Set<String>) { saved = addresses }
            override fun associate(address: String, complete: (Boolean) -> Unit) {
                associated = setOf(address)
                complete(true)
            }
            override fun disassociate(address: String) { associated = emptySet() }
            override fun copy(text: String) { copied = text }
        }
        MainActivity.accessFactory = { fake }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Switch>().single().performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(setOf(address), fake.saved)
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/soundtag-settings.png")
                .use { FileInputStream(it.fileDescriptor).readBytes() }
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .first { it.contentDescription == "デスクのスピーカーのタグ用コード" }.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .first { it.text.toString() == "コピー" }.performClick()
            }
            assertEquals("soundtag://connect/$address", fake.copied)
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/soundtag-code.png")
                .use { FileInputStream(it.fileDescriptor).readBytes() }
            instrumentation.runOnMainSync {
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.toString() == "コピーしました" })
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .first { it.text.toString() == "スピーカー一覧" }.performClick()
                descendants(activity.window.decorView).filterIsInstance<Switch>().single().performClick()
            }
            assertTrue(fake.saved.isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            MainActivity.accessFactory = null
        }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
