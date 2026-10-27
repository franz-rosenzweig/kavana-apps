package xyz.tiresia.kavana.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.fmtMin
import java.util.Calendar

/** The plain cards' colours (the Today, Later and Sound widgets): cream by day, the dark café's card at night. */
class CardPal(val dark: Boolean) {
    val bg = if (dark) Color(0xF0262229) else Color(0xFFFFFCF7)
    val ink = if (dark) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (dark) Color(0xFFB7AEA5) else Color(0xFF6B6158)
    val faint = if (dark) Color(0xFF8C837B) else Color(0xFFB9AEA3)
    val line = if (dark) Color(255, 255, 255, 20) else Color(43, 33, 26, 15)
    val tint = if (dark) Color(0xFF5FD0C0) else Color(0xFF2C8A7E)
    val onTint = if (dark) Color(0xFF10302B) else Color.White
    val soft = if (dark) Color(95, 208, 192, 46) else Color(44, 138, 126, 36)
    val onSoft = if (dark) Color(0xFF8FE3D6) else Color(0xFF1F6B61)
    val chip = if (dark) Color(255, 255, 255, 20) else Color(43, 33, 26, 20)
    val orange = Color(0xFFE98A3C)
    val onOrange = Color(0xFF3A1D05)
    val track = if (dark) Color(255, 255, 255, 30) else Color(0xFFEFE6DA)
}

private fun isDark(ctx: Context) = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private val OPEN_APP: Action @Composable get() = actionStartActivity<MainActivity>()

// =============================== Today ===============================

/** Today: the goal ring, the streak with its paw prints, and the missions, with a Collect button when a reward's waiting. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.demo(context, wid) ?: WidgetStore.get(context) }
            TodayContent(snap)
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/** "1h 20" for 80 minutes, "45m" for 45. */
private fun compact(m: Int) = if (m >= 60) "${m / 60}h ${(m % 60).toString().padStart(2, '0')}" else "${m}m"

@Composable
fun TodayContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg) }
    val narrow = size.width < 300.dp
    val d = minOf(size.height - 20.dp, if (narrow) 96.dp else 96.dp)
    val missionsW = if (narrow) 104.dp else 94.dp
    val streakW = size.width - 28.dp - d - 10.dp - missionsW - 6.dp // what's left between the ring and the missions
    val paws = if (narrow) 0 else minOf(5, (streakW.value / 18f).toInt())
    val goal = if (s.goalMin > 0) s.goalMin else 120
    val ring = remember(s.todayMin, goal, d, pal.dark) { WidgetArt.ring(ctx, d.value, (s.todayMin / goal.toFloat()).coerceIn(0f, 1f), pal.track, pal.tint, 10f) }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(OPEN_APP)) {
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(d), contentAlignment = Alignment.Center) {
                Image(ImageProvider(ring), "Today's focus: ${s.todayMin} minutes", GlanceModifier.size(d))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(compact(s.todayMin), style = TextStyle(color = ColorProvider(pal.ink), fontSize = 23.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(if (s.goalMin > 0) "of ${fmtMin(s.goalMin)}" else "today", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 11.sp), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            if (!narrow) {
                Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.streak == 1) "1 day" else "${s.streak} days", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text("in a row", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 11.5.sp), maxLines = 1)
                    if (paws > 0) {
                        Spacer(GlanceModifier.height(8.dp))
                        val days = remember(s.streak, paws) { lastDays(paws) }
                        Row {
                            days.forEachIndexed { i, _ ->
                                val lit = i >= paws - minOf(s.streak, paws)
                                Image(ImageProvider(R.drawable.w_paw), null, GlanceModifier.size(16.dp).padding(end = 1.dp), colorFilter = ColorFilter.tint(ColorProvider(if (lit) pal.tint else pal.track)))
                            }
                        }
                        Row { days.forEach { Text(it, style = TextStyle(color = ColorProvider(pal.faint), fontSize = 10.5.sp), modifier = GlanceModifier.width(17.dp), maxLines = 1) } }
                    }
                }
                Spacer(GlanceModifier.width(6.dp))
            }
            Column(if (narrow) GlanceModifier.defaultWeight().fillMaxHeight() else GlanceModifier.width(missionsW).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text("${s.missionsDone} of ${s.missionsTotal}", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text("missions done", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 11.5.sp), maxLines = 1)
                Spacer(GlanceModifier.height(8.dp))
                Row {
                    for (i in 0 until s.missionsTotal) {
                        if (i < s.missionsDone) Box(GlanceModifier.size(20.dp).background(ColorProvider(pal.tint)).cornerRadius(10.dp), contentAlignment = Alignment.Center) {
                            Image(ImageProvider(R.drawable.w_check), null, GlanceModifier.size(10.dp), colorFilter = ColorFilter.tint(ColorProvider(pal.onTint)))
                        } else Box(GlanceModifier.size(20.dp).background(ColorProvider(pal.track)).cornerRadius(10.dp), contentAlignment = Alignment.Center) {
                            Box(GlanceModifier.size(14.dp).background(ColorProvider(pal.bg)).cornerRadius(7.dp)) {}
                        }
                        Spacer(GlanceModifier.width(5.dp))
                    }
                }
                if (s.collectTotal > 0) {
                    Spacer(GlanceModifier.height(8.dp))
                    Pill("Collect +${s.collectTotal}", pal.orange, pal.onOrange, actionRunCallback<CollectAction>(), GlanceModifier.fillMaxWidth(), height = 34.dp, textSize = 13.sp)
                }
            }
        }
    }
}

