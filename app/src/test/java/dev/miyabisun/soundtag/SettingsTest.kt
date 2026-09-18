package dev.miyabisun.soundtag

import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    private val a = "00:11:22:33:44:AA"
    private val b = "00:11:22:33:44:BB"

    @Test fun tagCommandsRoundTripAndRejectUntrustedInput() {
        for (command in listOf(TagCommand.Connect(a), TagCommand.Disconnect(a), TagCommand.Phone)) {
            assertEquals(command, TagCommand.parse(command.uri()))
        }
        assertEquals("soundtag://connect/$a", TagCommand.Connect(a).uri())
        for (bad in listOf("", "https://connect/$a", "soundtag://toggle/$a",
            "soundtag://connect", "soundtag://connect/invalid", "soundtag://connect/$a/extra",
            "soundtag://phone/$a", "soundtag://phone?device=$a", "soundtag://connect/$a#x",
            "soundtag://user@connect/$a", "soundtag://connect:80/$a", "soundtag://connect/%30$a")) {
            assertNull(bad, TagCommand.parse(bad))
        }
    }

    @Test fun pairingAloneDoesNotAuthorizeAndPermissionDenialDoesNotReadDevices() {
        val fake = FakeSettings(a, b)
        val settings = SettingsController(fake)
        assertFalse(settings.snapshot().speakers.first().allowed)
        assertNull(settings.code(TagCommand.Connect(a)))
        fake.permission = false
        assertTrue(settings.snapshot().speakers.isEmpty())
        assertNull(settings.code(TagCommand.Phone))
        assertEquals(SettingsResult.PERMISSION_REQUIRED, settings.setAllowedNow(a, true))
        assertEquals(0, fake.permissionlessReads)
    }

    @Test fun associationConsentPersistsThenRevocationAndOffBlockOldTags() {
        val fake = FakeSettings(a, b)
        val settings = SettingsController(fake)
        var result: SettingsResult? = null
        settings.setAllowed(a, true) { result = it }
        assertNull(result)
        assertTrue(fake.saved.isEmpty())
        fake.completeAssociation(true)
        assertEquals(SettingsResult.SAVED, result)
        assertEquals(setOf(a), fake.saved)
        assertEquals("soundtag://connect/$a", SettingsController(fake).code(TagCommand.Connect(a)))
        assertNull(settings.code(TagCommand.Connect(b)))
        fake.associated.clear()
        assertFalse(settings.snapshot().speakers.first().allowed)
        assertNull(settings.code(TagCommand.Connect(a)))
        fake.associated.add(a)
        settings.setAllowed(a, false) { result = it }
        assertEquals(SettingsResult.SAVED, result)
        assertFalse(fake.saved.contains(a))
        assertNull(settings.code(TagCommand.Disconnect(a)))
    }

    @Test fun rejectedOrLateConsentNeverEnablesADevice() {
        val fake = FakeSettings(a, b)
        val settings = SettingsController(fake)
        var result: SettingsResult? = null
        settings.setAllowed(a, true) { result = it }
        fake.completeAssociation(false)
        assertEquals(SettingsResult.ASSOCIATION_FAILED, result)
        assertTrue(fake.saved.isEmpty())
        settings.setAllowed(a, true) { result = it }
        settings.setAllowed(a, false) {}
        fake.completeAssociation(true)
        assertTrue(fake.saved.isEmpty())
        assertNull(settings.code(TagCommand.Connect(a)))
        fake.enabled = false
        assertEquals(SettingsResult.BLUETOOTH_OFF, settings.setAllowedNow(b, true))
        fake.enabled = true
        assertEquals(SettingsResult.UNKNOWN_DEVICE, settings.setAllowedNow("00:00:00:00:00:00", true))
    }

    @Test fun copyOnlyUsesCurrentAuthorizationAndPermissionRevocationCancelsConsent() {
        val fake = FakeSettings(a, b)
        val settings = SettingsController(fake)
        settings.setAllowed(a, true) {}
        fake.permission = false
        fake.completeAssociation(true)
        assertTrue(fake.saved.isEmpty())
        fake.permission = true
        settings.setAllowed(a, true) {}
        assertTrue(settings.copy(TagCommand.Disconnect(a)))
        assertEquals("soundtag://disconnect/$a", fake.copied)
        settings.setAllowed(a, false) {}
        assertFalse(settings.copy(TagCommand.Disconnect(a)))
    }

    private fun SettingsController.setAllowedNow(id: String, enabled: Boolean): SettingsResult? {
        var result: SettingsResult? = null
        setAllowed(id, enabled) { result = it }
        return result
    }
}

class FakeSettings(vararg addresses: String) : SettingsAccess {
    var permission = true
    var enabled = true
    var permissionlessReads = 0
    val devices = addresses.mapIndexed { i, address -> Speaker(address, "スピーカー ${i + 1}", i == 0) }
    val associated = mutableSetOf<String>()
    var saved = setOf<String>()
    var copied: String? = null
    private var pending: Pair<String, (Boolean) -> Unit>? = null
    override fun hasPermission() = permission
    override fun bluetoothEnabled() = enabled
    override fun speakers(): List<Speaker> {
        if (!permission) permissionlessReads++
        return devices
    }
    override fun associations() = associated.toSet()
    override fun allowed() = saved
    override fun saveAllowed(addresses: Set<String>) { saved = addresses.toSet() }
    override fun associate(address: String, complete: (Boolean) -> Unit) { pending = address to complete }
    override fun disassociate(address: String) { associated.remove(address) }
    override fun copy(text: String) { copied = text }
    fun completeAssociation(accepted: Boolean) {
        val (address, complete) = checkNotNull(pending)
        pending = null
        if (accepted) associated.add(address)
        complete(accepted)
    }
}
