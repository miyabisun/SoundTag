package dev.miyabisun.soundtag

data class Speaker(val address: String, val name: String, val connected: Boolean)
data class SpeakerSetting(val speaker: Speaker, val allowed: Boolean)
data class SettingsSnapshot(
    val permission: Boolean,
    val bluetoothEnabled: Boolean,
    val speakers: List<SpeakerSetting>,
)

interface SettingsAccess {
    fun hasPermission(): Boolean
    fun bluetoothEnabled(): Boolean
    fun speakers(): List<Speaker>
    fun associations(): Set<String>
    fun allowed(): Set<String>
    fun saveAllowed(addresses: Set<String>)
    fun associate(address: String, complete: (Boolean) -> Unit)
    fun disassociate(address: String)
}

enum class SettingsResult { SAVED, PERMISSION_REQUIRED, BLUETOOTH_OFF, UNKNOWN_DEVICE, ASSOCIATION_FAILED }

class SettingsController(private val access: SettingsAccess) {
    private var revision = 0

    fun snapshot(): SettingsSnapshot {
        if (!access.hasPermission()) return SettingsSnapshot(false, false, emptyList())
        val allowed = access.allowed().intersect(access.associations())
        return SettingsSnapshot(true, access.bluetoothEnabled(),
            access.speakers().map { SpeakerSetting(it, it.address in allowed) })
    }

    fun setAllowed(address: String, enabled: Boolean, complete: (SettingsResult) -> Unit) {
        val request = ++revision
        if (!enabled) {
            access.saveAllowed(access.allowed() - address)
            access.disassociate(address)
            complete(SettingsResult.SAVED)
            return
        }
        val state = snapshot()
        when {
            !state.permission -> complete(SettingsResult.PERMISSION_REQUIRED)
            !state.bluetoothEnabled -> complete(SettingsResult.BLUETOOTH_OFF)
            state.speakers.none { it.speaker.address == address } -> complete(SettingsResult.UNKNOWN_DEVICE)
            address in access.associations() -> {
                access.saveAllowed(access.allowed() + address)
                complete(SettingsResult.SAVED)
            }
            else -> access.associate(address) { accepted ->
                if (request != revision) return@associate
                val current = snapshot()
                if (accepted && current.permission && address in access.associations() &&
                    current.speakers.any { it.speaker.address == address }) {
                    access.saveAllowed(access.allowed() + address)
                    complete(SettingsResult.SAVED)
                } else {
                    complete(SettingsResult.ASSOCIATION_FAILED)
                }
            }
        }
    }

    fun code(command: TagCommand): String? {
        val allowed = snapshot().speakers.filter { it.allowed }.map { it.speaker.address }
        if (allowed.isEmpty() || (command.target != null && command.target !in allowed)) return null
        return command.uri().takeIf { TagCommand.parse(it) == command }
    }

    fun close() { revision++ }
}
