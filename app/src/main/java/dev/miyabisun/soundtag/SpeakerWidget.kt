package dev.miyabisun.soundtag

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

internal sealed interface WidgetItem {
    val command: TagCommand

    data class Device(val speaker: Speaker) : WidgetItem {
        override val command get() = TagCommand.Connect(speaker.address)
    }
    data object DisconnectAll : WidgetItem {
        override val command get() = TagCommand.Phone
    }
}

/** An empty list means the widget can only open SoundTag (no permission or no allowed speaker). */
internal fun widgetItems(state: SettingsSnapshot): List<WidgetItem> {
    val allowed = state.speakers.filter { it.allowed }
    if (!state.permission || allowed.isEmpty()) return emptyList()
    return allowed.map { WidgetItem.Device(it.speaker) } + WidgetItem.DisconnectAll
}

/** Taps send the same URI as a tag to NfcService; the widget never opens SoundTag for a switch. */
class SpeakerWidget : AppWidgetProvider() {
    companion object {
        // Instrumentation supplies its fake here; there is no user-facing mock mode.
        internal var accessFactory: ((Context) -> SettingsAccess)? = null

        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, SpeakerWidget::class.java))
            if (ids.isEmpty()) return
            val items = try {
                widgetItems(SettingsController(accessFactory?.invoke(context) ?: AndroidSettings(context)).snapshot())
            } catch (_: RuntimeException) { emptyList() }
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setViewVisibility(R.id.list, if (items.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            views.setViewVisibility(R.id.open, if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
            views.setOnClickPendingIntent(R.id.open, PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val collection = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
            items.forEachIndexed { index, item -> collection.addItem(index.toLong(), row(context, item)) }
            views.setRemoteAdapter(R.id.list, collection.build())
            // Mutable only so each row can fill in its URI; the explicit service component cannot change.
            views.setPendingIntentTemplate(R.id.list, PendingIntent.getForegroundService(context, 0,
                Intent(context, NfcService::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            manager.updateAppWidget(ids, views)
        }

        private fun row(context: Context, item: WidgetItem) = RemoteViews(context.packageName, R.layout.widget_item).apply {
            when (item) {
                is WidgetItem.Device -> {
                    setTextViewText(R.id.name, item.speaker.name)
                    setImageViewResource(R.id.icon, connectorIcon(item.speaker.connected))
                    setContentDescription(R.id.item, connectionDescription(item.speaker))
                }
                WidgetItem.DisconnectAll -> {
                    setTextViewText(R.id.name, context.getString(R.string.disconnect_all))
                    setImageViewResource(R.id.icon, R.drawable.ic_phone)
                    setContentDescription(R.id.item, context.getString(R.string.disconnect_all))
                }
            }
            setOnClickFillInIntent(R.id.item, Intent().setData(Uri.parse(item.command.uri())))
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            // The broadcast only triggers a re-read of Android's current state.
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> update(context)
        }
    }
}

internal fun connectorIcon(connected: Boolean) = if (connected) R.drawable.ic_link_on else R.drawable.ic_link_off
internal fun connectionDescription(speaker: Speaker) = "${speaker.name}、${if (speaker.connected) "接続中" else "未接続"}"
