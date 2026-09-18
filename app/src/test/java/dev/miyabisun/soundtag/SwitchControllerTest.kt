package dev.miyabisun.soundtag

import org.junit.Assert.*
import org.junit.Test

class SwitchControllerTest {
    private val a = "00:11:22:33:44:AA"
    private val b = "00:11:22:33:44:BB"
    private val watch = "00:11:22:33:44:CC"

    @Test fun sameTagIsIdempotentAndAcceptanceIsNotSuccess() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        assertEquals(SwitchPhase.WORKING, control.status.phase)
        control.submit(TagCommand.Connect(a), 10)
        assertEquals(listOf("connect:$a"), fake.calls)
        fake.connected(a)
        control.changed(20)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        control.submit(TagCommand.Connect(a), 30)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        assertEquals(listOf("connect:$a"), fake.calls)
    }

    @Test fun switchesBothWaysWithoutTouchingTheWatch() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(b), 0)
        assertEquals(listOf("disconnect:$a"), fake.calls)
        fake.disconnected(a)
        control.changed(10)
        assertEquals(listOf("disconnect:$a", "connect:$b"), fake.calls)
        fake.connected(b)
        control.changed(20)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        assertTrue(watch in fake.state.linked)
        control.submit(TagCommand.Connect(a), 30)
        fake.disconnected(b)
        control.changed(40)
        fake.connected(a)
        control.changed(50)
        assertEquals(listOf("disconnect:$a", "connect:$b", "disconnect:$b", "connect:$a"), fake.calls)
        assertEquals(setOf(a, watch), fake.state.audioConnected)
    }

    @Test fun alreadyConnectedTargetIsKeptWhileOnlyOtherAllowedDevicesAreDisconnected() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        assertEquals(listOf("disconnect:$b"), fake.calls)
        fake.state = fake.state.copy(audioConnected = setOf(a, watch))
        control.changed(10)
        assertEquals(SwitchPhase.WORKING, control.status.phase)
        assertEquals(listOf("disconnect:$b"), fake.calls)
        fake.disconnected(b)
        control.changed(20)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        assertEquals(setOf(a, watch), fake.state.audioConnected)
        assertEquals(listOf("disconnect:$b"), fake.calls)
        control.submit(TagCommand.Connect(watch), 30)
        assertEquals(SwitchFailure.NOT_ALLOWED, control.status.failure)
        assertEquals(listOf("disconnect:$b"), fake.calls)
    }

    @Test fun disconnectAndPhoneAreNoOpsWhenAlreadyDisconnected() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Disconnect(a), 0)
        fake.disconnected(a)
        control.changed(10)
        control.submit(TagCommand.Disconnect(a), 20)
        assertEquals(listOf("disconnect:$a"), fake.calls)
        control.submit(TagCommand.Phone, 30)
        assertEquals(listOf("disconnect:$a", "disconnect:$b"), fake.calls)
        fake.disconnected(b)
        control.changed(40)
        control.submit(TagCommand.Phone, 50)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        assertEquals(setOf(watch), fake.state.linked)
        assertEquals(2, fake.calls.size)
    }

    @Test fun competingRequestsFinishOnlyTheLatestTarget() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        control.submit(TagCommand.Connect(b), 10)
        assertEquals(listOf("connect:$a"), fake.calls)
        fake.connected(a)
        control.changed(20)
        assertEquals(listOf("connect:$a", "disconnect:$a"), fake.calls)
        fake.disconnected(a)
        control.changed(30)
        assertEquals(listOf("connect:$a", "disconnect:$a", "connect:$b"), fake.calls)
        // A delayed duplicate event reads the current snapshot; it cannot revive the old command.
        control.changed(40)
        assertEquals(SwitchPhase.WORKING, control.status.phase)
        fake.connected(b)
        control.changed(50)
        assertEquals(TagCommand.Connect(b), control.status.command)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
        control.changed(60)
        assertEquals(3, fake.calls.size)
        assertEquals(setOf(b), fake.state.audioConnected)
    }

    @Test fun latestRequestAlsoWinsDuringDisconnect() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(b), 0)
        control.submit(TagCommand.Connect(a), 10)
        fake.disconnected(a)
        control.changed(20)
        assertEquals(listOf("disconnect:$a", "connect:$a"), fake.calls)
        fake.connected(a)
        control.changed(30)
        assertEquals(TagCommand.Connect(a), control.status.command)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
    }

    @Test fun missingPermissionAssociationOrAllowlistNeverReachesTheApi() {
        val states = listOf(
            BluetoothSnapshot(permission = false) to SwitchFailure.PERMISSION,
            FakeBluetooth(a, b, watch).state.copy(enabled = false) to SwitchFailure.BLUETOOTH_OFF,
            FakeBluetooth(a, b, watch).state.copy(allowed = emptySet()) to SwitchFailure.NOT_ALLOWED,
            FakeBluetooth(a, b, watch).state.copy(associated = emptySet()) to SwitchFailure.NOT_ALLOWED,
            FakeBluetooth(a, b, watch).state.copy(devices = emptySet()) to SwitchFailure.NOT_ALLOWED,
        )
        for ((state, failure) in states) {
            val fake = FakeBluetooth(a, b, watch).apply { this.state = state }
            val control = SwitchController(fake)
            control.submit(TagCommand.Connect(a), 0)
            assertEquals(failure, control.status.failure)
            assertTrue(fake.calls.isEmpty())
        }
    }

    @Test fun permissionAndAuthorizationAreRecheckedBeforeTheNextAction() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(b), 0)
        fake.state = fake.state.copy(allowed = setOf(a))
        fake.disconnected(a)
        control.changed(10)
        assertEquals(SwitchFailure.NOT_ALLOWED, control.status.failure)
        assertEquals(listOf("disconnect:$a"), fake.calls)
        control.submit(TagCommand.Connect(b), 20)
        assertEquals(listOf("disconnect:$a"), fake.calls)
    }

    @Test fun revokedOtherDeviceIsNotDisconnectedByAValidTarget() {
        val fake = FakeBluetooth(a, b, watch)
        fake.connected(a)
        fake.state = fake.state.copy(associated = setOf(b))
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(b), 0)
        assertEquals(listOf("connect:$b"), fake.calls)
    }

    @Test fun rejectedApiAndTimeoutFailInsteadOfPretendingToConnect() {
        val fake = FakeBluetooth(a, b, watch)
        fake.accept = false
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        assertEquals(SwitchFailure.API_REJECTED, control.status.failure)
        fake.accept = true
        control.submit(TagCommand.Connect(a), 10)
        control.submit(TagCommand.Connect(a), 100)
        control.changed(10 + SwitchController.TIMEOUT_MS)
        assertEquals(SwitchFailure.TIMEOUT, control.status.failure)
        assertEquals("disconnect:$a", fake.calls.last())
        val size = fake.calls.size
        control.changed(100_000)
        assertEquals(size, fake.calls.size)
    }

    @Test fun waitsForProfilesAndForEveryLinkToDisconnect() {
        val fake = FakeBluetooth(a, b, watch)
        fake.state = fake.state.copy(profilesReady = false)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        assertTrue(fake.calls.isEmpty())
        fake.state = fake.state.copy(profilesReady = true)
        control.changed(10)
        fake.connected(a)
        control.changed(20)
        control.submit(TagCommand.Disconnect(a), 30)
        fake.state = fake.state.copy(audioConnected = emptySet())
        control.changed(40)
        assertEquals(SwitchPhase.WORKING, control.status.phase)
        fake.disconnected(a)
        control.changed(50)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
    }

    @Test fun closingCancelsAnUnfinishedConnectAndCannotActAgain() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        control.close()
        control.changed(10)
        assertEquals(listOf("connect:$a", "disconnect:$a"), fake.calls)
        control.submit(TagCommand.Connect(b), 20)
        assertEquals(SwitchFailure.CLOSED, control.status.failure)
        assertEquals(2, fake.calls.size)
    }

    @Test fun untrustedRequestCannotCancelAnAuthorizedConnection() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        assertEquals(SwitchFailure.NOT_ALLOWED, control.submit(TagCommand.Connect(watch), 10))
        assertEquals(listOf("connect:$a"), fake.calls)
        assertEquals(TagCommand.Connect(a), control.status.command)
        fake.connected(a)
        control.changed(20)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
    }

    @Test fun timeoutDoesNotForgetAConnectionThatCompletesLate() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        control.changed(SwitchController.TIMEOUT_MS)
        control.submit(TagCommand.Connect(b), SwitchController.TIMEOUT_MS + 10)
        assertEquals(listOf("connect:$a", "disconnect:$a"), fake.calls)
        assertEquals(SwitchPhase.WORKING, control.status.phase)
        fake.connected(a)
        control.changed(SwitchController.TIMEOUT_MS + 20)
        assertEquals("disconnect:$a", fake.calls.last())
        fake.disconnected(a)
        control.changed(SwitchController.TIMEOUT_MS + 30)
        assertEquals("connect:$b", fake.calls.last())
        fake.connected(b)
        control.changed(SwitchController.TIMEOUT_MS + 40)
        assertEquals(setOf(b), fake.state.audioConnected)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
    }

    @Test fun aConfirmedCancellationAllowsTheNextRequestWithoutEverConnecting() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        control.changed(SwitchController.TIMEOUT_MS)
        fake.disconnected(a)
        control.changed(SwitchController.TIMEOUT_MS + 10)
        control.submit(TagCommand.Connect(b), SwitchController.TIMEOUT_MS + 20)
        assertEquals(listOf("connect:$a", "disconnect:$a", "connect:$b"), fake.calls)
    }

    @Test fun cancellingKeepsPendingWorkUntilTheNativeDisconnectIsConfirmed() {
        val fake = FakeBluetooth(a, b, watch)
        val control = SwitchController(fake)
        control.submit(TagCommand.Connect(a), 0)
        control.cancel()
        assertTrue(control.hasPendingWork)
        assertEquals(SwitchFailure.CLOSED, control.status.failure)
        control.submit(TagCommand.Connect(b), 10)
        assertEquals(listOf("connect:$a", "disconnect:$a"), fake.calls)
        fake.disconnected(a)
        control.changed(20)
        fake.connected(b)
        control.changed(30)
        assertFalse(control.hasPendingWork)
        assertEquals(SwitchPhase.SUCCEEDED, control.status.phase)
    }
}

private class FakeBluetooth(a: String, b: String, watch: String) : BluetoothAccess {
    var state = BluetoothSnapshot(permission = true, enabled = true, profilesReady = true,
        devices = setOf(a, b, watch), allowed = setOf(a, b), associated = setOf(a, b))
    val calls = mutableListOf<String>()
    var accept = true
    override fun snapshot() = state
    override fun connect(address: String): Boolean {
        calls += "connect:$address"
        state = state.copy(confirmedDisconnections = state.confirmedDisconnections - address)
        return accept
    }
    override fun disconnect(address: String): Boolean {
        calls += "disconnect:$address"
        state = state.copy(confirmedDisconnections = state.confirmedDisconnections - address)
        return accept
    }
    fun connected(vararg addresses: String) {
        state = state.copy(audioConnected = state.audioConnected + addresses, linked = state.linked + addresses)
    }
    fun disconnected(address: String) {
        state = state.copy(audioConnected = state.audioConnected - address,
            linked = state.linked - address, transitioning = state.transitioning - address,
            confirmedDisconnections = state.confirmedDisconnections + address)
    }
}
