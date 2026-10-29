package xyz.tiresia.kavana.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.MainActivity
import androidx.glance.appwidget.action.actionStartActivity

/**
 * Quick start: your cat and four buttons, 15, 25, 50 minutes and ∞ (a stopwatch), on a slim strip. While a
 * block is running it becomes the Window's strip (the clock, the title, Pause), so one widget does both.
 */
class QuickStartWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            val now = remember(tick) { System.currentTimeMillis() }
            QuickContent(snap, now)
        }
    }
}

class QuickStartWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickStartWidget()
}

/** Your cat, as a square picture `sizeDp` wide. */
fun catPicture(ctx: Context, path: String, sizeDp: Float): Bitmap? {
    val px = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    return Art.bitmap(ctx, path)?.let { Bitmap.createScaledBitmap(it, px, px, true) }
}

@Composable
fun QuickContent(s: Snap, now: Long) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val pal = Pal(dark, "classic") // this one is a plain card, not the café wall
    val mode = s.mode()
    val card = remember(size, dark) { WidgetArt.card(ctx, size.width.value, size.height.value, if (dark) Color(0xF0262229) else Color(0xEBFFFCF7)) }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(actionStartActivity<MainActivity>())) {
        if (mode == WMode.READY) {
            val d = minOf(size.height - 12.dp, 64.dp)
            val bh = minOf(size.height - 20.dp, 52.dp)
            val cat = if (s.catsOn) remember(s.portrait, d) { catPicture(ctx, s.portrait, d.value) } else null
            Row(GlanceModifier.fillMaxSize().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (cat != null) { Image(ImageProvider(cat), "${s.catName}", GlanceModifier.size(d)); Spacer(GlanceModifier.width(10.dp)) }
                Pill("15", pal.chip, pal.ink, startAction(15), GlanceModifier.defaultWeight(), height = bh, textSize = 16.sp)
                Spacer(GlanceModifier.width(8.dp))
                Pill("25", pal.tint, pal.onTint, startAction(25), GlanceModifier.defaultWeight(), height = bh, textSize = 16.sp)
                Spacer(GlanceModifier.width(8.dp))
                Pill("50", pal.chip, pal.ink, startAction(50), GlanceModifier.defaultWeight(), height = bh, textSize = 16.sp)
                Spacer(GlanceModifier.width(8.dp))
                Pill("∞", pal.chip, pal.ink, startAction(-1), GlanceModifier.defaultWeight(), height = bh, textSize = 20.sp)
            }
        } else {
            StripFor(ctx, s, now, mode, pal, size)
        }
    }
}
