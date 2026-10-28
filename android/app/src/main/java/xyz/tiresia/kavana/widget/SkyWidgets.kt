package xyz.tiresia.kavana.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.background
import androidx.glance.Image
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** The two sky widgets: the month as a constellation (4 x 2, or the last two weeks small), and the week as weather. */

internal fun ordinal(n: Int) = "$n" + if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

// =============================== Constellation ===============================

internal class ConstellationWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = ConstellationContent(s)
}

class ConstellationWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConstellationWidget()
}

@Composable
fun ConstellationContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val now = clockNow(s)
    val small = size.width < 250.dp
    val bmp = remember(s.stars, s.days, s.streak, size, now / 3_600_000L) { PlayArt.constellation(ctx, s, size.width.value, size.height.value, small, now) }
    val white = Color(0xFFF2EDE6)
    val lav = Color(0xFFA99BE8)
    val dim = Color(0xFFB7B0D6)
    val amber = Color(0xFFFFCF8A)
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bmp)).clickable(OPEN_APP)) {
        if (small) {
            Box(GlanceModifier.fillMaxSize().padding(start = 16.dp, top = 14.dp)) {
                Text(if (s.streak == 0) "No streak yet" else if (s.streak == 1) "1 day" else "${s.streak} days", style = TextStyle(color = ColorProvider(white), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Box(GlanceModifier.fillMaxSize().padding(start = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomStart) {
                val lit = s.days.count { it.min > 0 }
                Text(if (lit == 1) "1 star lit" else "$lit stars lit", style = TextStyle(color = ColorProvider(dim), fontSize = 11.5.sp), maxLines = 1)
            }
        } else {
            val n = s.stars.size
            Column(GlanceModifier.padding(start = 18.dp, top = 14.dp)) {
                Text(s.month.uppercase(Locale.getDefault()), style = TextStyle(color = ColorProvider(lav), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(if (n == 0) "No stars yet" else "$n ${if (n == 1) "block" else "blocks"}, $n ${if (n == 1) "star" else "stars"}", style = TextStyle(color = ColorProvider(white), fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
            Box(GlanceModifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.BottomStart) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(brightest(s) ?: "Bring a kitten home to light one", style = TextStyle(color = ColorProvider(dim), fontSize = 11.5.sp), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                    if (s.streak > 0) Text(if (s.streak == 1) "1 day in a row" else "${s.streak} days in a row", style = TextStyle(color = ColorProvider(amber), fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
            }
        }
    }
}

/** "Brightest: Tuesday the 21st": the day this month with the most minutes. */
private fun brightest(s: Snap): String? {
    if (s.stars.isEmpty()) return null
    val best = s.stars.groupBy { Calendar.getInstance().apply { timeInMillis = it.t }.get(Calendar.DAY_OF_MONTH) }.maxByOrNull { (_, v) -> v.sumOf { it.min } }!!
    val day = Calendar.getInstance().apply { timeInMillis = best.value.first().t }
    return "Brightest: ${SimpleDateFormat("EEEE", Locale.getDefault()).format(day.time)} the ${ordinal(best.key)}"
}

// =============================== The week, as weather ===============================

internal class WeatherWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = WeatherContent(s)
}

class WeatherWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherWidget()
}

@Composable
fun WeatherContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val dark = isDark(ctx)
    val now = clockNow(s)
    val theme = s.theme
    val light = WidgetArt.lightInk(theme, dark)
    val ink = if (light) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (light) Color(0xFFB7AEA5) else Color(0xFF6B6158)
    val teal = if (light) Color(0xFF8FE3D6) else Color(0xFF1F6B61)
    val days = s.days.takeLast(7)
    val bg = remember(theme, dark, size) { WidgetArt.wall(ctx, size.width.value, size.height.value, theme, dark) }
    val padX = 16f
    val cell = (size.width.value - 2 * padX - 6 * 7f) / 7f
    val stripH = (size.height.value - 14f - 22f - 8f - 8f - 16f - 12f).coerceAtLeast(40f)
    val strip = remember(s.days, s.portrait, s.nap, size, now / 3_600_000L, dark) { PlayArt.week(ctx, s, size.width.value - 2 * padX, stripH, now, Color(95, 208, 192, 180)) }
    val best = days.filter { it.min > 0 }.maxByOrNull { it.min }
    val names = days.map { SimpleDateFormat("EEE", Locale.getDefault()).format(java.util.Date(it.day)) }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bg)).clickable(OPEN_APP)) {
        Column(GlanceModifier.fillMaxSize().padding(start = padX.dp, end = padX.dp, top = 14.dp, bottom = 12.dp)) {
            Row(GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("This week, as weather", style = TextStyle(color = ColorProvider(ink), fontSize = 16.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                Text(if (best != null) "best: ${SimpleDateFormat("EEEE", Locale.getDefault()).format(java.util.Date(best.day))}" else "no blocks yet", style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(8.dp))
            Image(ImageProvider(strip), "The last seven days", GlanceModifier.fillMaxWidth().height(stripH.dp))
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) { // seven children: a Glance row holds ten at most, so the gaps are in the labels' widths
                days.forEachIndexed { i, _ ->
                    val today = i == days.lastIndex
                    Text(if (today) "Today" else names[i], style = TextStyle(color = ColorProvider(if (today) teal else muted), fontSize = 11.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center), modifier = GlanceModifier.width((if (today) cell else cell + 7f).dp), maxLines = 1)
                }
            }
        }
    }
}
