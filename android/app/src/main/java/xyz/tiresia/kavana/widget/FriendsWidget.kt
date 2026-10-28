package xyz.tiresia.kavana.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.KJson
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.R
import java.io.File

/**
 * The friends' table: who's focusing, who's on a break, who isn't, and how long they have left, with their kittens around a table.
 * It's what the server's friends list says (GET api/friends), so a friend who shares less, or nothing, shows less, or "isn't sharing":
 * the server never sends what they keep to themselves. It needs an account. Read-only, for now: joining a friend's nap needs the
 * app to report leaving and coming back, as the web does, and the app doesn't yet.
 */
@Serializable
data class FriendRow(val name: String, val display: String = "", val status: String = "idle", val end: Long? = null, val left: Long? = null)

@Serializable
data class FriendsSnap(val signedIn: Boolean = false, val sharing: Boolean = true, val rows: List<FriendRow> = emptyList(), val at: Long = 0)

object Friends {
    private fun file(ctx: Context) = File(ctx.filesDir, "friends.json")

    fun get(ctx: Context): FriendsSnap = runCatching { KJson.decodeFromString(FriendsSnap.serializer(), file(ctx).readText()) }.getOrDefault(FriendsSnap())

    private fun put(ctx: Context, s: FriendsSnap) {
        runCatching { file(ctx).writeText(KJson.encodeToString(FriendsSnap.serializer(), s)) }
    }

    fun used(ctx: Context): Boolean = AppWidgetManager.getInstance(ctx).getAppWidgetIds(android.content.ComponentName(ctx, FriendsWidgetReceiver::class.java)).isNotEmpty()

    /** Asks the server who's doing what (if there's an account), keeps the answer, and draws the widget again. Offline: keeps the last one. */
    suspend fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val repo = KavanaApp.repo
        if (repo.sync.account.value == null) {
            put(app, FriendsSnap(signedIn = false, at = System.currentTimeMillis()))
        } else {
            val j: JsonObject = runCatching { repo.sync.api("GET", "api/friends") }.getOrNull() ?: return
            val rows = j["friends"]?.jsonArray?.mapNotNull { e ->
                val o = e.jsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun lng(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
                FriendRow(str("name") ?: return@mapNotNull null, str("display") ?: str("name")!!, str("status") ?: "idle", lng("end"), lng("left"))
            } ?: emptyList()
            put(app, FriendsSnap(true, (j["sharing"] as? JsonPrimitive)?.booleanOrNull ?: true, rows, System.currentTimeMillis()))
        }
        WidgetUpdater.refresh(app)
    }
}

class FriendsRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) = Friends.refresh(context)
}

internal class FriendsWidget : SnapWidget() {
    @Composable
    override fun Content(s: Snap) = FriendsContent(s)
}

class FriendsWidgetReceiver : DayWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FriendsWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); KavanaApp.repo.scope.launch { Friends.refresh(context) } }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        KavanaApp.repo.scope.launch { Friends.refresh(context) }
    }
}

private val KITTENS = listOf("miso", "mochi", "biscuit", "earl", "luna", "maple", "cocoa", "dumpling", "flurry")

/** "14 min left", "1 h 5 min left": from when the friend's block ends, as of now. */
private fun leftText(r: FriendRow, now: Long, at: Long): String {
    val ms = r.end?.let { it - now } ?: r.left?.let { it - (now - at) } ?: return "focusing"
    val min = ((ms + 59_999L) / 60_000L).toInt()
    return if (min <= 0) "just finishing" else if (min < 60) "$min min left" else "${min / 60} h${if (min % 60 != 0) " ${min % 60} min" else ""} left"
}

/** "13:42 left", counting down by itself (a Chronometer), so the widget isn't stale between the times it's drawn. */
@Composable
private fun FriendLeft(ctx: Context, leftMs: Long, color: Color) {
    val rv = RemoteViews(ctx.packageName, R.layout.widget_chrono)
    rv.setChronometerCountDown(R.id.chrono, true)
    rv.setChronometer(R.id.chrono, SystemClock.elapsedRealtime() + leftMs.coerceAtLeast(0L), "%s left", true)
    rv.setTextColor(R.id.chrono, color.toArgb())
    rv.setTextViewTextSize(R.id.chrono, TypedValue.COMPLEX_UNIT_SP, 12f)
    AndroidRemoteViews(rv, GlanceModifier.height(17.dp).width(76.dp))
}

