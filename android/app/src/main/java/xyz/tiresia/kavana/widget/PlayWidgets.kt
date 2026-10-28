package xyz.tiresia.kavana.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.op
import xyz.tiresia.kavana.R
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

/**
 * The playful widgets that live on the hour: Pet the cat (tap and she hops; tap again and she purrs) and A cat
 * with a day (the time, the date and what the cat is up to). After ten at night and before five, a cat is
 * asleep, and Boo (who only comes out late) is out, if you've met Boo.
 */

/** The time the widget goes by: the real one, or the Widget lab's made-up one. */
internal fun clockNow(s: Snap): Long = s.demo?.at?.takeIf { it != 0L } ?: System.currentTimeMillis()
internal fun hourOf(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
internal fun isNight(t: Long): Boolean = hourOf(t).let { it >= 22 || it < 5 }

val PET_MODE = intPreferencesKey("petMode")
val PET_AT = longPreferencesKey("petAt")

// =============================== Pet the cat ===============================

internal class PetCatWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val prefs = currentState<Preferences>()
            val tick = prefs[WidgetUpdater.TICK]
            val fresh = System.currentTimeMillis() - (prefs[PET_AT] ?: 0L) < 6_000L // a hop that never ended (the process died) isn't one
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            PetContent(snap, if (fresh) prefs[PET_MODE] ?: 0 else 0)
        }
    }
}

class PetCatWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PetCatWidget()
}

/**
 * A tap on the cat. A widget can't tell a press from a press and hold, so: one tap, she hops (a heart, a little buzz);
 * a second tap while she's still up, she purrs in her loaf (rings, hearts, a purr in the phone). Then she settles.
 */
class PetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val at = System.currentTimeMillis()
        var purr = false
        updateAppWidgetState(context, glanceId) { p ->
            purr = (p[PET_MODE] ?: 0) != 0 && at - (p[PET_AT] ?: 0L) < 2_500L
            p[PET_MODE] = if (purr) 2 else 1
            p[PET_AT] = at
        }
        PetCatWidget().update(context, glanceId)
        if (purr) Haptics.purr(context) else Haptics.hop(context)
        // She settles a moment later, from the app's own scope, and this returns now: a handler that waited would hold up the next tap,
        // and a quick second tap has to find her still up.
        KavanaApp.repo.scope.launch {
            delay(if (purr) 5_000L else 1_800L) // (a widget takes a moment to redraw, so the hop is on screen for a good part of this)
            var settled = false
            updateAppWidgetState(context, glanceId) { p -> if (p[PET_AT] == at) { p[PET_MODE] = 0; settled = true } } // untouched since: back to sitting
            if (settled) PetCatWidget().update(context, glanceId)
        }
    }
}

/** The phone's part of petting: a soft click for a hop, a long purr for a purr (not if touch feedback is off). */
object Haptics {
    private fun vibrator(ctx: Context): Vibrator? {
        if (Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return null
        return ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }
    }

