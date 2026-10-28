package xyz.tiresia.kavana.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.SizeMode
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.fmtClock
import java.util.Calendar

/** What the widgets' words and buttons are coloured with (the mockups' palette), by the café's theme and the phone's dark mode. */
class Pal(val dark: Boolean, theme: String) {
    val lightInk = WidgetArt.lightInk(theme, dark)
    val ink = if (lightInk) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (lightInk) Color(0xFFB7AEA5) else Color(0xFF5D5249)
    val eyebrow = if (lightInk) Color(0xFF5FD0C0) else Color(0xFF1F6B61)
    val tint = if (lightInk) Color(0xFF5FD0C0) else Color(0xFF2C8A7E)
    val onTint = if (lightInk) Color(0xFF10302B) else Color.White
    val chip = if (lightInk) Color(255, 255, 255, 26) else Color(43, 33, 26, 20)
    val amber = if (lightInk) Color(0xFFFFCF8A) else Color(0xFFE98A3C)
    val onAmber = Color(0xFF3A2510)
    val orange = if (lightInk) Color(0xFFFFB36B) else Color(0xFFD9731E)
    val orangeLine = Color(0xFFE98A3C)
    val green = if (lightInk) Color(0xFF7FDC9B) else Color(0xFF2F9E5A)
    val onGreen = if (lightInk) Color(0xFF0E2A18) else Color.White
}

/**
 * The Window: your cat in a round window on the café wall. It's one widget in three shapes (the 4×2, the 2×2
 * and the slim 4×1 strip), and shows what's happening: ready to start, the kitten growing with a live
 * countdown, a check-in to answer, the kitten come home, paused, or wandered off. What it shows comes from the
 * snapshot the app saved (WidgetStore), so drawing it doesn't start the engine.
 */
class WindowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            // Glance keeps a widget's session going and recomposes it only when its state changes, so the updater
            // bumps a number in that state (WidgetUpdater) and the snapshot is read again each time it changes.
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            val now = remember(tick) { System.currentTimeMillis() }
            WindowContent(snap, now)
        }
    }
}

class WindowWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WindowWidget()
}

private val OPEN: Action @Composable get() = actionStartActivity<MainActivity>()

@Composable
private fun stopAction(): Action {
    val ctx = LocalContext.current
    return actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("stop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

private fun answer(id: String, n: Int, what: String) = actionRunCallback<AnswerAction>(actionParametersOf(WidgetKeys.ID to id, WidgetKeys.N to n, WidgetKeys.WHAT to what))
private fun outcome(id: String, what: String) = actionRunCallback<OutcomeAction>(actionParametersOf(WidgetKeys.ID to id, WidgetKeys.WHAT to what))
private fun start(min: Int) = startAction(min)
/** Starts a block of this many minutes (a negative number: a stopwatch). */
fun startAction(min: Int) = actionRunCallback<StartAction>(actionParametersOf(WidgetKeys.MIN to min))

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "GOOD MORNING"; in 12..16 -> "GOOD AFTERNOON"; in 17..21 -> "GOOD EVENING"; else -> "NIGHT OWL"
}

@Composable
fun WindowContent(s: Snap, now: Long) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val mode = s.mode()
    val pal = Pal(dark, s.theme)
    val hide = ctx.getSharedPreferences("kavana", Context.MODE_PRIVATE).getBoolean("hideTitles", false)
    val wall = remember(size, s.theme, dark, mode == WMode.CHECKIN) {
        WidgetArt.wall(ctx, size.width.value, size.height.value, s.theme, dark, if (mode == WMode.CHECKIN) pal.orangeLine else null)
    }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(wall)).clickable(OPEN)) {
        when {
            size.height < 100.dp -> Strip(ctx, s, now, mode, pal, size, hide)
            size.width < 200.dp -> Small(ctx, s, now, mode, pal, size, hide)
            else -> Wide(ctx, s, now, mode, pal, size, hide)
        }
    }
}

// ---------- the 4 × 2 ----------

