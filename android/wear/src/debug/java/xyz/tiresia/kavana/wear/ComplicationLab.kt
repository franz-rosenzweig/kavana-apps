package xyz.tiresia.kavana.wear

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Debug builds only: the complication for a made-up snapshot, drawn by the renderer watch faces use (the round ranged one and the
 * short text one, side by side), with what each says in words under it for a check to read:
 *   adb shell am start -n xyz.tiresia.kavana.debug/xyz.tiresia.kavana.wear.ComplicationLab --es json '{"mode":"READY","catName":"Miso"}'
 */
class ComplicationLab : Activity() {
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = intent.getStringExtra("json") ?: return finish()
        val snap = Json { ignoreUnknownKeys = true }.decodeFromString(WatchSnap.serializer(), json)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        col.addView(row)
        for (type in listOf(ComplicationType.RANGED_VALUE, ComplicationType.SHORT_TEXT)) {
            val data = FocusComplication.data(type, snap) ?: continue
            row.addView(Face(this, data), LinearLayout.LayoutParams(px(64), px(64)).apply { marginStart = px(6); marginEnd = px(6) })
            col.addView(TextView(this).apply {
                text = "${type.name.lowercase()}: ${words(data)}"
                setTextColor(Color.GRAY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.CENTER
            })
        }
        setContentView(col)
    }

    private fun words(d: ComplicationData): String {
        val now = Instant.now()
        return when (d) {
            is RangedValueComplicationData -> "${d.text?.getTextAt(resources, now)} ${d.title?.getTextAt(resources, now)} (${d.value.toInt()} of ${d.max.toInt()})"
            is ShortTextComplicationData -> "${d.text.getTextAt(resources, now)} ${d.title?.getTextAt(resources, now)}"
            else -> d.type.name
        }
    }

    /** One complication, as a watch face would draw it. */
    private class Face(ctx: Context, data: ComplicationData) : View(ctx) {
        private val drawable = ComplicationDrawable(ctx).apply {
            setComplicationData(data, false)
            activeStyle.textColor = Color.WHITE
            activeStyle.titleColor = Color.LTGRAY
            activeStyle.rangedValuePrimaryColor = 0xFF5FD0C0.toInt()
            activeStyle.borderColor = 0xFF5FD0C0.toInt()
        }

        override fun onDraw(c: Canvas) {
            drawable.setBounds(0, 0, width, height)
            drawable.currentTime = Instant.now()
            drawable.draw(c)
        }
    }
}
