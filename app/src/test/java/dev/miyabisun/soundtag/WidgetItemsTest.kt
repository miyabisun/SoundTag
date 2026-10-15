package dev.miyabisun.soundtag

import org.junit.Assert.*
import org.junit.Test

class WidgetItemsTest {
    private val a = Speaker("00:11:22:33:44:AA", "デスク", true)
    private val b = Speaker("00:11:22:33:44:BB", "リビング", false)
    private val c = Speaker("00:11:22:33:44:CC", "時計", false)

    @Test fun allowedSpeakersKeepOrderAndStateThenDisconnectAllWithTagCommands() {
        val items = widgetItems(SettingsSnapshot(true, true,
            listOf(SpeakerSetting(b, true), SpeakerSetting(a, true), SpeakerSetting(c, false))))
        assertEquals(listOf(WidgetItem.Device(b), WidgetItem.Device(a), WidgetItem.DisconnectAll), items)
        assertEquals(listOf(TagCommand.Connect(b.address), TagCommand.Connect(a.address), TagCommand.Phone),
            items.map { it.command })
    }

    @Test fun missingPermissionOrNoAllowedSpeakerLeavesOnlyTheAppEntry() {
        assertTrue(widgetItems(SettingsSnapshot(false, false, emptyList())).isEmpty())
        assertTrue(widgetItems(SettingsSnapshot(true, true, listOf(SpeakerSetting(a, false)))).isEmpty())
        assertTrue(widgetItems(SettingsSnapshot(true, false, emptyList())).isEmpty())
    }
}
