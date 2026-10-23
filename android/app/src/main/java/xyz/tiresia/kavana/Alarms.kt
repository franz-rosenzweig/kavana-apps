package xyz.tiresia.kavana

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.ZonedDateTime

/**
 * Exact alarms for the next ping (a check-in, a nudge, the end, a break's end) and for the daily
 * reminder. Like the web's server, the app keeps the next pings (E.pings) and wakes up for the
 * first one; what's due then is shown, and the rest is scheduled again from the new doc.
 */
object Alarms {
    private const val SAVED = "pings"

    /** `wake`: another time to tick at (a pause that ends by itself), with no ping of its own. */
    fun pings(ctx: Context, list: List<Ping>, wake: Long? = null) {
        KavanaApp.repo.prefs.edit().putString(SAVED, KJson.encodeToString(ListSerializer(Ping.serializer()), list)).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, PingReceiver::class.java), FLAGS)
        val next = listOfNotNull(list.minOfOrNull { it.at }, wake).minOrNull()
        if (next == null) am.cancel(pi) else exact(am, next, pi)
    }

    fun saved(ctx: Context): List<Ping> = KavanaApp.repo.prefs.getString(SAVED, null)
        ?.let { runCatching { KJson.decodeFromString(ListSerializer(Ping.serializer()), it) }.getOrNull() } ?: emptyList()

    fun daily(ctx: Context, d: DailySet) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, DailyReceiver::class.java), FLAGS)
        val at = nextDaily(d, System.currentTimeMillis())
        if (at == null) am.cancel(pi) else exact(am, at, pi)
    }

    /** The next time on one of the days (0 = Sunday, as in the web), after `from`. */
    fun nextDaily(d: DailySet, from: Long): Long? {
        if (!d.on || d.days.isEmpty()) return null
        val (h, m) = d.time.split(":").map { it.toIntOrNull() ?: 0 }
        val base = ZonedDateTime.now().withHour(h).withMinute(m).withSecond(0).withNano(0)
        for (i in 0..7) {
            val day = base.plusDays(i.toLong())
            val at = day.toInstant().toEpochMilli()
            if (day.dayOfWeek.value % 7 in d.days && at > from + 1000) return at
        }
        return null
    }

    private fun exact(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}

/** Runs `work` off the main thread and keeps the receiver alive until it's done. */
private fun BroadcastReceiver.async(work: suspend () -> Unit) {
    val p = goAsync()
    KavanaApp.repo.scope.launch { try { work() } finally { p.finish() } }
}

class PingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async { KavanaApp.repo.deliverDue() }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val st = KavanaApp.repo.act(op("tick"))?.state ?: return@async
        // Not while a block is running (the web's "It won't ping you if you're already in a block").
        if (st.view.active == null && st.view.settings.daily.on) Notify.daily(ctx, st)
    }
}

/** After a restart, an update or a clock change: everything scheduled again from the doc. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async { KavanaApp.repo.act(op("tick")) }
}

/** The buttons on a notification: Yes / Drifted on a check-in, Done / Partly / Not really at the end. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = async {
        val repo = KavanaApp.repo
        val id = intent.getStringExtra("block") ?: return@async
        when (intent.action) {
            ANSWER -> repo.act(op("answer", "id" to id, "n" to intent.getIntExtra("n", 0), "answer" to intent.getStringExtra("answer")))
            OUTCOME -> repo.act(op("outcome", "id" to id, "outcome" to intent.getStringExtra("outcome")))
        }
        Notify.cancel(ctx, Notify.ID_PING)
    }

    companion object {
        const val ANSWER = "xyz.tiresia.kavana.ANSWER"
        const val OUTCOME = "xyz.tiresia.kavana.OUTCOME"
    }
}
