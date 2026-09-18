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
    }
    private lateinit var controller: SettingsController
    private lateinit var access: SettingsAccess
    private lateinit var content: LinearLayout
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
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selected?.uri())
        super.onSaveInstanceState(outState)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
    override fun onDestroy() {
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
            if (selected != null) showCode(checkNotNull(selected)) else showSpeakers()
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
        label("スピーカー", 22, true)
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
            button(row.speaker.name, "${row.speaker.name}のタグ用コード") {
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
        label("スマホに戻す", 22, true)
        button("スマホのタグ", "スマホに戻すタグ用コード") {
            selected = TagCommand.Phone
            message = ""
            render()
        }.apply {
            isEnabled = state.speakers.any { it.allowed }
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
        }
    }

    private fun showCode(command: TagCommand) {
        button("スピーカー一覧") {
            selected = null
            message = ""
            render()
        }
        val name = controller.snapshot().speakers.firstOrNull { it.speaker.address == command.target }?.speaker?.name
        label(name ?: "スマホに戻す", 22, true)
        if (command.target != null) {
            button("▶  接続", "スピーカーに接続するタグ") {
                selected = TagCommand.Connect(checkNotNull(command.target))
                message = ""
                render()
            }
            button("⏹  切断", "スピーカーを切断するタグ") {
                selected = TagCommand.Disconnect(checkNotNull(command.target))
                message = ""
                render()
            }
        }
        val code = controller.code(command)
        label(when (command) {
            is TagCommand.Connect -> "▶  接続"
            is TagCommand.Disconnect -> "⏹  切断"
            TagCommand.Phone -> "スマホ"
        }, 22, true)
        val steps = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val targetIcon = if (command == TagCommand.Phone) R.drawable.ic_phone else R.drawable.ic_soundtag
        val targetLabel = if (command == TagCommand.Phone) "スマホ" else "スピーカー"
        listOf(targetIcon to targetLabel, R.drawable.ic_copy to "コピー", R.drawable.ic_tag to "NFCタグ")
            .forEachIndexed { index, (icon, caption) ->
                if (index > 0) steps.addView(TextView(this).apply {
                    text = "→"
                    textSize = 22f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                val step = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(icon)
                        contentDescription = caption
                    }, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(TextView(this@MainActivity).apply {
                        text = caption
                        textSize = 14f
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                }
                steps.addView(step, LinearLayout.LayoutParams(0, -2, 1f))
            }
        content.addView(steps)
        if (code == null) {
            label("機器の自動操作を許可してください", 16)
            return
        }
        label(code, 14).apply { setTextIsSelectable(true) }
        button("コピー", "タグ用コードをコピー") {
            message = if (controller.copy(command)) "コピーしました" else "機器の許可を確認してください"
            render()
        }.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_copy, 0, 0, 0)
        label("NFC Tools → URIレコード", 16)
        label("Bluetoothレコードは削除", 14)
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
