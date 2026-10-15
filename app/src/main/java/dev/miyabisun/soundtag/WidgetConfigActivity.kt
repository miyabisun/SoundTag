package dev.miyabisun.soundtag

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The OS widget configuration screen: picks the one target of a widget. Leaving without a choice
 * cancels placement; with no allowed speaker only the way into SoundTag is offered.
 */
class WidgetConfigActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Exported for the launcher, so only our own widgets can be configured here.
        if (!SpeakerWidget.owns(this, id)) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) render()
    }

    private fun render() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.background))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(content)
        })
        val choices = widgetItems(SpeakerWidget.snapshot(this))
        if (choices.isEmpty()) {
            card(content, R.drawable.ic_soundtag, getString(R.string.widget_open), getString(R.string.widget_open), false) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            return
        }
        val current = WidgetTargets(this).target(id)
        for (item in choices) {
            val (icon, name, description) = when (item) {
                is WidgetItem.Device -> Triple(connectorIcon(item.speaker.connected), item.speaker.name,
                    connectionDescription(item.speaker))
                WidgetItem.DisconnectAll -> getString(R.string.disconnect_all).let { Triple(R.drawable.ic_phone, it, it) }
            }
            card(content, icon, name, description, item.command == current) {
                WidgetTargets(this).save(id, item.command, name)
                SpeakerWidget.update(this, intArrayOf(id))
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        }
    }

    /** The same card as the settings screen header, so the choice looks like the device it names. */
    private fun card(parent: LinearLayout, icon: Int, name: String, description: String, selected: Boolean,
                     action: () -> Unit) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(20), dp(12), dp(12), dp(12))
            contentDescription = description
            if (selected) stateDescription = "選択中"
            background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.border)), GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (selected) 2 else 1), getColor(if (selected) R.color.accent else R.color.border))
            }, null)
            setOnClickListener { action() }
        }
        panel.addView(ImageView(this).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        panel.addView(TextView(this).apply {
            text = name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text))
            setPadding(dp(12), dp(8), dp(8), dp(8))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
