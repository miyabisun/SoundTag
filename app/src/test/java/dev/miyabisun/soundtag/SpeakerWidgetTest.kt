package dev.miyabisun.soundtag

import org.junit.Assert.*
import org.junit.Test

class SpeakerWidgetTest {
    private val a = Speaker("00:11:22:33:44:AA", "デスク", true)
    private val b = Speaker("00:11:22:33:44:BB", "リビング", false)
    private val c = Speaker("00:11:22:33:44:CC", "時計", false)
    private val state = SettingsSnapshot(true, true,
        listOf(SpeakerSetting(b, true), SpeakerSetting(a, true), SpeakerSetting(c, false)))
    private fun face(target: TagCommand?, saved: String? = null, snapshot: SettingsSnapshot = state) =
        widgetFace(target, saved, snapshot, "全て切断", "対象を選択")

    @Test fun choicesAreAllowedSpeakersInOrderThenDisconnectAllWithTagCommands() {
        val items = widgetItems(state)
        assertEquals(listOf(WidgetItem.Device(b), WidgetItem.Device(a), WidgetItem.DisconnectAll), items)
        assertEquals(listOf(TagCommand.Connect(b.address), TagCommand.Connect(a.address), TagCommand.Phone),
            items.map { it.command })
    }

    @Test fun missingPermissionOrNoAllowedSpeakerOffersNoChoice() {
        assertTrue(widgetItems(SettingsSnapshot(false, false, emptyList())).isEmpty())
        assertTrue(widgetItems(SettingsSnapshot(true, true, listOf(SpeakerSetting(a, false)))).isEmpty())
        assertTrue(widgetItems(SettingsSnapshot(true, false, emptyList())).isEmpty())
    }

    @Test fun allowedSpeakerShowsItsConnectorAndSendsTheConnectTag() {
        assertEquals(WidgetFace(R.drawable.ic_link_on, "デスク", "デスク、接続中", true,
            WidgetTap.Send(TagCommand.Connect(a.address))), face(TagCommand.Connect(a.address)))
        assertEquals(WidgetFace(R.drawable.ic_link_off, "リビング", "リビング、未接続", true,
            WidgetTap.Send(TagCommand.Connect(b.address))), face(TagCommand.Connect(b.address), "古い名前"))
    }

    @Test fun disconnectAllSendsThePhoneTagWhileAnySpeakerIsAllowed() {
        assertEquals(WidgetFace(R.drawable.ic_phone, "全て切断", "全て切断", true, WidgetTap.Send(TagCommand.Phone)),
            face(TagCommand.Phone))
    }

    @Test fun unusableTargetsAreDimmedAndOpenTheApp() {
        assertEquals(WidgetFace(R.drawable.ic_link_off, "時計", "時計、使用できません", false, WidgetTap.OpenApp),
            face(TagCommand.Connect(c.address)))
        val unpaired = face(TagCommand.Connect("00:11:22:33:44:DD"), "寝室")
        assertEquals(WidgetFace(R.drawable.ic_link_off, "寝室", "寝室、使用できません", false, WidgetTap.OpenApp), unpaired)
        val noPermission = SettingsSnapshot(false, false, emptyList())
        assertEquals(WidgetFace(R.drawable.ic_link_off, "デスク", "デスク、使用できません", false, WidgetTap.OpenApp),
            face(TagCommand.Connect(a.address), "デスク", noPermission))
        assertEquals(WidgetFace(R.drawable.ic_phone, "全て切断", "全て切断、使用できません", false, WidgetTap.OpenApp),
            face(TagCommand.Phone, snapshot = noPermission))
        assertEquals(WidgetTap.OpenApp,
            face(TagCommand.Phone, snapshot = SettingsSnapshot(true, true, listOf(SpeakerSetting(a, false)))).tap)
    }

    @Test fun unsetOrUnofferedTargetsAskForAChoice() {
        val choose = WidgetFace(R.drawable.ic_soundtag, "対象を選択", "対象を選択", true, WidgetTap.Choose)
        assertEquals(choose, face(null))
        assertEquals(choose, face(TagCommand.Disconnect(a.address)))
    }
}