@Composable
fun FriendsContent(s: Snap) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    // the night wall at night or in dark mode, as on the evening board; by day the café's own wall and a light table, as on the tablet's
    val night = isDark(ctx) || isNight(clockNow(s))
    val wallTheme = if (night) "night" else s.theme
    val fs = s.friends ?: Friends.get(ctx) // read afresh each time it draws: a copy kept from before never saw a refresh
    val now = System.currentTimeMillis()
    val light = WidgetArt.lightInk(wallTheme, night)
    val ink = if (light) Color(0xFFF2EDE6) else Color(0xFF2B211A)
    val muted = if (light) Color(0xFFB7AEA5) else Color(0xFF6B6158)
    val teal = if (night) Color(0xFF5FD0C0) else Color(0xFF2C8A7E)
    val onTeal = if (night) Color(0xFF10302B) else Color.White
    val rows = remember(fs) { fs.rows.sortedBy { when (it.status) { "focusing" -> 0; "paused" -> 1; "break" -> 2; "idle" -> 3; else -> 4 } }.take(3) }
    val shown = if (fs.signedIn && fs.sharing) rows else emptyList()
    val bg = remember(size, wallTheme, night) { WidgetArt.wall(ctx, size.width.value, size.height.value, wallTheme, night) }
    val tableSize = minOf(138f, size.height.value - 24f)
    val table = remember(shown, tableSize, night) {
        PlayArt.table(ctx, tableSize, shown.map { r ->
            val art = Art.kitten(KITTENS[(r.name.hashCode() and 0x7fffffff) % KITTENS.size])
            Triple(art, when (r.status) { "focusing" -> 1f; "paused", "break" -> .75f; "idle" -> .55f; else -> .4f },
                if (r.status == "focusing") ((r.end?.let { it - now } ?: r.left ?: 0L) / 3_600_000f).coerceIn(.05f, 1f) else null)
        }, night, teal)
    }
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(bg)).clickable(OPEN_APP)) {
        Row(GlanceModifier.fillMaxSize().padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(tableSize.dp), contentAlignment = Alignment.Center) {
                androidx.glance.Image(ImageProvider(table), "The friends' table", GlanceModifier.size(tableSize.dp))
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                if (shown.isNotEmpty()) {
                    for ((i, r) in shown.withIndex()) {
                        if (i > 0) Spacer(GlanceModifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(GlanceModifier.size(8.dp).background(ColorProvider(when (r.status) { "focusing" -> teal; "paused", "break" -> if (night) Color(0xFFFFCF8A) else Color(0xFFE9B25A); else -> if (night) Color(0xFF6F6678) else Color(0xFFB8ACA0) })).cornerRadius(4.dp)) {}
                            Spacer(GlanceModifier.width(8.dp))
                            Text(r.display.ifBlank { r.name }, style = TextStyle(color = ColorProvider(ink), fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                            Spacer(GlanceModifier.width(8.dp))
                            if (r.status == "focusing" && r.end != null && s.friends == null) FriendLeft(ctx, r.end - now, muted)
                            else Text(when (r.status) { "focusing" -> leftText(r, now, fs.at); "paused" -> "paused"; "break" -> "on a break"; "idle" -> "not focusing"; else -> "isn't sharing" },
                                style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp), maxLines = 1)
                        }
                    }
                } else {
                    Text(when {
                        !fs.signedIn -> "Not signed in"
                        !fs.sharing -> "Sharing is off"
                        else -> "No friends yet"
                    }, style = TextStyle(color = ColorProvider(ink), fontSize = 13.sp), maxLines = 4)
                }
                Spacer(GlanceModifier.defaultWeight())
                if (fs.signedIn) Pill("Refresh", teal, onTeal, actionRunCallback<FriendsRefreshAction>(), GlanceModifier.fillMaxWidth(), height = 38.dp, textSize = 14.sp)
                else Pill("Open Chataltul", teal, onTeal, OPEN_APP, GlanceModifier.fillMaxWidth(), height = 38.dp, textSize = 14.sp)
            }
        }
    }
}
