package dev.miyabisun.soundtag

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Rect
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

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
    private lateinit var scroll: ScrollView
    private val backToSpeakers = OnBackInvokedCallback { collapse() }
    private var selected: TagCommand? = null
    private var message = ""
    private var revealSelection = false
    private var registered = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            SpeakerWidget.update(context)
            render()
        }
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
        SpeakerWidget.update(this)
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
        SpeakerWidget.update(this)
        render()
    }
    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToSpeakers)
        writer.cancel()
        (nfc as? AutoCloseable)?.close()
        controller.close()
        if (registered) unregisterReceiver(changes)
        super.onDestroy()
    }

    private fun render() {
        val position = if (::scroll.isInitialized) scroll.scrollY else 0
        scroll = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.background))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)
        content.requestFocus()
        var opened: View? = null
        try {
            opened = showSpeakers()
        } catch (_: SecurityException) {
            message = "権限を確認してください"
            permissionButton()
            showMessage()
        } catch (_: IllegalStateException) {
            message = "設定を保存できませんでした"
            showMessage()
        }
        // Back closes the open card; with every card closed it follows the OS.
        if (opened == null) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToSpeakers)
        else onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToSpeakers)
        val reveal = revealSelection
        revealSelection = false
        scroll.requestApplyInsets()
        scroll.post {
            scroll.scrollTo(0, position)
            if (reveal) opened?.let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), false) }
        }
    }

    /** Returns the open card so a newly opened accordion can be scrolled into view. */
    private fun showSpeakers(): View? {
        val state = controller.snapshot()
        if (!state.permission) {
            reason("Bluetoothへのアクセスが必要です")
            permissionButton()
            showMessage()
            footer()
            return null
        }
        if (!state.bluetoothEnabled) {
            reason("BluetoothがOFFです")
            button("Bluetoothを開く") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        if (state.speakers.isEmpty()) {
            reason("ペアリング済みの機器がありません")
            button("機器をペアリング") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        var opened: View? = null
        state.speakers.forEach { row ->
            val command = TagCommand.Connect(row.speaker.address)
            card(command, row.speaker.name, connectorIcon(row.speaker.connected), connectionDescription(row.speaker),
                openable = true, writable = row.allowed) { allowSwitch(row, state) }?.let { opened = it }
        }
        val anyAllowed = state.speakers.any { it.allowed }
        card(TagCommand.Phone, getString(R.string.disconnect_all), R.drawable.ic_phone,
            getString(R.string.disconnect_all), openable = anyAllowed, writable = anyAllowed)?.let { opened = it }
        showMessage()
        footer()
        return opened
    }

    /**
     * One accordion card; returns it when it is the open one. A speaker card always opens for its
     * allow switch (initial setup), but only allowed targets get the tag writer.
     */
    private fun card(command: TagCommand, name: String, icon: Int, description: String, openable: Boolean,
                     writable: Boolean, setup: (() -> Switch)? = null): View? {
        val open = openable && selected == command
        val panel = panel()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            contentDescription = description
            isEnabled = openable
            if (openable) {
                stateDescription = if (open) "開いています" else "閉じています"
                background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.border)), null, null)
                setOnClickListener { if (open) collapse() else expand(command) }
            }
        }
        header.addView(ImageView(this).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        header.addView(TextView(this).apply {
            text = name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(if (writable) R.color.text else R.color.muted))
            setPadding(dp(12), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_next)
            rotation = if (open) -90f else 90f
            visibility = if (openable) View.VISIBLE else View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        if (open) {
            setup?.let { panel.addView(it(), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; marginEnd = dp(8) }) }
            if (writable) writer(command, panel)
        }
        return panel.takeIf { open }
    }

    private fun allowSwitch(row: SpeakerSetting, state: SettingsSnapshot) = Switch(this).apply {
        contentDescription = "${row.speaker.name}の自動操作を許可"
        minHeight = dp(48)
        minWidth = dp(48)
        isChecked = row.allowed
        isEnabled = state.bluetoothEnabled || row.allowed
        setOnCheckedChangeListener { _, enabled ->
            val aborted = !enabled && writer.phase == WritePhase.WRITING
            message = if (enabled) "機器の確認中…" else ""
            if (!enabled) writer.cancel()
            try {
                controller.setAllowed(row.speaker.address, enabled) { result ->
                    if (!isDestroyed) {
                        message = when (result) {
                            SettingsResult.SAVED -> when {
                                enabled -> "許可しました"
                                aborted -> "書込みを中断しました。タグの内容を確認してください"
                                else -> "許可を解除しました"
                            }
                            SettingsResult.PERMISSION_REQUIRED -> "権限を確認してください"
                            SettingsResult.BLUETOOTH_OFF -> "BluetoothがOFFです"
                            SettingsResult.UNKNOWN_DEVICE -> "機器を選び直してください"
                            SettingsResult.ASSOCIATION_FAILED -> "機器の確認ができませんでした"
                        }
                        SpeakerWidget.update(this@MainActivity)
                        render()
                    }
                }
            } catch (_: RuntimeException) {
                message = "設定を変更できませんでした"
                render()
            }
        }
    }

    private fun footer() {
        button("通知の設定") {
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
        }.apply { topMargin(24) }
        button("通知へのアクセス") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    YouTubeRecovery.component(this).flattenToString()))
        }
    }

    private fun writer(command: TagCommand, panel: LinearLayout) {
        val state = writer.phase
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
            WritePhase.IDLE -> "タグの内容を上書きします"
            WritePhase.WAITING -> "タグをかざしてください"
            WritePhase.WRITING -> "書込み中…"
            WritePhase.SUCCEEDED -> "書き込みました"
            WritePhase.FAILED -> "書込みできませんでした"
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            panel.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        status.addView(ImageView(this).apply {
            setImageResource(when (state) {
                WritePhase.SUCCEEDED -> R.drawable.ic_success
                WritePhase.FAILED -> R.drawable.ic_error
                else -> R.drawable.ic_tag
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        status.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(if (state == WritePhase.FAILED) R.color.danger else R.color.text))
            setPadding(dp(12), dp(8), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        when (state) {
            WritePhase.IDLE, WritePhase.FAILED -> {
                if (state == WritePhase.FAILED) label("タグの内容を上書きします", parent = body)
                button(if (state == WritePhase.IDLE) "タグに書き込む" else "もう一度書き込む", primary = true, parent = body) {
                    message = ""
                    writer.start(command)
                }
                if (writer.failure == WriteFailure.DISABLED) {
                    button("NFCを開く", parent = body) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                }
            }
            WritePhase.WAITING, WritePhase.WRITING -> {
                label("タグを端末の背面に近づけてください", parent = body)
                button("中止", parent = body) {
                    message = if (writer.phase == WritePhase.WRITING) "タグの内容を確認してください" else ""
                    writer.cancel()
                    render()
                }
            }
            WritePhase.SUCCEEDED -> {
                label("タグを離してから閉じてください", parent = body)
                button("閉じる", parent = body) { collapse() }
            }
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
    private fun expand(command: TagCommand) {
        collapse(rerender = false)
        selected = command
        revealSelection = true
        render()
    }
    private fun collapse(rerender: Boolean = true) {
        message = if (writer.phase == WritePhase.WRITING) "書込みを中断しました。タグの内容を確認してください" else ""
        writer.cancel()
        selected = null
        if (rerender) render()
    }
    private fun reason(value: String) = label(value)
    private fun showMessage() {
        if (message.isNotEmpty()) label(message).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(getColor(R.color.accent))
        }
    }
    private fun View.topMargin(value: Int) {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(value)
    }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = surface(R.color.surface)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(8).toFloat()
        if (color == R.color.surface) setStroke(dp(1), getColor(R.color.border))
    }
    private fun label(value: String, parent: LinearLayout = content): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(getColor(R.color.text))
        setPadding(0, dp(8), 0, dp(8))
        parent.addView(this)
    }
    private fun button(value: String, description: String = value, primary: Boolean = false,
                       parent: LinearLayout = content, action: () -> Unit): Button = Button(this).apply {
        text = value
        textSize = 16f
        contentDescription = description
        isAllCaps = false
        setSingleLine(false)
        minHeight = dp(48)
        gravity = Gravity.CENTER
        stateListAnimator = null
        background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.border)),
            surface(if (primary) R.color.accent else R.color.surface), null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(getColor(R.color.muted), getColor(if (primary) R.color.on_accent else R.color.text))))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
