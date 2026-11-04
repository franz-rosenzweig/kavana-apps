package xyz.tiresia.kavana.widget

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import xyz.tiresia.kavana.KJson
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.Notify
import xyz.tiresia.kavana.op
import java.io.ByteArrayOutputStream

/**
 * The watch's half of the widgets: the phone sends the Wear OS app a small snapshot (and the window's picture) whenever it changes,
 * and does what's tapped on the watch as if it had been tapped on a widget. It goes over Google's Wearable Data Layer, which needs
 * Play services on the phone; without them, or without a watch, nothing happens. Block titles go only if they're shown on widgets.
 */
object WatchSync {
    const val SNAP = "/chataltul/snap"
    const val ACT = "/chataltul/act"

    /** What the watch draws (the same shape is in the watch app's WatchSnap). */
    @Serializable
    data class WatchSnap(
        val mode: String, val catName: String, val catsOn: Boolean = true, val title: String = "",
        val start: Long = 0, val end: Long = 0, val total: Long = 0, val paused: Long? = null, val pauseEnds: Long? = null,
        val stopwatch: Boolean = false, val over: Long? = null, val pending: Int? = null,
        val minutes: Int = 0, val treats: Int = 0, val todayMin: Int = 0, val goalMin: Int = 0, val streak: Int = 0,
        val sent: Long = 0,
    )

    private fun watchSnap(s: Snap): WatchSnap {
        val b = s.block
        val a = s.awaiting
        val hide = WidgetPins.hideTitles
        return WatchSnap(s.mode().name, s.catName, s.catsOn, if (hide) "" else b?.title ?: a?.title ?: "",
            b?.start ?: 0, b?.end ?: 0, b?.total ?: 0, b?.paused, b?.pauseEnds, b?.stopwatch ?: false, b?.over, b?.pending,
            a?.minutes ?: 0, a?.treats ?: 0, s.todayMin, s.goalMin, s.streak, System.currentTimeMillis())
    }

    /** The snapshot changed: send it, if there's a watch to send it to. */
    fun send(ctx: Context, s: Snap, force: Boolean = false) {
        val app = ctx.applicationContext
        KavanaApp.repo.scope.launch(Dispatchers.Default) {
            runCatching {
                if (!force && Wearable.getNodeClient(app).connectedNodes.await().isEmpty()) return@runCatching
                val pic = WidgetArt.window(app, s, System.currentTimeMillis(), 64f, false)
                val png = ByteArrayOutputStream().also { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                val req = PutDataMapRequest.create(SNAP).apply {
                    dataMap.putString("json", KJson.encodeToString(WatchSnap.serializer(), watchSnap(s)))
                    dataMap.putAsset("window", Asset.createFromBytes(png))
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(app).putDataItem(req).await()
            }
        }
    }

    /** A tap on the watch: do it as the widget would. */
    suspend fun handle(ctx: Context, o: JsonObject) {
        val repo = KavanaApp.repo
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content
        when (str("op")) {
            "start" -> { val m = (o["min"] as? JsonPrimitive)?.intOrNull ?: 25; WidgetOps.start(if (m < 0) 180 else m, if (m < 0) "stopwatch" else "timer") }
            "pause" -> repo.act(op("pause"))
            "resume" -> repo.act(op("resume"))
            "stop" -> { // Done, only where it can't hurt (the watch's WatchSnap.finishes); stopping anything else stays the phone's
                val b = (repo.state.value ?: repo.act(op("tick"))?.state)?.view?.active
                if (b != null && (b.over != null || b.stopwatch && b.total - (b.end - (b.paused ?: System.currentTimeMillis())) >= 10 * 60_000L)) repo.act(op("stop"))
            }
            "answer" -> {
                val b = (repo.state.value ?: repo.act(op("tick"))?.state)?.view?.active
                if (b?.pending != null) repo.act(op("answer", "id" to b.id, "n" to b.pending, "answer" to str("what")))
                Notify.cancel(ctx, Notify.ID_PING)
            }
            "outcome" -> {
                val a = (repo.state.value ?: repo.act(op("tick"))?.state)?.view?.awaiting
                if (a != null) repo.act(op("outcome", "id" to a.id, "outcome" to str("what")))
                Notify.cancel(ctx, Notify.ID_PING)
            }
            "hello" -> send(ctx, WidgetStore.get(ctx), force = true) // the watch has nothing yet, or something old
        }
        WidgetUpdater.refresh(ctx)
    }
}

/** Taps from the watch (MessageClient, path /chataltul/act). */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != WatchSync.ACT) return
        val o = runCatching { KJson.parseToJsonElement(String(e.data)).jsonObject }.getOrNull() ?: return
        runBlocking { withTimeoutOrNull(15_000L) { WatchSync.handle(applicationContext, o) } } // (on the listener's own thread)
    }
}
