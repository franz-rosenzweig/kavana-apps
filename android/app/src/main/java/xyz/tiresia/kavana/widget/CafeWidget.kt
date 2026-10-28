package xyz.tiresia.kavana.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.fmtMin
import xyz.tiresia.kavana.ui.Cafe3D
import xyz.tiresia.kavana.ui.cafeHash
import xyz.tiresia.kavana.ui.cafeScene

/**
 * Your café on the home screen: today's cats by day (light mode), the month's cats at night (dark mode). The picture is the app's
 * own 3D café, drawn once into a file whenever what's in it changes (so the widget only ever loads a picture), then shown with
 * what happened: cats home, time focused, treats.
 */
object CafeRender {
    // big enough for a tablet's 6 x 4 without going soft; a phone shows it smaller
    const val W_DP = 460f
    const val H_DP = 340f
    private val gate = Mutex()

    fun file(ctx: Context, night: Boolean) = File(ctx.filesDir, if (night) "cafe-night.webp" else "cafe-day.webp")

    private fun used(ctx: Context): Boolean = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, CafeWidgetReceiver::class.java)).isNotEmpty()

    /** The app's state changed: draw the two pictures again if what they show is different (nothing, if there's no café widget). */
    fun update(ctx: Context, st: UiState, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!force && !used(app)) return
        val cal = Calendar.getInstance().apply { timeInMillis = st.now }
        val today0 = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val month0 = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val prefs = KavanaApp.repo.prefs
        for (night in listOf(false, true)) {
            val list = st.history.filter { it.start >= if (night) month0 else today0 }
            val sig = listOf(night, W_DP, H_DP, st.view.settings.theme, st.shop.owned.toList().sorted(), list.map { it.id + it.kitten + Art.portrait(it.cat) }, st.catsOn).toString().hashCode().toString()
            if (prefs.getString("cafeSig$night", null) == sig && file(app, night).exists()) continue
            KavanaApp.repo.scope.launch(Dispatchers.Default) {
                gate.withLock {
                    runCatching {
                        val bmp = render(app, st, list, night)
                        file(app, night).outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
                        prefs.edit().putString("cafeSig$night", sig).apply()
                    }
                }
                withContext(Dispatchers.Main) { WidgetUpdater.refresh(app) }
            }
        }
    }

    private fun render(ctx: Context, st: UiState, list: List<xyz.tiresia.kavana.Hist>, night: Boolean): Bitmap {
        val dens = ctx.resources.displayMetrics.density
        val w = (W_DP * dens).toInt()
        val h = (H_DP * dens).toInt()
        val engine = Cafe3D(ctx, dens, true)
        engine.margin = 2.0
        engine.show(cafeScene(st, list, if (night) "month" else "day", cafeHash("widget$night"), night, quiet = true).copy(light = if (night) "night" else "day", dim = night))
        engine.resize(w.toFloat(), h.toFloat())
        val until = System.currentTimeMillis() + 4_000L
        while (!engine.artReady() && System.currentTimeMillis() < until) Thread.sleep(40) // the paintings load in the background
        repeat(40) { engine.step(0.05) } // let the cats settle where they sit
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { engine.draw(Canvas(it)) }
    }
}

internal class CafeWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = CafeContent(s)
}

class CafeWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CafeWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        KavanaApp.repo.scope.launch { // a café widget just added: draw its pictures if the app hasn't yet
            val repo = KavanaApp.repo
            (repo.state.value ?: repo.act(xyz.tiresia.kavana.op("tick"))?.state)?.let { CafeRender.update(context, it, force = true) }
        }
    }
}

@Composable
fun CafeContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val night = isDark(ctx)
    val bg = if (night) Color(0xFF151317) else Color(0xFFFBF8F3)
    val ink = if (night) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (night) Color(0xFFA59C93) else Color(0xFF6B6158)
    val tile = if (night) Color(0xFF262229) else Color.White
    val soft = if (night) Color(95, 208, 192, 41) else Color(44, 138, 126, 31)
    val onSoft = if (night) Color(0xFF8FE3D6) else Color(0xFF1F6B61)
    val card = remember(size, night) { WidgetArt.card(ctx, size.width.value, size.height.value, bg, outline = if (night) Color(255, 255, 255, 18) else null) }
    val f = CafeRender.file(ctx, night)
    val pic = remember(f.lastModified(), night) { runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
    val big = size.height >= 220.dp
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(OPEN_APP)) {
        if (big) {
            Column(GlanceModifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
                Row(GlanceModifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (night) "${s.monthHome} ${if (s.monthHome == 1) "cat" else "cats"} in ${s.month}" else "Your café today", style = TextStyle(color = ColorProvider(ink), fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight().padding(start = 4.dp), maxLines = 1)
                    Pill("Open", soft, onSoft, OPEN_APP, height = 28.dp, textSize = 12.5.sp, pad = 12.dp)
                }
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(ImageProvider(pic), "Your café", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text("Drawing your café…", style = TextStyle(color = ColorProvider(muted), fontSize = 13.sp), maxLines = 1)
                }
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    val stats = if (night) listOf(hoursText(s.monthMin) to "focused", "${s.monthGone}" to "wandered off", "${s.monthTreats}" to "treats")
                    else listOf("${s.todayHome}" to "came home", hoursText(s.todayMin) to "focused", "${s.todayTreats}" to "treats earned")
                    stats.forEachIndexed { i, (v, l) ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        Column(GlanceModifier.defaultWeight().background(ColorProvider(tile)).cornerRadius(16.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                            Text(v, style = TextStyle(color = ColorProvider(ink), fontSize = 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Text(l, style = TextStyle(color = ColorProvider(muted), fontSize = 11.sp), maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Column(GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (pic != null) Image(ImageProvider(pic), "Your café", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Text(when {
                    night -> "${s.monthHome} this month"
                    s.todayHome == 0 -> "No cats home yet"
                    s.todayHome == 1 -> "1 cat home today"
                    else -> "${s.todayHome} cats home today"
                }, style = TextStyle(color = ColorProvider(ink), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
            }
        }
    }
}
