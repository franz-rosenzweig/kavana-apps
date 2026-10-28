package xyz.tiresia.kavana.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
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
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.fmtMin

/**
 * The small, plain widgets, each one thing: the streak (Google's layout: a big number and a paw for each day of
 * the week), the daily goal as a ring, and the day's missions in a slim strip with a Collect button.
 */

/** What every widget here does to get its snapshot: the lab's made-up one, or the app's own. */
internal abstract class SnapWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    @Composable
    abstract fun Content(s: Snap)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            Content(snap)
        }
    }
}

// =============================== Streak ===============================

internal class StreakWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = StreakContent(s)
}

class StreakWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}

@Composable
fun StreakContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg) }
    val roomy = size.height >= 100.dp
    val big = (minOf(size.width, size.height).value * if (roomy) .34f else .28f).sp
    val lit = minOf(s.streak, 7)
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(OPEN_APP), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.streak}", style = TextStyle(color = ColorProvider(pal.onSoft), fontSize = big, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
            Text(if (s.streak == 1) "day in a row" else "days in a row", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 13.sp), maxLines = 1)
            if (roomy) {
                Spacer(GlanceModifier.height(8.dp))
                Row {
                    for (i in 0 until 7) Image(ImageProvider(R.drawable.w_paw), null, GlanceModifier.size(16.dp).padding(horizontal = 1.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(if (i < lit) pal.tint else pal.track)))
                }
            }
        }
    }
}

// =============================== Daily goal ===============================

internal class GoalWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = GoalContent(s)
}

class GoalWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GoalWidget()
}

@Composable
fun GoalContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg) }
    val goal = s.goalMin
    val d = minOf(size.width - 30.dp, size.height - 46.dp).coerceAtLeast(40.dp)
    val ring = remember(s.todayMin, goal, d, pal.dark) { WidgetArt.ring(ctx, d.value, if (goal > 0) (s.todayMin / goal.toFloat()).coerceIn(0f, 1f) else 0f, pal.track, pal.tint, d.value * .09f) }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(OPEN_APP), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(GlanceModifier.size(d), contentAlignment = Alignment.Center) {
                Image(ImageProvider(ring), "Today's focus: ${s.todayMin} minutes", GlanceModifier.size(d))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(compact(s.todayMin), style = TextStyle(color = ColorProvider(pal.ink), fontSize = (d.value * .24f).sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Text(if (goal > 0) "of ${fmtMin(goal)}" else "today", style = TextStyle(color = ColorProvider(pal.muted), fontSize = 11.5.sp), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(when {
                goal <= 0 -> "No goal set"
                s.todayMin >= goal -> "Goal reached"
                else -> "${fmtMin(goal - s.todayMin)} to go"
            }, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
        }
    }
}

// =============================== Missions ===============================

internal class MissionsWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = MissionsContent(s)
}

class MissionsWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MissionsWidget()
}

@Composable
fun MissionsContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val pal = CardPal(isDark(ctx))
    val card = remember(size, pal.dark) { WidgetArt.card(ctx, size.width.value, size.height.value, pal.bg, cornerDp = minOf(30f, size.height.value / 2)) }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(card)).clickable(OPEN_APP)) {
        Row(GlanceModifier.fillMaxSize().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.missionsTotal > 0) {
                Row {
                    for (i in 0 until minOf(s.missionsTotal, 5)) {
                        if (i < s.missionsDone) Box(GlanceModifier.size(24.dp).background(ColorProvider(pal.tint)).cornerRadius(12.dp), contentAlignment = Alignment.Center) {
                            Image(ImageProvider(R.drawable.w_check), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(ColorProvider(pal.onTint)))
                        } else Box(GlanceModifier.size(24.dp).background(ColorProvider(pal.track)).cornerRadius(12.dp), contentAlignment = Alignment.Center) {
                            Box(GlanceModifier.size(18.dp).background(ColorProvider(pal.bg)).cornerRadius(9.dp)) {}
                        }
                        Spacer(GlanceModifier.width(6.dp))
                    }
                }
                Spacer(GlanceModifier.width(6.dp))
            }
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.missionsTotal == 0) "No missions yet" else "${s.missionsDone} of ${s.missionsTotal} missions", style = TextStyle(color = ColorProvider(pal.ink), fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                val line = if (s.missionsTotal == 0) "They come with the day" else if (s.missionNext.isNotEmpty()) s.missionNext else "All done for today"
                Text(line, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp), maxLines = 1)
            }
            if (s.collectTotal > 0) {
                Spacer(GlanceModifier.width(8.dp))
                Pill("+${s.collectTotal}", pal.orange, pal.onOrange, actionRunCallback<CollectAction>(), icon = R.drawable.w_treat, height = 40.dp, textSize = 13.sp, pad = 14.dp)
            }
        }
    }
}