    fun hop(ctx: Context) {
        vibrator(ctx)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /** Short pulses (about fifteen a second) that swell and fade over three and a half seconds. */
    fun purr(ctx: Context) {
        val v = vibrator(ctx) ?: return
        val n = 56
        val on = 26L
        val off = 36L
        val effect = if (v.hasAmplitudeControl()) {
            val times = LongArray(n * 2) { if (it % 2 == 0) on else off }
            val amps = IntArray(n * 2) { if (it % 2 == 0) (50 + 90 * sin(PI * (it / 2) / n)).toInt().coerceIn(1, 255) else 0 }
            VibrationEffect.createWaveform(times, amps, -1)
        } else VibrationEffect.createWaveform(LongArray(n * 2) { if (it % 2 == 0) on else off }, -1)
        v.vibrate(effect)
    }
}

@Composable
fun PetContent(s: Snap, stateMode: Int) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val now = clockNow(s)
    val night = isNight(now)
    val dark = isDark(ctx)
    val m = s.demo?.pet ?: stateMode
    val boo = night && s.boo
    val pose = when {
        night -> if (m == 2) PlayArt.Pose.DREAM else PlayArt.Pose.SLEEP
        m == 2 -> PlayArt.Pose.PURR
        m == 1 -> PlayArt.Pose.HOP
        else -> PlayArt.Pose.SIT
    }
    val theme = if (night) "night" else s.theme
    val ink = if (WidgetArt.lightInk(theme, dark || night)) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (WidgetArt.lightInk(theme, dark || night)) Color(0xFFB7AEA5) else Color(0xFF5D5249)
    val bmp = remember(s.portrait, s.nap, s.loaf, s.booNap, s.theme, s.catsOn, size, pose, dark, boo, night) { PlayArt.pet(ctx, s, size.width.value, size.height.value, pose, dark, boo, night) }
    val strip = size.height < 100.dp
    val wide = !strip && size.width >= 250.dp
    val pet = actionRunCallback<PetAction>()
    val mode = s.mode()
    val name = if (boo) "Boo" else s.catName
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bmp)).clickable(OPEN_APP)) {
        when {
            !s.catsOn -> Box(GlanceModifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Cats are switched off in Chataltul", style = TextStyle(color = ColorProvider(ink), fontSize = 13.sp, textAlign = TextAlign.Center))
            }
            strip -> Row(GlanceModifier.fillMaxSize().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.width(104.dp).fillMaxHeight().clickable(pet)) {}
                Column(GlanceModifier.defaultWeight()) {
                    Bubble(when {
                        night -> if (m == 2) "Prrr…" else "Shh. $name is asleep."
                        m == 1 -> "Mrrp!"
                        m == 2 -> "Prrrr…"
                        mode == WMode.READY -> "Boo! Ready when you are."
                        mode == WMode.HOME -> "${s.catName} came home!"
                        mode == WMode.PAUSED -> "Taking a breather."
                        else -> "Shh. ${s.catName} is growing."
                    }, lines = 2)
                }
                if (mode == WMode.READY) {
                    Spacer(GlanceModifier.width(8.dp))
                    Pill("Start 25", Color(0xFF2C8A7E), Color.White, startAction(25), height = 34.dp, textSize = 13.sp, pad = 16.dp)
                }
            }
            wide -> Row(GlanceModifier.fillMaxSize().padding(end = 18.dp)) {
                Box(GlanceModifier.defaultWeight().fillMaxHeight().clickable(pet)) {
                    Box(GlanceModifier.padding(start = ((36f + 128f) * size.height.value / 176f + 6f).dp, top = (size.height.value * .1f).dp)) {
                        Bubble(when { night -> if (m == 2) "Prrr…" else "Shh."; m == 1 -> "Mrrp!"; m == 2 -> "Prrrr…"; else -> "Mrrp?" })
                    }
                }
                val k = (size.height.value / 150f).coerceIn(.7f, 1.1f)
                Column(GlanceModifier.width((size.width.value * .34f).dp).fillMaxHeight().padding(vertical = (14f * k).dp), horizontalAlignment = Alignment.End) {
                    val live = mode != WMode.READY && s.block != null
                    Text(when { live -> if (mode == WMode.PAUSED) "PAUSED" else "FOCUSING"; else -> "TODAY" }, style = TextStyle(color = ColorProvider(if (night || WidgetArt.lightInk(theme, dark)) Color(0xFF8FE3D6) else Color(0xFF1F6B61)), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
                    if (live) Chrono(ctx, s.block!!, System.currentTimeMillis(), 24f * k, ink)
                    else Text(hoursText(s.todayMin), style = TextStyle(color = ColorProvider(ink), fontSize = (26f * k).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
                    if (size.height >= 112.dp) Text(when (s.todayHome) { 0 -> "No cats home yet"; 1 -> "1 cat came home"; else -> "${s.todayHome} cats came home" },
                        style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp, textAlign = TextAlign.End), modifier = GlanceModifier.fillMaxWidth(), maxLines = 1)
                    Spacer(GlanceModifier.defaultWeight())
                    if (mode == WMode.READY) Pill("Start 25", Color(0xFF2C8A7E), Color.White, startAction(25), GlanceModifier.fillMaxWidth(), height = (38f * k).dp, textSize = 14.sp)
                }
            }
            else -> Box(GlanceModifier.fillMaxSize().clickable(pet)) {}
        }
    }
}

/** "2 h 10" for 130 minutes, "45 min" for 45. */
internal fun hoursText(m: Int) = if (m >= 60) "${m / 60} h ${(m % 60).toString().padStart(2, '0')}" else "$m min"

/** The cat's words, in a white bubble. */
@Composable
private fun Bubble(text: String, lines: Int = 1) {
    Box(GlanceModifier.background(ColorProvider(Color(0xF2FFFFFF))).cornerRadius(13.dp).padding(horizontal = 11.dp, vertical = 5.dp)) {
        Text(text, style = TextStyle(color = ColorProvider(Color(0xFF2B211A)), fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = lines)
    }
}

// =============================== A cat with a day ===============================

internal class ClockWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = ClockContent(s)
}

class ClockWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClockWidget()
}

