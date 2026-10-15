package dev.miyabisun.soundtag

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/** A target the widget can be bound to: one allowed speaker or disconnect-all. */
internal sealed interface WidgetItem {
    val command: TagCommand

    data class Device(val speaker: Speaker) : WidgetItem {
        override val command get() = TagCommand.Connect(speaker.address)
    }
    data object DisconnectAll : WidgetItem {
        override val command get() = TagCommand.Phone
    }
}

/** An empty list means no target can be chosen (no permission or no allowed speaker). */
internal fun widgetItems(state: SettingsSnapshot): List<WidgetItem> {
    val allowed = state.speakers.filter { it.allowed }
    if (!state.permission || allowed.isEmpty()) return emptyList()
    return allowed.map { WidgetItem.Device(it.speaker) } + WidgetItem.DisconnectAll
}

internal sealed interface WidgetTap {
    data class Send(val command: TagCommand) : WidgetTap
    data object OpenApp : WidgetTap
    data object Choose : WidgetTap
}

internal data class WidgetFace(val icon: Int, val name: String, val description: String,
                               val enabled: Boolean, val tap: WidgetTap)

/**
 * What one widget shows and does for its saved target. A target that can no longer be operated keeps
 * its name (falling back to the name saved when it was chosen) and opens SoundTag instead.
 */
internal fun widgetFace(target: TagCommand?, savedName: String?, state: SettingsSnapshot,
                        allName: String, chooseName: String): WidgetFace {
    val usable = target != null && widgetItems(state).any { it.command == target }
    fun face(icon: Int, name: String, description: String) = if (usable) {
        WidgetFace(icon, name, description, true, WidgetTap.Send(target!!))
    } else {
        WidgetFace(icon, name, "$name、使用できません", false, WidgetTap.OpenApp)
    }
    return when (target) {
        is TagCommand.Connect -> {
            val speaker = state.speakers.firstOrNull { it.speaker.address == target.address }?.speaker
            val name = speaker?.name ?: savedName ?: target.address
            face(connectorIcon(speaker?.connected == true), name,
                speaker?.let(::connectionDescription) ?: name)
        }
        TagCommand.Phone -> face(R.drawable.ic_phone, allName, allName)
        // Disconnect tags are only received for compatibility; a widget is never bound to one.
        is TagCommand.Disconnect, null -> WidgetFace(R.drawable.ic_soundtag, chooseName, chooseName, true, WidgetTap.Choose)
    }
}

/** Each widget's target, saved as the same URI a tag carries. */
internal class WidgetTargets(context: Context) {
    private val preferences = context.getSharedPreferences("widgets", Activity.MODE_PRIVATE)

    fun target(id: Int) = preferences.getString("$id", null)?.let(TagCommand::parse)
    fun name(id: Int) = preferences.getString("$id.name", null)
    fun save(id: Int, command: TagCommand, name: String) {
        preferences.edit().putString("$id", command.uri()).putString("$id.name", name).apply()
    }
    fun remove(ids: IntArray) {
        preferences.edit().apply { ids.forEach { remove("$it"); remove("$it.name") } }.apply()
    }
}

/** Taps send the same URI as a tag to NfcService; the widget never opens SoundTag for a switch. */
class SpeakerWidget : AppWidgetProvider() {
    companion object {
        internal const val NAME = "name"
        // Instrumentation supplies its fake here; there is no user-facing mock mode.
        internal var accessFactory: ((Context) -> SettingsAccess)? = null

        internal fun snapshot(context: Context) = try {
            SettingsController(accessFactory?.invoke(context) ?: AndroidSettings(context)).snapshot()
        } catch (_: RuntimeException) { SettingsSnapshot(false, false, emptyList()) }

        internal fun owns(context: Context, id: Int) =
            AppWidgetManager.getInstance(context)?.getAppWidgetInfo(id)?.provider == ComponentName(context, SpeakerWidget::class.java)

        fun update(context: Context, only: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = only ?: manager.getAppWidgetIds(ComponentName(context, SpeakerWidget::class.java))
            if (ids.isEmpty()) return
            val state = snapshot(context)
            val targets = WidgetTargets(context)
            for (id in ids) {
                val face = widgetFace(targets.target(id), targets.name(id), state,
                    context.getString(R.string.disconnect_all), context.getString(R.string.widget_choose))
                manager.updateAppWidget(id, views(context, id, face))
            }
        }

        private fun views(context: Context, id: Int, face: WidgetFace) = RemoteViews(context.packageName, R.layout.widget).apply {
            setImageViewResource(R.id.icon, face.icon)
            setInt(R.id.icon, "setImageAlpha", if (face.enabled) 255 else 128)
            setTextViewText(R.id.name, face.name)
            setColorStateList(R.id.name, "setTextColor", if (face.enabled) R.color.text else R.color.muted)
            setContentDescription(android.R.id.background, face.description)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            setOnClickPendingIntent(android.R.id.background, when (val tap = face.tap) {
                is WidgetTap.Send -> PendingIntent.getForegroundService(context, id,
                    Intent(context, NfcService::class.java).setData(Uri.parse(tap.command.uri())), flags)
                WidgetTap.OpenApp -> PendingIntent.getActivity(context, id,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
                WidgetTap.Choose -> PendingIntent.getActivity(context, id,
                    Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), flags)
            })
        }

        fun canPin(context: Context) =
            AppWidgetManager.getInstance(context)?.isRequestPinAppWidgetSupported == true

        /** Asks the launcher to place a widget already bound to [command]; no target choice follows. */
        fun pin(context: Context, command: TagCommand, name: String): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            // Mutable so the launcher can add the new widget id; the explicit component and data stay fixed.
            val placed = PendingIntent.getBroadcast(context, 0,
                Intent(context, WidgetPinned::class.java).setData(Uri.parse(command.uri()))
                    .putExtra(NAME, name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            return manager.requestPinAppWidget(ComponentName(context, SpeakerWidget::class.java), null, placed)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context, ids)

    override fun onDeleted(context: Context, ids: IntArray) = WidgetTargets(context).remove(ids)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // The broadcast only triggers a re-read of Android's current state.
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> update(context)
        }
    }
}

/** Not exported: only the launcher's success callback from [SpeakerWidget.pin] reaches it. */
class WidgetPinned : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val command = intent.dataString?.let(TagCommand::parse) ?: return
        // The launcher fills in the id, so only a currently offered target for our own widget is saved.
        if (!SpeakerWidget.owns(context, id) || widgetItems(SpeakerWidget.snapshot(context)).none { it.command == command }) return
        WidgetTargets(context).save(id, command, intent.getStringExtra(SpeakerWidget.NAME) ?: return)
        SpeakerWidget.update(context, intArrayOf(id))
    }
}

internal fun connectorIcon(connected: Boolean) = if (connected) R.drawable.ic_link_on else R.drawable.ic_link_off
internal fun connectionDescription(speaker: Speaker) = "${speaker.name}、${if (speaker.connected) "接続中" else "未接続"}"
