package dev.miyabisun.soundtag

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    companion object {
        // Instrumentation supplies its fake here; there is no user-facing mock mode.
        internal var accessFactory: ((Activity) -> SettingsAccess)? = null
        internal var nfcFactory: ((Activity) -> NfcWriting)? = null
    }
    private lateinit var controller: SettingsController
    private lateinit var access: SettingsAccess
    private lateinit var content: LinearLayout
    private lateinit var nfc: NfcWriting
    private lateinit var writer: TagWriter
    private var selected: TagCommand? = null
    private var message = ""
    private var registered = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        access = accessFactory?.invoke(this) ?: AndroidSettings(this)
        controller = SettingsController(access)
        nfc = nfcFactory?.invoke(this) ?: AndroidTagWriter(this)
        writer = TagWriter(controller, nfc) { render() }
        selected = savedInstanceState?.getString("selected")?.let(TagCommand::parse)
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(changes, filter, RECEIVER_EXPORTED)
        registered = true
        render()
        val preferences = getPreferences(MODE_PRIVATE)
        if (!access.hasPermission() && !preferences.getBoolean("permissionAsked", false)) {
            preferences.edit().putBoolean("permissionAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) render()
    }
    override fun onPause() {
        if (writer.phase == WritePhase.WRITING) message = "書込みを中断しました。タグの内容を確認してください"
        writer.cancel()
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected?.uri())
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
    override fun onDestroy() {
        writer.cancel()
        (nfc as? AutoCloseable)?.close()
        controller.close()
        if (registered) unregisterReceiver(changes)
        super.onDestroy()
    }

    private fun render() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(248, 250, 248))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)
        label("SoundTag", 32, true)
        try {
            if (selected != null) showWriter(checkNotNull(selected)) else showSpeakers()
        } catch (_: SecurityException) {
            message = "権限を確認してください"
            permissionButton()
        } catch (_: IllegalStateException) {
            message = "設定を保存できませんでした"
        }
        if (message.isNotEmpty()) label(message, 16).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(Color.rgb(33, 94, 85))
        }
        scroll.requestApplyInsets()
    }

    private fun showSpeakers() {
        if (!OperationNotifications(this).resultsEnabled()) {
            label("接続状態が変わったときの通知がOFFです", 16)
            button("通知を設定") {
                val preferences = getPreferences(MODE_PRIVATE)
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    (!preferences.getBoolean("notificationsAsked", false) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                    preferences.edit().putBoolean("notificationsAsked", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
        }
        label("特定の機器の接続", 22, true)
        label("タグからの自動操作を許可", 16)
        val state = controller.snapshot()
        if (!state.permission) {
            label("Bluetoothへのアクセスが必要です", 16)
            permissionButton()
            return
        }
        if (!state.bluetoothEnabled) {
            label("BluetoothがOFFです", 16)
            button("Bluetoothを開く") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        if (state.speakers.isEmpty()) {
            label("ペアリング済みの機器がありません", 16)
            button("機器をペアリング") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        state.speakers.forEach { row ->
            button(row.speaker.name, "${row.speaker.name}に接続するタグ") {
                selected = TagCommand.Connect(row.speaker.address)
                message = ""
                render()
            }.isEnabled = row.allowed
            label(if (row.speaker.connected) "接続中" else "未接続", 14)
            val toggle = Switch(this).apply {
                text = "自動操作を許可"
                contentDescription = "${row.speaker.name}の自動操作を許可"
                minHeight = dp(48)
                isChecked = row.allowed
                isEnabled = state.bluetoothEnabled || row.allowed
                setOnCheckedChangeListener { _, enabled ->
                    message = if (enabled) "機器の確認中…" else ""
                    try {
                        controller.setAllowed(row.speaker.address, enabled) { result ->
                            if (!isDestroyed) {
                                message = when (result) {
                                    SettingsResult.SAVED -> if (enabled) "許可しました" else "許可を解除しました"
                                    SettingsResult.PERMISSION_REQUIRED -> "権限を確認してください"
                                    SettingsResult.BLUETOOTH_OFF -> "BluetoothがOFFです"
                                    SettingsResult.UNKNOWN_DEVICE -> "機器を選び直してください"
                                    SettingsResult.ASSOCIATION_FAILED -> "機器の確認ができませんでした"
                                }
                                render()
                            }
                        }
                    } catch (_: RuntimeException) {
                        message = "設定を変更できませんでした"
                        render()
                    }
                }
            }
            content.addView(toggle)
        }
        label("許可した機器をまとめて切断", 16)
        button("全て切断", "許可した全ての機器を切断するタグ") {
            selected = TagCommand.Phone
            message = ""
            render()
        }.apply {
            isEnabled = state.speakers.any { it.allowed }
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
        }
    }

    private fun showWriter(command: TagCommand) {
        val state = writer.phase
        val name = controller.snapshot().speakers.firstOrNull { it.speaker.address == command.target }?.speaker?.name
        label(if (command == TagCommand.Phone) "全て切断" else "特定の機器の接続", 22, true)
        label(name ?: "自動操作を許可した機器だけ", 18)
        val text = writer.failure?.let { failure -> when (failure) {
            WriteFailure.UNAVAILABLE -> "この端末はNFCに対応していません"
            WriteFailure.DISABLED -> "NFCがOFFです"
            WriteFailure.UNSUPPORTED -> "このタグには対応していません"
            WriteFailure.READ_ONLY -> "このタグは書込みできません"
            WriteFailure.TOO_SMALL -> "タグの容量が足りません"
            WriteFailure.LOST -> "タグが離れました"
            WriteFailure.IO -> "書込みを確認できませんでした"
            WriteFailure.NOT_ALLOWED -> "機器の自動操作を許可してください"
        } } ?: when (state) {
            WritePhase.IDLE -> "操作をタグに保存"
            WritePhase.WAITING -> "タグをかざしてください"
            WritePhase.WRITING -> "書込み中…"
            WritePhase.SUCCEEDED -> "書き込みました"
            WritePhase.FAILED -> "書込みできませんでした"
        }
        content.addView(ImageView(this).apply {
            setImageResource(when (state) {
                WritePhase.SUCCEEDED -> R.drawable.ic_success
                WritePhase.FAILED -> R.drawable.ic_error
                else -> R.drawable.ic_tag
            })
            contentDescription = text
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        label(text, 22, true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if (state == WritePhase.IDLE || state == WritePhase.FAILED) {
            label("タグの内容を上書きします", 16)
            button(if (state == WritePhase.IDLE) "タグに書き込む" else "もう一度書き込む") {
                message = ""
                writer.start(command)
            }
            if (writer.failure == WriteFailure.DISABLED) {
                button("NFCを開く") { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
        }
        if (state == WritePhase.WAITING || state == WritePhase.WRITING) {
            label("タグを端末の背面に近づけてください", 16)
            button("中止") {
                message = if (writer.phase == WritePhase.WRITING) "タグの内容を確認してください" else ""
                writer.cancel()
                render()
            }
        } else if (state == WritePhase.IDLE) {
            button("スピーカー一覧") { selected = null; message = ""; render() }
        } else {
            label("タグを離してから戻ってください", 16)
            button("タグを離して戻る") { writer.cancel(); message = ""; render() }
        }
    }

    private fun permissionButton() {
        button("権限を設定") {
            if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
    }
    private fun label(value: String, size: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(Color.rgb(28, 42, 39))
            setPadding(0, dp(12), 0, dp(8))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            content.addView(this)
        }
    private fun button(value: String, description: String = value, action: () -> Unit): Button =
        Button(this).apply {
            text = value
            contentDescription = description
            isAllCaps = false
            minHeight = dp(48)
            gravity = Gravity.CENTER
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
