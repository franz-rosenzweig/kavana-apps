package xyz.tiresia.kavana.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the watch draws: the phone's WatchSync.WatchSnap, the same shape. */
@Serializable
data class WatchSnap(
    val mode: String, val catName: String, val catsOn: Boolean = true, val title: String = "",
    val start: Long = 0, val end: Long = 0, val total: Long = 0, val paused: Long? = null, val pauseEnds: Long? = null,
    val stopwatch: Boolean = false, val over: Long? = null, val pending: Int? = null,
    val minutes: Int = 0, val treats: Int = 0, val todayMin: Int = 0, val goalMin: Int = 0, val streak: Int = 0,
    val sent: Long = 0,
) {
    /** A block's clock is running down (not paused, not a stopwatch, not over). */
    val counting get() = (mode == "FOCUSING" || mode == "CHECKIN" || mode == "GONE") && paused == null && !stopwatch && over == null && end > 0

    /** Done can end it without harm: extra time (it counts too), or a stopwatch past 10 minutes (the kitten comes home). */
    fun finishes(now: Long) = over != null || stopwatch && total - (end - (paused ?: now)) >= 10 * 60_000L
}

/** The last snapshot from the phone and its picture of the window, kept in two small files. */
object WatchStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun snapFile(ctx: Context) = File(ctx.filesDir, "snap.json")
    private fun picFile(ctx: Context) = File(ctx.filesDir, "window.png")

    fun snap(ctx: Context): WatchSnap? = runCatching { json.decodeFromString(WatchSnap.serializer(), snapFile(ctx).readText()) }.getOrNull()
    fun window(ctx: Context): ByteArray? = runCatching { picFile(ctx).readBytes() }.getOrNull()

    /** Changes with the picture, so the tile asks for it again. */
    fun version(ctx: Context): String = picFile(ctx).let { if (it.exists()) "w${it.lastModified()}" else "none" }

    fun put(ctx: Context, snapJson: String, png: ByteArray?) {
        snapFile(ctx).writeText(snapJson)
        if (png != null) picFile(ctx).writeBytes(png)
        changed(ctx)
    }

    /** Draw the tile and the complications again. */
    fun changed(ctx: Context) {
        val app = ctx.applicationContext // (the tile's updater binds a service, which a receiver's own context may not do)
        runCatching { TileService.getUpdater(app).requestUpdate(FocusTile::class.java) }
        runCatching { ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, FocusComplication::class.java)).requestUpdateAll() }
    }
}

/** Taps on the watch go to the phone, which does them (WatchListenerService there). */
object Phone {
    private const val ACT = "/chataltul/act"

    /** What a tap means, as the phone understands it. */
    fun opFor(id: String): String? = when (id) {
        "start25" -> """{"op":"start","min":25}"""
        "start15" -> """{"op":"start","min":15}"""
        "start50" -> """{"op":"start","min":50}"""
        "startInf" -> """{"op":"start","min":-1}"""
        "pause" -> """{"op":"pause"}"""
        "resume" -> """{"op":"resume"}"""
        "stop" -> """{"op":"stop"}"""
        "yes" -> """{"op":"answer","what":"yes"}"""
        "drifted" -> """{"op":"answer","what":"drifted"}"""
        "done" -> """{"op":"outcome","what":"done"}"""
        "hello" -> """{"op":"hello"}"""
        else -> null
    }

    /** Sends it to the phone (every phone this watch is connected to); false if there's none. */
    suspend fun act(ctx: Context, id: String): Boolean {
        val body = opFor(id) ?: return false
        return runCatching {
            val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
            for (n in nodes) Wearable.getMessageClient(ctx).sendMessage(n.id, ACT, body.toByteArray()).await()
            android.util.Log.i("ChataltulWatch", "tap $id: sent to ${nodes.size} phone(s)")
            nodes.isNotEmpty()
        }.getOrDefault(false)
    }
}