/** The initials of the last `n` days, today last (M T W T F). */
private fun lastDays(n: Int): List<String> {
    val c = Calendar.getInstance()
    return (n - 1 downTo 0).map { back ->
        val d = Calendar.getInstance().apply { timeInMillis = c.timeInMillis; add(Calendar.DAY_OF_YEAR, -back) }
        "SMTWTFS"[d.get(Calendar.DAY_OF_WEEK) - 1].toString()
    }
}

// =============================== Later ===============================

/** The Later list: tick things off, start a block with one, park a thought. */
class LaterWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.demo(context, wid) ?: WidgetStore.get(context) }
            LaterContent(snap)
        }
    }
}

class LaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LaterWidget()
}

@Composable
fun LaterContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg) }
    val hide = ctx.getSharedPreferences("kavana", Context.MODE_PRIVATE).getBoolean("hideTitles", false)
    val rows = ((size.height.value - 50f) / 41f).toInt().coerceIn(1, 5)
    val shown = s.later.take(rows)
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card))) {
        Column(GlanceModifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
            Row(GlanceModifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Later", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 17.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()), maxLines = 1)
                Spacer(GlanceModifier.width(8.dp))
                Text(when (s.later.size) { 0 -> "nothing yet"; 1 -> "1 thing"; else -> "${s.later.size} things" }, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Pill("Park a thought", pal.soft, pal.onSoft, actionStartActivity<ParkActivity>(), icon = R.drawable.w_plus, height = 34.dp, textSize = 13.sp)
            }
            if (shown.isEmpty()) {
                Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                    Text("Nothing parked. A thought that pulls at you goes here.", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 13.sp), maxLines = 2)
                }
            }
            shown.forEach { item ->
                Box(GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(pal.line))) {}
                Row(GlanceModifier.fillMaxWidth().height(41.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.size(32.dp).clickable(actionRunCallback<DoneLaterAction>(actionParametersOf(WidgetKeys.ID to item.id))), contentAlignment = Alignment.CenterStart) {
                        Box(GlanceModifier.size(22.dp).background(ColorProvider(pal.faint)).cornerRadius(11.dp), contentAlignment = Alignment.Center) {
                            Box(GlanceModifier.size(18.dp).background(ColorProvider(pal.bg)).cornerRadius(9.dp)) {}
                        }
                    }
                    Spacer(GlanceModifier.width(6.dp))
                    Text(if (hide) "A thought for later" else item.text, style = TextStyle(color = ColorProvider(pal.ink), fontSize = 14.sp), modifier = GlanceModifier.defaultWeight(), maxLines = 1)
                    Round(R.drawable.w_play, "Start a block with this", pal.chip, pal.muted, actionRunCallback<StartLaterAction>(actionParametersOf(WidgetKeys.ID to item.id, WidgetKeys.TEXT to item.text)), 34.dp)
                }
            }
        }
    }
}

// =============================== Sound ===============================

/** The sound mini-player: the mix with its volumes, and play and pause. */
class SoundWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.demo(context, wid) ?: WidgetStore.get(context) }
            SoundContent(snap)
        }
    }
}

class SoundWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SoundWidget()
}

@Composable
fun SoundContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg) }
    val running = s.block != null && s.block.paused == null
    val soundsSheet = actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("sounds", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    val tile = minOf(size.height - 20.dp, 52.dp)
    val barW = size.width - 14.dp - tile - 12.dp - 64.dp - 12.dp - tile - 14.dp
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card))) {
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(tile).background(ColorProvider(pal.soft)).cornerRadius(tile / 2).clickable(soundsSheet), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.w_sound), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(ColorProvider(pal.onSoft)))
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().clickable(soundsSheet), verticalAlignment = Alignment.CenterVertically) {
                if (s.mix.isEmpty()) {
                    Text("Pick a sound", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text("Rain, a purr, a café…", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                } else s.mix.forEachIndexed { i, m ->
                    if (i > 0) Spacer(GlanceModifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, style = TextStyle(color = ColorProvider(if (s.muted) pal.faint else pal.ink), fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.width(64.dp), maxLines = 1)
                        val bar = remember(barW, m.vol, s.muted, pal.dark) { WidgetArt.bar(ctx, maxOf(barW.value, 40f), 5f, m.vol, pal.track, if (s.muted) pal.faint else pal.tint) }
                        Image(ImageProvider(bar), "${m.name} volume", GlanceModifier.height(5.dp).defaultWeight())
                    }
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            if (s.mix.isNotEmpty() && running) Box(GlanceModifier.size(tile).background(ColorProvider(if (s.muted) pal.chip else pal.tint)).cornerRadius(tile / 2)
                .clickable(actionRunCallback<SoundToggleAction>()), contentAlignment = Alignment.Center) {
                Image(ImageProvider(if (s.muted) R.drawable.w_play else R.drawable.w_pause), if (s.muted) "Play the sound" else "Pause the sound", GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(ColorProvider(if (s.muted) pal.ink else pal.onTint)))
            } else Box(GlanceModifier.size(tile).background(ColorProvider(pal.chip)).cornerRadius(tile / 2).clickable(soundsSheet), contentAlignment = Alignment.Center) {
                Image(ImageProvider(R.drawable.w_play), "Choose sounds", GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(ColorProvider(pal.faint)))
            }
        }
    }
}
