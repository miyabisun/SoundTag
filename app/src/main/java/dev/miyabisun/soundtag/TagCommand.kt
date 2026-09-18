package dev.miyabisun.soundtag

sealed interface TagCommand {
    data class Connect(val address: String) : TagCommand
    data class Disconnect(val address: String) : TagCommand
    data object Phone : TagCommand

    val target: String?
        get() = when (this) {
            is Connect -> address
            is Disconnect -> address
            Phone -> null
        }

    fun uri(): String = when (this) {
        is Connect -> "soundtag://connect/$address"
        is Disconnect -> "soundtag://disconnect/$address"
        Phone -> "soundtag://phone"
    }

    companion object {
        private val deviceUri = Regex("soundtag://(connect|disconnect)/([0-9A-F]{2}(?::[0-9A-F]{2}){5})")

        fun parse(value: String): TagCommand? {
            if (value == "soundtag://phone") return Phone
            val match = deviceUri.matchEntire(value) ?: return null
            val address = match.groupValues[2]
            return if (match.groupValues[1] == "connect") Connect(address) else Disconnect(address)
        }
    }
}
