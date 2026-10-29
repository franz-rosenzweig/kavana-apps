package xyz.tiresia.kavana.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.Sound
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.ui.SoundMix

/** Keeps the widgets up to date: the snapshot is saved when the app's state changes, and they draw again. */
object WidgetUpdater {
    /** The app's state changed (Repo.after): save what the widgets draw, and redraw them if it's different. */
    fun onState(ctx: Context, st: UiState) {
        CafeRender.update(ctx, st)
        if (WidgetStore.put(ctx, snapOf(st).copy(mix = SoundMix.mix.map { SnapSound(it.first, Sound.byId(it.first)?.name ?: it.first, it.second) }, muted = SoundMix.muted))) refresh(ctx)
    }

    /** Something outside the engine changed what a widget shows (the sound mix, mute): snapshot again from the latest state. */
    fun changed(ctx: Context) {
        KavanaApp.repo.state.value?.let { onState(ctx, it) }
    }

    /** Draw every widget again from the saved snapshot (also called while a block runs, for the ring and the kitten's stage). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        Tiles.refresh(app)
        KavanaApp.repo.scope.launch {
            for (widget in WIDGETS) {
                val ids = runCatching { GlanceAppWidgetManager(app).getGlanceIds(widget.javaClass) }.getOrDefault(emptyList())
                for (id in ids) runCatching { // one that's gone (a widget just removed) mustn't stop the others
                    updateAppWidgetState(app, id) { it[TICK] = System.nanoTime() } // what the widget's composition watches
                    widget.update(app, id)
                }
            }
        }
    }

    /** Every kind of widget there is. */
    private val WIDGETS = listOf<androidx.glance.appwidget.GlanceAppWidget>(WindowWidget(), QuickStartWidget(), TodayWidget(), LaterWidget(), SoundWidget(),
        StreakWidget(), GoalWidget(), MissionsWidget(), PetCatWidget(), ClockWidget(),
        ConstellationWidget(), WeatherWidget(), CafeWidget(), FriendsWidget())

    /** Each widget's state holds a number that changes on every refresh, so Glance draws it again from the latest snapshot. */
    val TICK = longPreferencesKey("tick")
}
