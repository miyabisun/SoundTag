package dev.miyabisun.soundtag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Only Android's NFC dispatcher (or this app's own UID) can open this entry. */
class NfcActivity : Activity() {
    companion object {
        internal var bluetoothFactory: ((Context, () -> Unit) -> BluetoothAccess)? = null
        private var pendingSession: NfcSession? = null
        internal fun release(session: NfcSession) {
            if (pendingSession === session) pendingSession = null
        }
    }
    internal lateinit var session: NfcSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? NfcSession ?: newSession()
        session.updated = { render() }
        if (lastNonConfigurationInstance == null && savedInstanceState == null) session.receive(intent)
        else if (lastNonConfigurationInstance == null) session.error = "もう一度タグをかざしてください"
        render()
    }

    private fun newSession() = pendingSession?.takeUnless { it.closed }
        ?: NfcSession(applicationContext, bluetoothFactory).also { pendingSession = it }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (session.closed) session = newSession().apply { updated = { render() } }
        session.receive(intent)
    }

    override fun onStart() {
        super.onStart()
        session.foreground = true
        session.tick()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            session.foreground = false
            session.closeWhenIdle()
        }
    }

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onDestroy() {
        session.updated = {}
        if (!isChangingConfigurations) session.leave()
        super.onDestroy()
    }

    private fun render() {
        val state = session.controller.status
        val failure = session.error ?: state.failure?.let { failureLabel(it) }
        val text = failure ?: when (state.phase) {
            SwitchPhase.IDLE -> "タグをかざしてください"
            SwitchPhase.WORKING -> "切替中…"
            SwitchPhase.SUCCEEDED -> when (state.command) {
                is TagCommand.Connect -> "接続しました"
                is TagCommand.Disconnect -> "切断しました"
                else -> "スマホに戻しました"
            }
            SwitchPhase.FAILED -> "操作できませんでした"
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(248, 250, 248))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            this.text = value
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(28, 42, 39))
            setPadding(0, dp(16), 0, dp(16))
            content.addView(this)
        }
        label("SoundTag", 32f).typeface = Typeface.DEFAULT_BOLD
        content.addView(ImageView(this).apply {
            setImageResource(when {
                failure != null -> R.drawable.ic_error
                state.phase == SwitchPhase.WORKING -> R.drawable.ic_soundtag
                state.phase == SwitchPhase.SUCCEEDED -> R.drawable.ic_success
                else -> R.drawable.ic_tag
            })
            contentDescription = text
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        label(text, 24f).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val name = try { session.settings.speakers().firstOrNull { it.address == state.command?.target }?.name }
            catch (_: RuntimeException) { null }
        if (name != null) label(name, 18f)
        content.addView(Button(this).apply {
            this.text = "設定を開く"
            minHeight = dp(48)
            setOnClickListener { startActivity(Intent(this@NfcActivity, MainActivity::class.java)); finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            this.text = if (state.phase == SwitchPhase.WORKING) "中止して閉じる" else "閉じる"
            minHeight = dp(48)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(content)
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun failureLabel(failure: SwitchFailure): String = when (failure) {
    SwitchFailure.PERMISSION -> "権限を確認してください"
    SwitchFailure.BLUETOOTH_OFF -> "BluetoothがOFFです"
    SwitchFailure.NOT_ALLOWED -> "機器の許可を確認してください"
    SwitchFailure.API_REJECTED -> "操作を開始できませんでした"
    SwitchFailure.TIMEOUT -> "接続を確認できませんでした"
    SwitchFailure.CLOSED -> "操作を中止しました"
}

/** Shared until native work settles, including after closing the UI. No service or polling. */
internal class NfcSession(context: Context, factory: ((Context, () -> Unit) -> BluetoothAccess)?) {
    val settings = AndroidSettings(context)
    private val bluetooth = factory?.invoke(context) { tick() } ?: AndroidBluetooth(context, settings) { tick() }
    val controller = SwitchController(bluetooth)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { tick() }
    var updated: () -> Unit = {}
    var error: String? = null
    var foreground = true
    var closed = false
        private set

    init { bluetooth.start() }

    fun receive(intent: Intent) {
        val command = if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED)
            intent.dataString?.let(TagCommand::parse) else null
        if (command == null) {
            error = "タグを確認してください"
        } else {
            val working = controller.status.phase == SwitchPhase.WORKING
            error = controller.submit(command, SystemClock.elapsedRealtime())?.let(::failureLabel)
            if (!working && controller.status.phase == SwitchPhase.WORKING) {
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, SwitchController.TIMEOUT_MS)
            }
        }
        updated()
    }

    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        if (closed) return
        controller.changed(now)
        if (controller.status.phase != SwitchPhase.WORKING) handler.removeCallbacks(timeout)
        updated()
        closeWhenIdle()
    }

    fun closeWhenIdle() {
        if (!foreground && !controller.hasPendingWork) close()
    }

    fun leave() {
        foreground = false
        controller.cancel()
        handler.removeCallbacks(timeout)
        closeWhenIdle()
    }

    fun close() {
        if (closed) return
        controller.close()
        bluetooth.close()
        handler.removeCallbacks(timeout)
        closed = true
        NfcActivity.release(this)
    }
}
