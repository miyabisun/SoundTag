package dev.miyabisun.soundtag

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import java.util.Locale

class AndroidSettings(private val activity: Context) : SettingsAccess {
    private val adapter get() = activity.getSystemService(BluetoothManager::class.java)?.adapter
    private val companion get() = activity.getSystemService(CompanionDeviceManager::class.java)
    private val preferences = activity.getSharedPreferences("soundtag", Activity.MODE_PRIVATE)

    override fun hasPermission() = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
    override fun bluetoothEnabled() = hasPermission() && adapter?.isEnabled == true
    override fun speakers(): List<Speaker> {
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) return emptyList()
        return adapter?.bondedDevices.orEmpty().map {
            Speaker(it.address, it.alias ?: it.name ?: it.address,
                it.isConnected(BluetoothDevice.TRANSPORT_BREDR) || it.isConnected(BluetoothDevice.TRANSPORT_LE))
        }.sortedBy { it.name }
    }
    override fun associations() = companion?.myAssociations.orEmpty()
        .mapNotNull { it.deviceMacAddress?.toString()?.uppercase(Locale.ROOT) }.toSet()
    override fun allowed() = preferences.getStringSet("allowed", emptySet()).orEmpty().toSet()
    override fun saveAllowed(addresses: Set<String>) {
        check(preferences.edit().putStringSet("allowed", addresses).commit()) { "設定を保存できません" }
    }
    override fun associate(address: String, complete: (Boolean) -> Unit) {
        val screen = activity as? Activity ?: return complete(false)
        val manager = companion ?: return complete(false)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true).build()
        try {
            manager.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    try {
                        screen.startIntentSenderForResult(intentSender, 2, null, 0, 0, 0)
                    } catch (_: IntentSender.SendIntentException) {
                        complete(false)
                    }
                }
                override fun onAssociationCreated(info: AssociationInfo) {
                    complete(info.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true)
                }
                override fun onFailure(error: CharSequence?) { complete(false) }
            })
        } catch (_: SecurityException) {
            complete(false)
        } catch (_: IllegalStateException) {
            complete(false)
        }
    }
    override fun disassociate(address: String) {
        companion?.myAssociations.orEmpty().filter {
            it.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
        }.forEach { companion?.disassociate(it.id) }
    }
}
