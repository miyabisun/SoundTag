package dev.miyabisun.soundtag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/** Activity-scoped public API adapter. Close it after closing the controller. */
class AndroidBluetooth(
    private val context: Context,
    private val settings: SettingsAccess,
    private val changed: () -> Unit,
) : BluetoothAccess, AutoCloseable {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private val requested = mutableSetOf<Int>()
    private var registered = false
    private var closed = false
    private val disconnected = mutableSetOf<String>()
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed) { adapter?.closeProfileProxy(profile, proxy); return }
            profiles[profile] = proxy
            changed()
        }
        override fun onServiceDisconnected(profile: Int) {
            profiles.remove(profile)
            if (!closed) changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    ?.address?.let { disconnected += it }
            }
            changed()
        }
    }

    override fun start() {
        if (registered || closed) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        registered = true
    }

    override fun snapshot(): BluetoothSnapshot {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) return BluetoothSnapshot()
        val bluetooth = adapter ?: return BluetoothSnapshot(permission = true)
        if (!bluetooth.isEnabled) return BluetoothSnapshot(permission = true)
        val needed = buildSet {
            add(BluetoothProfile.A2DP)
            if (bluetooth.isLeAudioSupported == BluetoothStatusCodes.FEATURE_SUPPORTED) add(BluetoothProfile.LE_AUDIO)
        }
        needed.filter { it !in requested }.forEach {
            if (bluetooth.getProfileProxy(context, listener, it)) requested += it
        }
        val devices = bluetooth.bondedDevices
        val audio = mutableSetOf<String>()
        val transitioning = mutableSetOf<String>()
        val linked = mutableSetOf<String>()
        devices.forEach { device ->
            profiles.values.forEach { profile ->
                when (profile.getConnectionState(device)) {
                    BluetoothProfile.STATE_DISCONNECTED -> Unit
                    BluetoothProfile.STATE_CONNECTED -> audio += device.address
                    BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_DISCONNECTING -> transitioning += device.address
                }
            }
            if (device.isConnected(BluetoothDevice.TRANSPORT_BREDR) ||
                device.isConnected(BluetoothDevice.TRANSPORT_LE)) linked += device.address
        }
        return BluetoothSnapshot(true, true, profiles.keys.containsAll(needed),
            devices.map { it.address }.toSet(), settings.allowed(), settings.associations(),
            audio, linked, transitioning, disconnected.toSet())
    }

    override fun connect(address: String) = operate(address, true)
    override fun disconnect(address: String) = operate(address, false)

    @SuppressLint("MissingPermission") // Runtime permission and CDM association are checked immediately before the API.
    private fun operate(address: String, connect: Boolean): Boolean {
        if (closed) return false
        if (!settings.hasPermission()) throw SecurityException("Bluetooth permission revoked")
        val bluetooth = adapter ?: return false
        if (!bluetooth.isEnabled || address !in settings.allowed() || address !in settings.associations()) return false
        val device = bluetooth.bondedDevices.firstOrNull { it.address == address } ?: return false
        disconnected -= address
        return (if (connect) device.connect() else device.disconnect()) == BluetoothStatusCodes.SUCCESS
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) context.unregisterReceiver(receiver)
        profiles.forEach { (type, proxy) -> adapter?.closeProfileProxy(type, proxy) }
        profiles.clear()
    }
}