@Composable
fun ClockContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val now = clockNow(s)
    val hr = hourOf(now)
    val night = isNight(now)
    val dark = isDark(ctx)
    val boo = night && s.boo
    val theme = if (night) "night" else s.theme
    val light = WidgetArt.lightInk(theme, dark || night)
    val ink = if (light) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val dateInk = if (light) Color(0xFFCFC6BD) else Color(0xFF5D5249)
    val lineInk = if (light) Color(0xFF9D94AB) else Color(0xFF6B6158)
    val eyebrowInk = if (night) Color(0xFFA99BE8) else if (light) Color(0xFF8FE3D6) else Color(0xFF1F6B61)
    val mode = s.mode()
    val name = s.catName
    val eyebrow = when { boo -> "BOO IS OUT"; hr in 5..11 -> "GOOD MORNING"; hr in 12..16 -> "GOOD AFTERNOON"; hr in 17..21 -> "GOOD EVENING"; else -> "GOOD NIGHT" }
    val line = when {
        mode == WMode.FOCUSING || mode == WMode.EXTRA -> "$name is growing. Stay with it."
        mode == WMode.PAUSED -> "Taking a breather. $name waits."
        mode == WMode.CHECKIN -> "$name wants to know: still on it?"
        mode == WMode.HOME -> "$name came home."
        mode == WMode.STOPPED || mode == WMode.GONE -> "$name wandered off."
        night -> "$name is asleep on the cushion. Shh."
        hr in 5..11 -> "Good morning. $name is waiting by the window."
        hr in 12..16 -> "$name is on the cushion, waiting for you."
        else -> "The café is quiet. $name is curled up."
    }
    val bg = remember(theme, dark, night, size) { WidgetArt.wall(ctx, size.width.value, size.height.value, theme, dark || night) }
    val k = (size.height.value / 150f).coerceIn(.7f, 1.1f)
    val catSize = minOf(118f, size.height.value - 22f)
    val faceSize = minOf(96f, size.height.value - 30f)
    val timeSp = 46f * k
    val lines = if (size.height >= 140.dp) 2 else 1
    val catPath = when { boo -> s.booArt; night -> s.nap; mode == WMode.READY || mode == WMode.BREAK -> s.portrait; else -> s.portrait }
    val cat = remember(catPath, catSize, boo, s.catsOn) { if (s.catsOn) PlayArt.catPicture(ctx, catPath, catSize, boo) else null }
    val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
    val demoTime = s.demo?.at?.takeIf { it != 0L }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bg)).clickable(OPEN_APP)) {
        // the clock face, behind the cat
        Box(GlanceModifier.fillMaxSize().padding(start = (size.width.value * .5f).dp, top = 14.dp)) {
            if (demoTime != null) Image(ImageProvider(PlayArt.clockFace(ctx, faceSize, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))), "The time", GlanceModifier.size(faceSize.dp))
            else AndroidRemoteViews(RemoteViews(ctx.packageName, R.layout.widget_clock_face), GlanceModifier.size(faceSize.dp))
        }
        if (cat != null) Box(GlanceModifier.fillMaxSize().padding(end = 8.dp, bottom = 6.dp), contentAlignment = Alignment.BottomEnd) {
            Image(ImageProvider(cat), s.catName, GlanceModifier.size(catSize.dp))
        }
        Column(GlanceModifier.padding(start = 20.dp, top = 14.dp)) {
            Text(eyebrow, style = TextStyle(color = ColorProvider(eyebrowInk), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (demoTime != null) {
                Text("${cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }}:${cal.get(Calendar.MINUTE).toString().padStart(2, '0')}", style = TextStyle(color = ColorProvider(ink), fontSize = timeSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(java.util.Date(now)), style = TextStyle(color = ColorProvider(dateInk), fontSize = 13.sp), maxLines = 1)
            } else {
                val t = RemoteViews(ctx.packageName, R.layout.widget_clock_time).apply { setTextColor(R.id.clock_time, ink.toArgb()); setTextViewTextSize(R.id.clock_time, TypedValue.COMPLEX_UNIT_SP, timeSp) }
                AndroidRemoteViews(t, GlanceModifier.height((timeSp * 1.3f).dp).width((timeSp * 3.3f).dp))
                val d = RemoteViews(ctx.packageName, R.layout.widget_clock_date).apply { setTextColor(R.id.clock_date, dateInk.toArgb()) }
                AndroidRemoteViews(d, GlanceModifier.height(18.dp).width(170.dp))
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(line, style = TextStyle(color = ColorProvider(lineInk), fontSize = 12.sp), modifier = GlanceModifier.width((size.width.value * .46f).dp), maxLines = lines)
        }
    }
}

// =============================== the hours the widgets change on ===============================

/** Wakes the widgets at the hours that change them (5, 10, 12, 17 and 22), with a loose alarm that needs no permission. */
object DayAlarms {
    private val HOURS = intArrayOf(0, 5, 10, 12, 17, 22) // midnight (a new day's numbers), then the hours the cat's day changes on

    private fun used(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return WidgetPins.KINDS.any { mgr.getAppWidgetIds(android.content.ComponentName(ctx, it.receiver)).isNotEmpty() }
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!used(ctx)) return // no such widget on the home screen: no alarm
        val now = Calendar.getInstance()
        fun at(h: Int, addDays: Int = 0) = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, addDays); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, if (h == 0) 2 else 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val next = HOURS.map { at(it) }.firstOrNull { it > now.timeInMillis + 1_000L } ?: at(HOURS[0], 1)
        val pi = PendingIntent.getBroadcast(ctx, 7001, Intent(ctx, DayReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setWindow(AlarmManager.RTC, next, 10 * 60_000L, pi)
    }
}

class DayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        KavanaApp.repo.scope.launch {
            try {
                // the engine brings its day up to now (today's minutes, the streak, the missions), then the widgets draw it
                val repo = KavanaApp.repo
                (repo.act(op("tick"))?.state ?: repo.state.value)?.let { WidgetUpdater.onState(context, it) }
                WidgetUpdater.refresh(context)
            } finally {
                DayAlarms.schedule(context)
                pending.finish()
            }
        }
    }
}

/** A receiver for a widget that goes by the day: whenever it's added or updated, the loose alarm for the next hour that matters is set. */
abstract class DayWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) { super.onEnabled(context); DayAlarms.schedule(context) }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) { super.onUpdate(context, appWidgetManager, appWidgetIds); DayAlarms.schedule(context) }
}
