package dev.miyabisun.soundtag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Foreground only while switching; unresolved native cancellation retains the existing session. */
class NfcService : Service() {
    companion object {
        internal var bluetoothFactory: ((Context, () -> Unit) -> BluetoothAccess)? = null
        internal var pendingSession: NfcSession? = null
    }
    private var session: NfcSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notices = OperationNotifications(this)
        startForeground(1, notices.progress(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val command = intent?.dataString?.let(TagCommand::parse)
        if (command == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val current = pendingSession ?: NfcSession(applicationContext, bluetoothFactory).also { pendingSession = it }
        session = current
        current.updated = {
            if (current.controller.status.phase != SwitchPhase.WORKING) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        current.receive(command)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.let {
            it.updated = {}
            it.leave()
        }
        super.onDestroy()
    }
}

internal class NfcSession(context: Context, factory: ((Context, () -> Unit) -> BluetoothAccess)?) {
    private val settings = AndroidSettings(context)
    private val notices = OperationNotifications(context)
    private val bluetooth = factory?.invoke(context) { tick() } ?: AndroidBluetooth(context, settings) { tick() }
    val controller = SwitchController(bluetooth)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { tick() }
    private var delivered = false
    private var closed = false
    var updated: () -> Unit = {}

    init { bluetooth.start() }

    fun receive(command: TagCommand) {
        val working = controller.status.phase == SwitchPhase.WORKING
        if (!working) delivered = false
        controller.submit(command, SystemClock.elapsedRealtime())
        if (!working && controller.status.phase == SwitchPhase.WORKING) {
            handler.postDelayed(timeout, SwitchController.TIMEOUT_MS)
        }
        finishIfSettled()
    }

    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        if (closed) return
        controller.changed(now)
        finishIfSettled()
    }

    private fun finishIfSettled() {
        val state = controller.status
        if (state.phase != SwitchPhase.WORKING) {
            handler.removeCallbacks(timeout)
            if (!delivered && controller.connectionsChanged) {
                val name = try { settings.speakers().firstOrNull { it.address == state.command?.target }?.name }
                    catch (_: RuntimeException) { null }
                notices.result(state, name)
                delivered = true
            }
        }
        updated()
        if (!controller.hasPendingWork) close()
    }

    fun leave() {
        controller.cancel()
        finishIfSettled()
    }

    private fun close() {
        if (closed) return
        closed = true
        controller.close()
        bluetooth.close()
        handler.removeCallbacks(timeout)
        if (NfcService.pendingSession === this) NfcService.pendingSession = null
    }
}

internal class OperationNotifications(private val context: Context) {
    companion object { const val RESULTS = "connection-results" }
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel("switching", "接続操作中", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RESULTS, "接続状態の変化", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun resultsEnabled() = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(RESULTS).importance != NotificationManager.IMPORTANCE_NONE

    fun progress(): Notification = builder("switching", "切替中…")
        .setOngoing(true).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED).build()

    fun result(state: SwitchStatus, name: String?) {
        if (!resultsEnabled()) return
        val text = state.failure?.let { "接続状態が変わりました・${failureLabel(it)}" } ?: when (state.command) {
            is TagCommand.Connect -> "接続しました"
            is TagCommand.Disconnect -> "切断しました"
            else -> "全て切断しました"
        }
        manager.notify(2, builder(RESULTS, text).setContentText(name ?: "自動操作を許可した機器")
            .setAutoCancel(true).build())
    }

    private fun builder(channel: String, text: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text)
        .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setVisibility(Notification.VISIBILITY_PRIVATE)
}

internal fun failureLabel(failure: SwitchFailure): String = when (failure) {
    SwitchFailure.PERMISSION -> "権限を確認してください"
    SwitchFailure.BLUETOOTH_OFF -> "BluetoothがOFFです"
    SwitchFailure.NOT_ALLOWED -> "機器の許可を確認してください"
    SwitchFailure.API_REJECTED -> "操作を開始できませんでした"
    SwitchFailure.TIMEOUT -> "接続を確認できませんでした"
    SwitchFailure.CLOSED -> "操作を中止しました"
}
