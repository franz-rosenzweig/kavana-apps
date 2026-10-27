package xyz.tiresia.kavana.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.Sound
import xyz.tiresia.kavana.op
import xyz.tiresia.kavana.ui.SoundMix

/** The Quick Settings tiles: start a block, park a thought, the sound. They draw from the same snapshot as the widgets. */
object Tiles {
    val ALL: List<Class<out TileService>> = listOf(FocusTileService::class.java, ParkTileService::class.java, SoundTileService::class.java)

    /** Ask Android to read the tiles again (they're "active" tiles: they only update when asked). */
    fun refresh(ctx: Context) {
        for (c in ALL) runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, c)) }
    }

    /** Asks Android to add the three tiles to Quick Settings (it shows its own "Add tile?" for each; Android 13 and newer). */
    fun requestAdd(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) {
            KavanaApp.repo.toasts.tryEmit("Swipe down twice, tap the pencil, and drag Chataltul's tiles in.")
            return
        }
        val sb = ctx.getSystemService(android.app.StatusBarManager::class.java)
        val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
        for ((cls, label, icon) in listOf(Triple(FocusTileService::class.java, "Chataltul", R.drawable.ic_notify), Triple(ParkTileService::class.java, "Park a thought", R.drawable.ic_tile_park),
            Triple(SoundTileService::class.java, "Focus sound", R.drawable.ic_tile_sound)))
            runCatching { sb.requestAddTileService(ComponentName(ctx, cls), label, Icon.createWithResource(ctx, icon), ex) { } }
    }

    fun open(ctx: Context, vararg extras: Pair<String, Any>): Intent =
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .also { i -> for ((k, v) in extras) when (v) { is Boolean -> i.putExtra(k, v); is String -> i.putExtra(k, v) } }
}

private fun TileService.openAndCollapse(i: Intent) {
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    else @Suppress("DEPRECATION") startActivityAndCollapse(i)
}

private fun Tile.show(label: String, sub: String?, on: Boolean, icon: Int) {
    this.label = label
    subtitle = sub
    state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    this.icon = Icon.createWithResource(KavanaApp.app, icon)
    updateTile()
}

/** "Chataltul": starts 25 minutes, or, while a block runs, says how long is left and opens the app. */
class FocusTileService : TileService() {
    override fun onStartListening() = update()

    private fun update() {
        val s = WidgetStore.get(this)
        val b = s.block
        val now = System.currentTimeMillis()
        val sub = when {
            b != null && b.paused != null -> "Paused"
            b != null && b.stopwatch -> "Focusing · ${((b.total - (b.end - now)) / 60_000).coerceAtLeast(0)} min"
            b != null && b.over != null -> "Extra time"
            b != null -> "Focusing · ${((b.end - now + 59_999) / 60_000).coerceAtLeast(0)} min left"
            s.awaiting != null -> "How did it go?"
            s.breakEnd != null -> "On a break"
            else -> "Start 25 min"
        }
        qsTile?.show("Chataltul", sub, b != null || s.awaiting != null, R.drawable.ic_notify)
    }

    override fun onClick() {
        val s = WidgetStore.get(this)
        if (s.block == null && s.awaiting == null && s.breakEnd == null) {
            KavanaApp.repo.scope.launch { WidgetOps.start(25); WidgetUpdater.refresh(applicationContext) }
            qsTile?.show("Chataltul", "Starting…", true, R.drawable.ic_notify)
        } else unlockAndRun { openAndCollapse(Tiles.open(this)) }
    }
}

/** "Park a thought": the small dialog, over whatever you're doing. */
class ParkTileService : TileService() {
    override fun onStartListening() { qsTile?.show("Park a thought", "Tap to add", false, R.drawable.ic_tile_park) }
    override fun onClick() { unlockAndRun { openAndCollapse(Intent(this, ParkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** "Focus sound": what's in the mix; a tap plays or pauses it during a block, and picks sounds otherwise. */
class SoundTileService : TileService() {
    override fun onStartListening() = update()

    private fun update() {
        val s = WidgetStore.get(this)
        val running = s.block != null && s.block.paused == null
        val names = s.mix.joinToString(" + ") { it.name }
        qsTile?.show("Focus sound", if (s.mix.isEmpty()) "Pick a sound" else if (s.muted) "Paused" else names, running && s.mix.isNotEmpty() && !s.muted, R.drawable.ic_tile_sound)
    }

    override fun onClick() {
        val s = WidgetStore.get(this)
        val running = s.block != null && s.block.paused == null
        if (s.mix.isNotEmpty() && running) {
            val ui = SoundMix
            ui.muted = !ui.muted
            val st = KavanaApp.repo.state.value
            if (st != null) { if (ui.muted) Sound.stop() else Sound.play(applicationContext, ui.playable(st)) }
            update()
        } else unlockAndRun { openAndCollapse(Tiles.open(this, "sounds" to true)) }
    }
}
