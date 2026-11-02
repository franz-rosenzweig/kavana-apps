package xyz.tiresia.kavana.widget

import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import xyz.tiresia.kavana.CoverActivity
import xyz.tiresia.kavana.MainActivity

/**
 * The widgets for a Galaxy Flip's cover screen (Samsung's Flex Window): the Window, Pet the cat, Daily goal and Your café,
 * the same widgets at the cover's size. Samsung offers a widget there when its receiver also carries
 * `com.samsung.android.appwidget.provider` with `display="sub_screen"` (res/xml/cover_samsung.xml) and its category is
 * keyguard, so these don't show among the home screen's widgets. Their taps open Chataltul on the cover itself
 * (CoverActivity, on the cover's display), not on the inside screen that's folded shut.
 */
val LocalCover = staticCompositionLocalOf { false }

/** Samsung's number for the cover screen's display (the inside one is 0). */
private const val COVER_DISPLAY = 1

/** The cover's display, if this phone has one: a cover widget anywhere else (a tablet's lock screen) opens the app as usual. */
private fun coverDisplay(ctx: Context): Boolean = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(COVER_DISPLAY) != null

/** Opens Chataltul: from a cover widget, its pages on the cover (`page`: "Focus", "Today", "Pet", "Later"); otherwise the app. */
@OptIn(ExperimentalGlanceApi::class) // the display to open on (Samsung's own sample does the same with a PendingIntent)
@Composable
fun openAction(page: String? = null, stop: Boolean = false): Action {
    val ctx = LocalContext.current
    if (LocalCover.current && coverDisplay(ctx)) {
        val i = Intent(ctx, CoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        page?.let { i.putExtra("page", it) }
        if (stop) i.putExtra("stop", true)
        return actionStartActivity(i, actionParametersOf(), ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).toBundle())
    }
    if (stop) return actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("stop", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    return actionStartActivity<MainActivity>()
}

/** "Park a thought": its little dialog, on the cover too when the widget is there (the cover has a keyboard). */
@OptIn(ExperimentalGlanceApi::class)
@Composable
fun parkAction(): Action {
    val ctx = LocalContext.current
    if (LocalCover.current && coverDisplay(ctx))
        return actionStartActivity(Intent(ctx, ParkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), actionParametersOf(),
            ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).toBundle())
    return actionStartActivity<ParkActivity>()
}

internal class CoverWindowWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val tick = currentState<Preferences>()[WidgetUpdater.TICK]
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            val now = remember(tick) { System.currentTimeMillis() }
            CompositionLocalProvider(LocalCover provides true) { WindowContent(snap, now) }
        }
    }
}

internal class CoverPetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wid = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val prefs = currentState<Preferences>()
            val tick = prefs[WidgetUpdater.TICK]
            val fresh = System.currentTimeMillis() - (prefs[PET_AT] ?: 0L) < 6_000L
            val snap = remember(tick) { WidgetStore.fresh(context, wid) }
            CompositionLocalProvider(LocalCover provides true) { PetContent(snap, if (fresh) prefs[PET_MODE] ?: 0 else 0) }
        }
    }
}

internal class CoverGoalWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = CompositionLocalProvider(LocalCover provides true) { GoalContent(s) }
}

internal class CoverCafeWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = CompositionLocalProvider(LocalCover provides true) { CafeContent(s) }
}

class CoverWindowReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWindowWidget()
}

class CoverPetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverPetWidget()
}

class CoverGoalReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverGoalWidget()
}

class CoverCafeReceiver : CafeWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverCafeWidget()
}

object CoverWidgets {
    val RECEIVERS = listOf(CoverWindowReceiver::class.java, CoverPetReceiver::class.java, CoverGoalReceiver::class.java, CoverCafeReceiver::class.java)

    /** Whether a widget (by its Glance id) is one of the cover's: Pet the cat redraws through its own class. */
    fun isCover(ctx: Context, id: GlanceId): Boolean {
        val wid = runCatching { GlanceAppWidgetManager(ctx).getAppWidgetId(id) }.getOrDefault(-1)
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(wid)?.provider?.className ?: return false
        return RECEIVERS.any { it.name == cls }
    }
}