@Composable
private fun Wide(ctx: Context, s: Snap, now: Long, mode: WMode, pal: Pal, size: DpSize, hide: Boolean) {
    val d = minOf(size.height - 24.dp, 140.dp)
    val win = remember(s, size, now / 20_000) { WidgetArt.window(ctx, s, now, d.value, pal.dark, mode) }
    val b = s.block
    val title = if (hide) "" else b?.title.orEmpty()
    val tight = size.height < 160.dp // a real 4×2 is about 150 dp tall: the words and buttons have to fit in that
    val bh = if (tight) 38.dp else 40.dp
    Row(GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(win), "${s.catName} in the window", GlanceModifier.size(d))
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            when (mode) {
                WMode.READY -> {
                    Eyebrow(greeting(), pal.eyebrow)
                    Heading(if (s.catsOn) "${s.catName} is waiting" else "Ready when you are", 20.sp, pal)
                    Sub("Tap to start 25 minutes", pal)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Pill("Start", pal.tint, pal.onTint, start(25), GlanceModifier.defaultWeight(), R.drawable.w_play)
                        Spacer(GlanceModifier.width(8.dp))
                        Pill("15", pal.chip, pal.ink, start(15), GlanceModifier.width(44.dp), textSize = 13.sp)
                        Spacer(GlanceModifier.width(8.dp))
                        Pill("50", pal.chip, pal.ink, start(50), GlanceModifier.width(44.dp), textSize = 13.sp)
                    }
                }
                WMode.FOCUSING, WMode.EXTRA, WMode.GONE -> {
                    if (mode == WMode.GONE) {
                        Eyebrow("${s.catName.uppercase()} WANDERED OFF", pal.orange)
                        Heading("Your block keeps going.", if (tight) 15.sp else 17.sp, pal, lines = if (tight) 1 else 2)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Chrono(ctx, b!!, now, 15f, pal.ink)
                            Text(" left · no kitten this time", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                        }
                        Spacer(GlanceModifier.defaultWeight())
                        Pill("Open Chataltul", pal.chip, pal.ink, OPEN, GlanceModifier.fillMaxWidth(), height = bh, textSize = 14.sp)
                    } else {
                        Eyebrow(if (mode == WMode.EXTRA) "EXTRA TIME" else "FOCUSING", pal.eyebrow)
                        Chrono(ctx, b!!, now, if (tight) 30f else 34f, pal.ink)
                        val next = if (mode == WMode.EXTRA) "Chataltul keeps counting" else b.next?.let { "Check-in in ${((it - now + 59_999) / 60_000).coerceAtLeast(1)} min" } ?: "Check-in at the end"
                        val line = title.ifEmpty { if (s.catsOn) "${s.catName} is growing" else "" }
                        // roomy: its name and the check-in; tight: just one of them (its name if it has one)
                        if (tight) Text(line.ifEmpty { next }, style = TextStyle(color = ColorProvider(pal.ink), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                        else {
                            if (line.isNotEmpty()) Text(line, style = TextStyle(color = ColorProvider(pal.ink), fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                            Sub(next, pal)
                        }
                        Spacer(GlanceModifier.defaultWeight())
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (mode == WMode.EXTRA) Pill("Done", pal.green, pal.onGreen, stopAction(), GlanceModifier.defaultWeight(), R.drawable.w_check, height = bh, textSize = 14.sp)
                            else Pill("Pause", pal.amber, pal.onAmber, actionRunCallback<PauseAction>(), GlanceModifier.defaultWeight(), R.drawable.w_pause, height = bh, textSize = 14.sp)
                            Spacer(GlanceModifier.width(8.dp))
                            Round(R.drawable.w_bulb, "Park a thought", pal.chip, pal.ink, actionStartActivity<ParkActivity>(), bh)
                            Spacer(GlanceModifier.width(8.dp))
                            if (mode != WMode.EXTRA) Round(R.drawable.w_stop, "Stop", pal.chip, pal.ink, stopAction(), bh)
                        }
                    }
                }
                WMode.CHECKIN -> {
                    Eyebrow("BOO! CHECK-IN", pal.orange)
                    Heading(if (title.isNotEmpty()) "Still on: $title?" else "Still focusing?", 18.sp, pal, lines = 3)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        Pill("Yes", pal.tint, pal.onTint, answer(b!!.id, b.pending ?: 0, "yes"), GlanceModifier.defaultWeight())
                        Spacer(GlanceModifier.width(8.dp))
                        Pill("Drifted", pal.chip, pal.ink, answer(b.id, b.pending ?: 0, "drifted"), GlanceModifier.defaultWeight())
                    }
                }
                WMode.PAUSED -> {
                    Eyebrow("PAUSED", pal.orange)
                    ChronoTo(ctx, b!!.pauseEnds ?: (b.paused!! + 5 * 60_000L), now, if (tight) 30f else 34f, pal.ink, b.still)
                    Sub("Starts again by itself", pal)
                    if (s.catsOn && !tight) Sub("${s.catName} waits for you", pal)
                    Spacer(GlanceModifier.defaultWeight())
                    Pill("Resume", pal.green, pal.onGreen, actionRunCallback<ResumeAction>(), GlanceModifier.fillMaxWidth(), R.drawable.w_play, height = bh, textSize = 14.sp)
                }
                WMode.HOME, WMode.STOPPED -> {
                    val e = s.awaiting!!
                    if (mode == WMode.HOME) {
                        Eyebrow("${s.catName.uppercase()} CAME HOME", pal.eyebrow)
                        Heading("Nice, ${e.minutes} minutes", 18.sp, pal)
                        Text("+${e.treats} treats", style = TextStyle(color = ColorProvider(pal.orange), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    } else {
                        Eyebrow("STOPPED EARLY", pal.orange)
                        Heading("${s.catName} wandered off", 18.sp, pal)
                        Sub("${e.minutes} min · how did it go?", pal)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        Pill("Done", pal.tint, pal.onTint, outcome(e.id, "done"), GlanceModifier.width(46.dp), height = bh, textSize = 12.sp)
                        Spacer(GlanceModifier.width(5.dp))
                        Pill("Partly", pal.chip, pal.ink, outcome(e.id, "partly"), GlanceModifier.width(52.dp), height = bh, textSize = 12.sp)
                        Spacer(GlanceModifier.width(5.dp))
                        Pill("Not really", pal.chip, pal.ink, outcome(e.id, "notreally"), GlanceModifier.defaultWeight(), height = bh, textSize = 12.sp)
                    }
                }
                WMode.BREAK -> {
                    Eyebrow("BREAK", pal.eyebrow)
                    Heading("Stretch your legs", 18.sp, pal)
                    Sub("Stand up, get some water.", pal)
                    Spacer(GlanceModifier.defaultWeight())
                    Pill("End break", pal.chip, pal.ink, actionRunCallback<EndBreakAction>(), GlanceModifier.fillMaxWidth(), height = bh, textSize = 14.sp)
                }
            }
        }
    }
}

// ---------- the 2 × 2 ----------

@Composable
private fun Small(ctx: Context, s: Snap, now: Long, mode: WMode, pal: Pal, size: DpSize, hide: Boolean) {
    val d = minOf(size.width - 20.dp, size.height - 20.dp - 40.dp, 120.dp)
    val win = remember(s, size, now / 20_000) { WidgetArt.window(ctx, s, now, d.value, pal.dark, mode) }
    val b = s.block
    Column(GlanceModifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(win), "${s.catName} in the window", GlanceModifier.size(d))
        Spacer(GlanceModifier.defaultWeight())
        when (mode) {
            WMode.READY -> Pill("Start 25", pal.tint, pal.onTint, start(25), GlanceModifier.fillMaxWidth(), height = 36.dp, textSize = 14.sp)
            WMode.FOCUSING, WMode.EXTRA, WMode.GONE -> {
                Chrono(ctx, b!!, now, 20f, pal.ink)
                val t = if (hide) "" else b.title
                if (mode == WMode.GONE) Text("wandered off", style = TextStyle(color = ColorProvider(pal.orange), fontSize = 11.sp), maxLines = 1)
                else if (t.isNotEmpty()) Text(t, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 11.sp), maxLines = 1)
            }
            WMode.CHECKIN -> Row(GlanceModifier.fillMaxWidth()) {
                Pill("Yes", pal.tint, pal.onTint, answer(b!!.id, b.pending ?: 0, "yes"), GlanceModifier.defaultWeight(), height = 36.dp, textSize = 13.sp)
                Spacer(GlanceModifier.width(6.dp))
                Pill("Drifted", pal.chip, pal.ink, answer(b.id, b.pending ?: 0, "drifted"), GlanceModifier.defaultWeight(), height = 36.dp, textSize = 13.sp)
            }
            WMode.PAUSED -> Pill("Resume", pal.green, pal.onGreen, actionRunCallback<ResumeAction>(), GlanceModifier.fillMaxWidth(), height = 36.dp, textSize = 14.sp)
            WMode.HOME, WMode.STOPPED -> Text(if (mode == WMode.HOME) "+${s.awaiting!!.treats}" else "How did it go?",
                style = TextStyle(color = ColorProvider(pal.ink), fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
            WMode.BREAK -> Pill("End break", pal.chip, pal.ink, actionRunCallback<EndBreakAction>(), GlanceModifier.fillMaxWidth(), height = 36.dp, textSize = 13.sp)
        }
    }
}

// ---------- the slim 4 × 1 strip ----------

@Composable
private fun Strip(ctx: Context, s: Snap, now: Long, mode: WMode, pal: Pal, size: DpSize, hide: Boolean) = StripBody(ctx, s, now, mode, pal, size, hide)

/** The strip for a block that's running (or just ended), as the Quick start widget shows it. */
@Composable
fun StripFor(ctx: Context, s: Snap, now: Long, mode: WMode, pal: Pal, size: DpSize) =
    StripBody(ctx, s, now, mode, pal, size, ctx.getSharedPreferences("kavana", Context.MODE_PRIVATE).getBoolean("hideTitles", false))

@Composable
private fun StripBody(ctx: Context, s: Snap, now: Long, mode: WMode, pal: Pal, size: DpSize, hide: Boolean) {
    val d = minOf(size.height - 12.dp, 64.dp)
    val win = remember(s, size, now / 20_000) { WidgetArt.window(ctx, s, now, d.value, pal.dark, mode) }
    val b = s.block
    val title = if (hide) "" else b?.title.orEmpty()
    Row(GlanceModifier.fillMaxSize().padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(win), "${s.catName} in the window", GlanceModifier.size(d))
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            when (mode) {
                WMode.READY -> {
                    Text(if (s.catsOn) "${s.catName} is waiting" else "Ready when you are", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text("Tap to start 25 minutes", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                }
                WMode.FOCUSING, WMode.EXTRA, WMode.GONE -> {
                    Chrono(ctx, b!!, now, 22f, pal.ink)
                    val line = if (mode == WMode.GONE) "${s.catName} wandered off" else title.ifEmpty { if (s.catsOn) "${s.catName} is growing" else "Focusing" }
                    Text(line, style = TextStyle(color = ColorProvider(if (mode == WMode.GONE) pal.orange else pal.muted), fontSize = 12.sp), maxLines = 1)
                }
                WMode.CHECKIN -> {
                    Eyebrow("BOO! CHECK-IN", pal.orange)
                    Text(if (title.isNotEmpty()) "Still on: $title?" else "Still focusing?", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                WMode.PAUSED -> {
                    Text("Paused", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text("Starts again by itself", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                }
                WMode.HOME, WMode.STOPPED -> {
                    Text(if (mode == WMode.HOME) "${s.catName} came home" else "${s.catName} wandered off", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(if (mode == WMode.HOME) "+${s.awaiting!!.treats} treats" else "${s.awaiting!!.minutes} min", style = TextStyle(color = ColorProvider(pal.orange), fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                WMode.BREAK -> {
                    Text("Break", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text("Stand up, get some water.", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
                }
            }
        }
        when (mode) {
            WMode.READY -> Pill("Start", pal.tint, pal.onTint, start(25), GlanceModifier.width(76.dp), R.drawable.w_play, height = 40.dp, textSize = 14.sp)
            WMode.FOCUSING, WMode.GONE -> Round(R.drawable.w_pause, "Pause", pal.amber, pal.onAmber, actionRunCallback<PauseAction>(), 40.dp)
            WMode.EXTRA -> Round(R.drawable.w_check, "Done", pal.green, pal.onGreen, stopAction(), 40.dp)
            WMode.CHECKIN -> Row {
                Pill("Yes", pal.tint, pal.onTint, answer(b!!.id, b.pending ?: 0, "yes"), GlanceModifier.width(52.dp), height = 38.dp, textSize = 13.sp)
                Spacer(GlanceModifier.width(6.dp))
                Pill("Drifted", pal.chip, pal.ink, answer(b.id, b.pending ?: 0, "drifted"), GlanceModifier.width(66.dp), height = 38.dp, textSize = 13.sp)
            }
            WMode.PAUSED -> Round(R.drawable.w_play, "Resume", pal.green, pal.onGreen, actionRunCallback<ResumeAction>(), 40.dp)
            WMode.HOME, WMode.STOPPED -> Pill("Done", pal.tint, pal.onTint, outcome(s.awaiting!!.id, "done"), GlanceModifier.width(64.dp), height = 38.dp, textSize = 13.sp)
            WMode.BREAK -> Pill("End", pal.chip, pal.ink, actionRunCallback<EndBreakAction>(), GlanceModifier.width(56.dp), height = 38.dp, textSize = 13.sp)
        }
    }
}

// ---------- the little parts ----------

@Composable
private fun Eyebrow(text: String, color: Color) =
    Text(text, style = TextStyle(color = ColorProvider(color), fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)

@Composable
private fun Heading(text: String, size: TextUnit, pal: Pal, lines: Int = 2) =
    Text(text, style = TextStyle(color = ColorProvider(pal.ink), fontSize = size, fontWeight = FontWeight.Bold), maxLines = lines)

@Composable
private fun Sub(text: String, pal: Pal) =
    Text(text, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)

/** A pill-shaped button: an optional little icon, and its words. */
@Composable
fun Pill(label: String, bg: Color, fg: Color, action: Action, modifier: GlanceModifier = GlanceModifier, icon: Int? = null, height: Dp = 44.dp, textSize: TextUnit = 15.sp, pad: Dp = 0.dp) {
    Box(modifier.height(height).background(ColorProvider(bg)).cornerRadius(height / 2).clickable(action), contentAlignment = Alignment.Center) {
        Row(GlanceModifier.padding(horizontal = pad), verticalAlignment = Alignment.CenterVertically) {  // (a pill as wide as its words needs room, or its round ends cut into the letters)
            if (icon != null) {
                Image(ImageProvider(icon), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(ColorProvider(fg)))
                Spacer(GlanceModifier.width(6.dp))
            }
            Text(label, style = TextStyle(color = ColorProvider(fg), fontSize = textSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

/** A round button with just an icon. */
@Composable
fun Round(icon: Int, description: String, bg: Color, fg: Color, action: Action, size: Dp = 40.dp) {
    Box(GlanceModifier.size(size).background(ColorProvider(bg)).cornerRadius(size / 2).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(icon), description, GlanceModifier.size(if (size < 40.dp) 14.dp else 16.dp), colorFilter = ColorFilter.tint(ColorProvider(fg)))
    }
}

/** A live countdown to a time of day (a pause ending by itself). */
@Composable
fun ChronoTo(ctx: Context, target: Long, now: Long, sp: Float, color: Color, still: Boolean = false) {
    if (still) { StillClock(fmtClock((target - now).coerceAtLeast(0)), sp, color); return }
    val rv = RemoteViews(ctx.packageName, R.layout.widget_chrono)
    rv.setChronometerCountDown(R.id.chrono, true)
    rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + (target - now).coerceAtLeast(0), null, true)
    rv.setTextColor(R.id.chrono, color.toArgb())
    rv.setTextViewTextSize(R.id.chrono, TypedValue.COMPLEX_UNIT_SP, sp)
    AndroidRemoteViews(rv, clockBox(sp, if (target - now >= 3_600_000L) 7 else 5))
}

/**
 * The live clock: a Chronometer, which counts by itself, so the widget doesn't redraw every second. A timer
 * counts down to its end, a stopwatch up from its start, extra time up from where the block's own time ended.
 */
@Composable
fun Chrono(ctx: Context, b: SnapBlock, now: Long, sp: Float, color: Color) {
    if (b.still) {
        val at = b.paused ?: now
        val shown = when { b.over != null -> now - b.over; b.stopwatch -> b.total - (b.end - at); else -> b.end - at }
        StillClock((if (b.over != null) "+" else "") + fmtClock(shown.coerceAtLeast(0)), sp, color)
        return
    }
    val rv = RemoteViews(ctx.packageName, R.layout.widget_chrono)
    val real = SystemClock.elapsedRealtime()
    when {
        b.over != null -> { rv.setChronometerCountDown(R.id.chrono, false); rv.setChronometer(R.id.chrono, real - (now - b.over), "+%s", true) }
        b.stopwatch -> { rv.setChronometerCountDown(R.id.chrono, false); rv.setChronometer(R.id.chrono, real - (b.total - (b.end - now)), null, true) }
        else -> { rv.setChronometerCountDown(R.id.chrono, true); rv.setChronometer(R.id.chrono, real + (b.end - now), null, true) }
    }
    rv.setTextColor(R.id.chrono, color.toArgb())
    rv.setTextViewTextSize(R.id.chrono, TypedValue.COMPLEX_UNIT_SP, sp)
    val at = b.paused ?: now
    val shown = when { b.over != null -> now - b.over; b.stopwatch -> b.total - (b.end - at); else -> b.end - at }
    AndroidRemoteViews(rv, clockBox(sp, (if (shown >= 3_600_000L) 7 else 5) + (if (b.over != null) 1 else 0)))
}

/** A clock that doesn't tick (the lab's made-up blocks): plain text in the same place. */
@Composable
private fun StillClock(text: String, sp: Float, color: Color) =
    Text(text, style = TextStyle(color = ColorProvider(color), fontSize = sp.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.height((sp * 1.3f).dp), maxLines = 1)

/** The room a clock needs (a RemoteViews would take all that's left otherwise): about `chars` digits at `sp`. */
private fun clockBox(sp: Float, chars: Int): GlanceModifier = GlanceModifier.height((sp * 1.3f).dp).width((chars * sp * 0.62f).dp)
