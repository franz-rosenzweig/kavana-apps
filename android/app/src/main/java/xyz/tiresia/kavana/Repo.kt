package xyz.tiresia.kavana

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.File

/**
 * The doc (one JSON file in the app's own storage, the same shape the web keeps in the browser),
 * the engine that changes it, and what follows every change: the next check-in's alarm, the daily
 * reminder, and the countdown during a block. One change at a time, from the screens, the
 * notification buttons, the cat over other apps and the alarms alike.
 */
class Repo(private val app: Context) {
    private val engine = Engine(app)
    private val lock = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = AtomicFile(File(app.filesDir, "doc.json"))
    val prefs: SharedPreferences = app.getSharedPreferences("kavana", Context.MODE_PRIVATE)

    val state = MutableStateFlow<UiState?>(null)
    /** Cats met and not celebrated yet (the meet sheet takes them one at a time). */
    val meets = MutableStateFlow<List<String>>(emptyList())
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Set when the engine can't run at all (no JavaScript sandbox on this phone). */
    val broken = MutableStateFlow<String?>(null)

    suspend fun act(op: JsonObject): Out? = lock.withLock { run(op, retry = true) }

    /** Fire and forget, from the screens; `then` runs on the main thread. */
    fun launch(op: JsonObject, then: (Out?) -> Unit = {}) = scope.launch {
        val out = act(op)
        kotlinx.coroutines.withContext(Dispatchers.Main) { then(out) }
    }

    private suspend fun run(op: JsonObject, retry: Boolean): Out? {
        if (!Engine.supported) { broken.value = "nosandbox"; return null }
        return try {
            if (engine.fresh) load()
            val raw = engine.eval("KB.run(${JSONObject.quote(op.toString())}, ${System.currentTimeMillis()})")
            val out = KJson.decodeFromString(Out.serializer(), raw)
            if (out.changed) write(engine.eval("KB.dump()"))
            if (out.error != null) Log.i(TAG, "${op["op"]}: ${out.error}")
            state.value = out.state
            if (out.meets.isNotEmpty()) meets.update { (it + out.meets).distinct() }
            after(out.state)
            out
        } catch (e: Exception) {
            Log.w(TAG, "engine", e)
            engine.reset()
            if (retry) run(op, retry = false) else { broken.value = e.message ?: e.javaClass.simpleName; null }
        }
    }

    private suspend fun load() {
        val json = if (file.baseFile.exists()) String(file.readFully()) else ""
        try {
            engine.eval("KB.load(${JSONObject.quote(json)})")
        } catch (e: Exception) {
            if (json.isEmpty()) throw e
            // A doc that doesn't parse: keep it aside, never write over it, and start a new one.
            Log.w(TAG, "doc.json doesn't load, kept as doc.json.bad", e)
            file.baseFile.copyTo(File(app.filesDir, "doc.json.bad-${System.currentTimeMillis()}"), overwrite = true)
            engine.eval("KB.load('')")
        }
        val ms = if (BuildConfig.DEBUG) prefs.getLong("minuteMs", 0) else 0
        if (ms > 0) engine.eval("KB.minute($ms)")
    }

    private fun write(json: String) {
        val out = file.startWrite()
        try { out.write(json.toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }

    /** The doc as the app keeps it (for Settings → Copy my data, and tests). */
    fun docJson(): String = if (file.baseFile.exists()) String(file.readFully()) else "{}"

    private fun after(st: UiState) {
        val before = Alarms.saved(app)
        Alarms.pings(app, st.pings)
        Alarms.daily(app, st.view.settings.daily)
        FocusService.sync(app, st)
        // Pings whose time came are shown by whatever noticed it: the alarm, the open screen's own
        // timer, a button. (If only the alarm showed them, the screen's timer, which keeps running
        // while Kavana is in the background, could move a check-in along first and it was never seen.)
        // Pings that left the list for other reasons (Stop, Pause, an answer) are still in the future.
        val now = System.currentTimeMillis()
        val still = st.pings.map { it.key }.toSet()
        val due = before.filter { it.at <= now + 1500 && it.key !in still }
            .groupBy { it.kind }.map { it.value.last() } // several at once (the phone was off): the last of each
            .filter { it.kind == "end" || now - it.at < 10 * 60_000 } // an old check-in or nudge is no use now
        for (p in due.sortedBy { it.at }) Deliver.ping(app, p, st)
    }

    // ---------- leaving Kavana in the middle of a block (the web's leftKavana/backInKavana) ----------

    /** Kavana went behind another app with the screen on. Locking the phone doesn't count. */
    fun left(now: Long) {
        val st = state.value ?: return
        val a = st.view.active ?: return
        if (!st.catsOn || a.paused != null || a.kitten != "growing") return
        if (prefs.getLong("left", 0) != 0L) return
        prefs.edit().putLong("left", now).apply()
        if (CatOverlay.allowed(app)) CatOverlay.leave(app, st, a) else Notify.away(app, st.cat(a.cat).name)
    }

    /** Back in Kavana: more than the grace away, and the kitten wandered off at the end of it. */
    fun back(now: Long) {
        val t = prefs.getLong("left", 0)
        if (t != 0L) prefs.edit().remove("left").apply()
        CatOverlay.hide() // whatever the cat was saying, Kavana's own screen says it now
        Notify.cancelAway(app)
        if (t == 0L || now - t <= GRACE) return
        scope.launch {
            val out = act(op("away", "at" to t + GRACE)) ?: return@launch
            val cat = (out.result as? JsonObject)?.get("cat")?.jsonPrimitive?.content ?: return@launch
            toasts.emit("${out.state.cat(cat).name} wandered off while you were away.")
        }
    }

    /** An alarm went off: move time on, which shows what came due (see after()). */
    suspend fun deliverDue() {
        act(op("tick"))
    }

    companion object {
        const val TAG = "Kavana"
        /** Seconds away before the kitten wanders off (web/app.js AWAY_GRACE). */
        const val GRACE = 15_000L
    }
}

/** One change for KB.run: `op("answer", "id" to id, "n" to 1, "answer" to "yes")`. */
fun op(name: String, vararg kv: Pair<String, Any?>): JsonObject = buildJsonObject {
    put("op", JsonPrimitive(name))
    for ((k, v) in kv) put(k, toJson(v))
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { toJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    else -> JsonPrimitive(v.toString())
}

/** A settings change: `settings("interval" to 20)`. */
fun settings(vararg kv: Pair<String, Any?>): JsonObject = op("settings", "patch" to kv.toMap())

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
fun JsonElement?.str(key: String): String? = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.content }
