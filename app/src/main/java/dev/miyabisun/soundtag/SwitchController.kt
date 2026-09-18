package dev.miyabisun.soundtag

data class BluetoothSnapshot(
    val permission: Boolean = false,
    val enabled: Boolean = false,
    val profilesReady: Boolean = false,
    val devices: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
    val associated: Set<String> = emptySet(),
    val audioConnected: Set<String> = emptySet(),
    val linked: Set<String> = emptySet(),
    val transitioning: Set<String> = emptySet(),
    val confirmedDisconnections: Set<String> = emptySet(),
) {
    val authorized get() = devices intersect allowed intersect associated
    val active get() = audioConnected + linked + transitioning
}

interface BluetoothAccess : AutoCloseable {
    fun start() {}
    override fun close() {}
    fun snapshot(): BluetoothSnapshot
    fun connect(address: String): Boolean
    fun disconnect(address: String): Boolean
}

enum class SwitchPhase { IDLE, WORKING, SUCCEEDED, FAILED }
enum class SwitchFailure { PERMISSION, BLUETOOTH_OFF, NOT_ALLOWED, API_REJECTED, TIMEOUT, CLOSED }
data class SwitchStatus(
    val phase: SwitchPhase = SwitchPhase.IDLE,
    val command: TagCommand? = null,
    val failure: SwitchFailure? = null,
)

/** One native operation at a time; notifications always re-read the current state. */
class SwitchController(private val access: BluetoothAccess) {
    companion object { const val TIMEOUT_MS = 30_000L }
    var status = SwitchStatus()
        private set
    private var flight: TagCommand? = null
    private var deadline = 0L
    private var closed = false
    private var cancelling: String? = null
    private var cancellationSawLink = false
    val hasPendingWork get() = status.phase == SwitchPhase.WORKING || cancelling != null

    fun submit(command: TagCommand, nowMs: Long): SwitchFailure? {
        if (closed) {
            status = SwitchStatus(SwitchPhase.FAILED, command, SwitchFailure.CLOSED)
            return SwitchFailure.CLOSED
        }
        val rejected = try { invalid(command, access.snapshot()) }
        catch (_: SecurityException) { SwitchFailure.PERMISSION }
        catch (_: IllegalStateException) { SwitchFailure.API_REJECTED }
        if (rejected != null) {
            // An untrusted new tag must not interrupt an already authorized operation.
            if (status.phase != SwitchPhase.WORKING) status = SwitchStatus(SwitchPhase.FAILED, command, rejected)
            return rejected
        }
        if (status.phase != SwitchPhase.WORKING) deadline = nowMs + TIMEOUT_MS
        status = SwitchStatus(SwitchPhase.WORKING, command)
        changed(nowMs)
        return status.failure
    }

    fun changed(nowMs: Long) {
        if (closed || (status.phase != SwitchPhase.WORKING && cancelling == null)) return
        try {
            val state = access.snapshot()
            settleCancellation(state)
            if (status.phase == SwitchPhase.WORKING) advance(state, nowMs)
        } catch (_: SecurityException) {
            fail(SwitchFailure.PERMISSION)
        } catch (_: IllegalStateException) {
            fail(SwitchFailure.API_REJECTED)
        }
    }

    private fun advance(state: BluetoothSnapshot, nowMs: Long) {
        val command = checkNotNull(status.command)
        val invalid = invalid(command, state)
        if (invalid != null) {
            cancelFlight(state)
            fail(invalid)
            return
        }
        if (nowMs >= deadline) {
            cancelFlight(state)
            fail(SwitchFailure.TIMEOUT)
            return
        }
        if (!state.profilesReady || cancelling != null) return
        when (val pending = flight) {
            is TagCommand.Connect -> if (pending.address !in state.audioConnected) return
            is TagCommand.Disconnect -> if (pending.address in state.active) return
            else -> Unit
        }
        flight = null
        val others = (state.active intersect state.authorized).sorted()
        val next = when (command) {
            is TagCommand.Connect -> others.firstOrNull { it != command.address }
                ?.let(TagCommand::Disconnect)
                ?: command.takeUnless { it.address in state.audioConnected }
            is TagCommand.Disconnect -> command.takeIf { it.address in state.active }
            TagCommand.Phone -> others.firstOrNull()?.let(TagCommand::Disconnect)
        }
        if (next == null) {
            status = status.copy(phase = SwitchPhase.SUCCEEDED)
            return
        }
        flight = next
        val accepted = when (next) {
            is TagCommand.Connect -> access.connect(next.address)
            is TagCommand.Disconnect -> access.disconnect(next.address)
            TagCommand.Phone -> false
        }
        if (!accepted) fail(SwitchFailure.API_REJECTED)
    }

    private fun fail(reason: SwitchFailure) {
        flight = null
        status = status.copy(phase = SwitchPhase.FAILED, failure = reason)
    }

    private fun invalid(command: TagCommand, state: BluetoothSnapshot): SwitchFailure? = when {
        !state.permission -> SwitchFailure.PERMISSION
        !state.enabled -> SwitchFailure.BLUETOOTH_OFF
        command.target != null && command.target !in state.authorized -> SwitchFailure.NOT_ALLOWED
        else -> null
    }

    private fun cancelFlight(state: BluetoothSnapshot) {
        val pending = flight ?: return
        val address = pending.target ?: return
        cancelling = address
        cancellationSawLink = address in state.active
        if (pending is TagCommand.Connect && state.permission && state.enabled && address in state.authorized) {
            try { access.disconnect(address) } catch (_: RuntimeException) { /* Already failing. */ }
        }
    }

    private fun settleCancellation(state: BluetoothSnapshot) {
        val address = cancelling ?: return
        if (!state.permission || !state.profilesReady) return
        if (address !in state.active && (cancellationSawLink || address in state.confirmedDisconnections)) {
            cancelling = null
        } else if (address in state.active && !cancellationSawLink && address in state.authorized) {
            // A native connection completed after the initial cancellation request.
            if (access.disconnect(address)) cancellationSawLink = true
        }
    }

    fun cancel() {
        if (closed || status.phase != SwitchPhase.WORKING) return
        try { cancelFlight(access.snapshot()) } catch (_: RuntimeException) { /* Permission may be revoked. */ }
        fail(SwitchFailure.CLOSED)
    }

    fun close() {
        if (closed) return
        cancel()
        closed = true
    }
}
