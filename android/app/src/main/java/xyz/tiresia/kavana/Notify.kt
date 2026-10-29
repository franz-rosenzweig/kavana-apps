package xyz.tiresia.kavana

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.util.Log
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.KeyguardManager
import androidx.annotation.RequiresApi
import java.text.DateFormat
import java.util.Date

/** Kavana's notifications: the pings, the countdown during a block, the daily reminder, "come back". */
object Notify {
    const val CH_CHECKINS = "checkins"
    const val CH_FOCUS = "focus"
    const val CH_DAILY = "daily"
    const val CH_AWAY = "away"

    const val ID_ONGOING = 1
    const val ID_PING = 10
    const val ID_BREAK = 11
    const val ID_DAILY = 12
    const val ID_AWAY = 13

    private val TINT = Color.parseColor("#2C8A7E")

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_CHECKINS, "Check-ins", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "\"Still on it?\", the nudges, and the end of a block"
            },
            NotificationChannel(CH_FOCUS, "Countdown", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The time left in a block, on the lock screen and in the status bar"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            },
            NotificationChannel(CH_DAILY, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_AWAY, "Come back", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When you leave Chataltul in the middle of a block"
                setSound(null, null)
            },
        ))
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, code: Int, title: String, act: String, vararg extras: Pair<String, Any?>): Notification.Action {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(act)
        for ((k, v) in extras) when (v) { is String -> i.putExtra(k, v); is Int -> i.putExtra(k, v) }
        val pi = PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, title, pi).build()
    }

    /** A check-in, a nudge, the end or a break's end, as engine.js words it (E.pings). */
    fun ping(ctx: Context, p: Ping, st: UiState?) {
        val b = Notification.Builder(ctx, CH_CHECKINS)
            .setSmallIcon(R.drawable.ic_notify).setColor(TINT)
            .setContentTitle(p.title).setContentText(p.body)
            .setStyle(Notification.BigTextStyle().bigText(p.body))
            .setAutoCancel(true).setContentIntent(open(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
        val catId = st?.view?.active?.cat ?: st?.view?.awaiting?.cat
        if (p.cat == 1 && catId != null) Art.bitmap(ctx, Art.portrait(catId))?.let { b.setLargeIcon(it) }
        val id = p.block
        when (p.kind) {
            "checkin" -> if (id != null) {
                b.addAction(action(ctx, 21, "Yes", ActionReceiver.ANSWER, "block" to id, "n" to (p.n ?: 0), "answer" to "yes"))
                b.addAction(action(ctx, 22, "Drifted", ActionReceiver.ANSWER, "block" to id, "n" to (p.n ?: 0), "answer" to "drifted"))
            }
            // In extra time the block is still running: Done etc. come when you stop it, in Kavana.
            "end" -> if (id != null && st?.view?.active?.id != id) {
                b.addAction(action(ctx, 23, "Done", ActionReceiver.OUTCOME, "block" to id, "outcome" to "done"))
                b.addAction(action(ctx, 24, "Partly", ActionReceiver.OUTCOME, "block" to id, "outcome" to "partly"))
                b.addAction(action(ctx, 25, "Not really", ActionReceiver.OUTCOME, "block" to id, "outcome" to "notreally"))
            }
        }
        if (p.kind == "checkin" || p.kind == "end") cancelAway(ctx) // "Miso is waiting" is old news by now
        post(ctx, if (p.kind == "break-over") ID_BREAK else ID_PING, b.build())
    }

    /**
     * The countdown while a block (or a break) runs: the FocusService's own notification. What you called the
     * block is only in the full version (the shade, once you've unlocked); the lock screen gets the public one,
     * with the cat and the clock but not the title.
     */
    fun ongoing(ctx: Context, st: UiState?): Notification {
        val pub = ongoingBuilder(ctx, st, hideTitle = true).build()
        return ongoingBuilder(ctx, st, hideTitle = false).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(pub).build()
    }

    private fun ongoingBuilder(ctx: Context, st: UiState?, hideTitle: Boolean): Notification.Builder {
        val b = Notification.Builder(ctx, CH_FOCUS)
            .setSmallIcon(R.drawable.ic_notify).setColor(TINT)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open(ctx)).setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) // Android holds it back ~10 s otherwise
        val now = System.currentTimeMillis()
        val a = st?.view?.active
        val brk = st?.view?.onBreak
        when {
            a != null -> {
                val c = st.cat(a.cat)
                b.setContentTitle(if (hideTitle) (if (a.paused != null) "Paused" else "Focusing") else a.title)
                // Spent: time on the block so far, pauses left out (web/app.js renderRun).
                val spent = a.total - (a.end - (a.paused ?: now))
                b.setContentText(when {
                    a.paused != null -> "Paused. It starts again by itself at ${time(a.pauseEnds ?: a.paused)}."
                    st.catsOn && a.kitten == "gone" -> "${c.name} wandered off. Your block keeps going."
                    a.over != null -> if (st.catsOn) "${c.name} came home. Extra time counts until you tap Done." else "Extra time counts until you tap Done."
                    a.stopwatch && !st.catsOn -> "Stopwatch, started at ${time(a.start)}"
                    a.stopwatch -> if (spent < 10 * 60_000L) "Stay 10 minutes and ${c.name} can come home." else "${c.name} can come home now. Tap Done when you're done."
                    !st.catsOn -> "Ends at ${time(a.end)}"
                    a.kitten == "gone" -> "${c.name} wandered off. Your block keeps going."
                    a.kitten == "home" -> "${c.name} came home. Keep going for more treats."
                    else -> "${c.name} is growing. Stay with it."
                })
                when {
                    a.paused != null -> {}
                    // counting up: the chronometer starts where the block's own time started
                    a.stopwatch -> b.setWhen(now - spent).setShowWhen(true).setUsesChronometer(true)
                    a.over != null -> b.setWhen(now - (spent - a.minutes * 60_000L).coerceAtLeast(0)).setShowWhen(true).setUsesChronometer(true).setSubText("Extra time")
                    else -> b.setWhen(a.end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                }
                // Pause (or Resume) and Park a thought, right on the notification
                if (a.paused != null) b.addAction(action(ctx, 30, "Resume", ActionReceiver.RESUME))
                else if (st.canPause && a.over == null) b.addAction(action(ctx, 31, "Pause", ActionReceiver.PAUSE))
                b.addAction(Notification.Action.Builder(null, "Park a thought", PendingIntent.getActivity(ctx, 32, Intent(ctx, xyz.tiresia.kavana.widget.ParkActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
                val done = when { a.stopwatch -> (spent % 3_600_000L) / 3_600_000f; a.over != null -> 1f; else -> 1f - (a.end - (a.paused ?: now)).toFloat() / a.total.coerceAtLeast(1) }
                if (Build.VERSION.SDK_INT >= 36) promote(ctx, b, st, a, done, if (a.paused != null) "Paused" else if (a.over != null) "Extra" else null)
            }
            brk != null -> {
                b.setContentTitle("Break").setContentText("Stand up, stretch, get some water.")
                b.setWhen(brk.end).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                val total = (brk.end - brk.start).coerceAtLeast(1)
                if (Build.VERSION.SDK_INT >= 36) promote(ctx, b, st, null, 1f - (brk.end - now).toFloat() / total, null)
            }
            else -> b.setContentTitle("Chataltul")
        }
        return b
    }

    // Android 16's Live Updates: on Samsung, the Now Bar on the lock screen. While a block runs the bar has its
    // three stages (the kitten napping, growing, grown), a dot for each check-in, the kitten itself walking along it,
    // a window where it starts and a house where it's going.
    @RequiresApi(36)
    private fun promote(ctx: Context, b: Notification.Builder, st: UiState?, a: Block?, done: Float, chip: String?) {
        val style = Notification.ProgressStyle().setStyledByProgress(a == null)
        if (a == null) style.setProgress((done.coerceIn(0f, 1f) * 100).toInt())
        else runCatching {
            val now = System.currentTimeMillis()
            val spent = a.total - (a.end - (a.paused ?: now))
            val frac = when { a.stopwatch -> spent / (180 * 60_000f); a.over != null -> 1f; else -> spent.toFloat() / a.total.coerceAtLeast(1) }.coerceIn(0f, 1f)
            style.setProgress((frac * 1000).toInt())
            // the stages: a timer's by its quarters, a stopwatch's at 10 and 40 minutes of its 3 hours
            val lengths = if (a.stopwatch) listOf(56, 167, 777) else listOf(250, 500, 250)
            val colors = listOf(0xFFBFE3DE.toInt(), 0xFF5FB5A8.toInt(), 0xFF2C8A7E.toInt())
            style.setProgressSegments(lengths.mapIndexed { i, n -> Notification.ProgressStyle.Segment(n).setColor(colors[i]) })
            if (!a.stopwatch) {
                val points = st?.pings.orEmpty().filter { it.kind == "checkin" && it.block == a.id }
                    .map { (1000 * (1f - (a.end - it.at).toFloat() / a.total.coerceAtLeast(1))).toInt() }.filter { it in 1..999 }
                style.setProgressPoints(points.map { Notification.ProgressStyle.Point(it).setColor(Color.WHITE) })
            }
            val s = xyz.tiresia.kavana.widget.WidgetStore.get(ctx)
            val stage = xyz.tiresia.kavana.widget.stageOf(frac)
            if (st?.catsOn != false) {
                val path = when { a.kitten == "gone" -> Art.GONE; stage == "nap" -> s.nap; stage == "kitten" -> s.kitten; else -> s.portrait }
                Art.bitmap(ctx, path)?.let { style.setProgressTrackerIcon(Icon.createWithBitmap(Bitmap.createScaledBitmap(it, 96, 96, true))) }
            }
            style.setProgressStartIcon(Icon.createWithBitmap(xyz.tiresia.kavana.widget.WidgetArt.window(ctx, s.copy(catsOn = false), now, 24f, false, xyz.tiresia.kavana.widget.WMode.READY)))
            style.setProgressEndIcon(Icon.createWithResource(ctx, R.drawable.ic_progress_home))
        }.onFailure { Log.w(Repo.TAG, "progress style", it) }
        b.setStyle(style)
        b.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }) // EXTRA_REQUEST_PROMOTED_ONGOING
        if (chip != null) b.setShortCriticalText(chip)
    }

    fun daily(ctx: Context, st: UiState) {
        val d = st.daily
        post(ctx, ID_DAILY, Notification.Builder(ctx, CH_DAILY)
            .setSmallIcon(R.drawable.ic_notify).setColor(TINT)
            .setContentTitle("${if (d.cat) "🐱 Boo! " else ""}Time to focus?").setContentText(d.body)
            .setAutoCancel(true).setContentIntent(open(ctx)).build())
    }

    /** Left Kavana mid-block without the cat over other apps: the web's "🐱 Miso is waiting". */
    fun away(ctx: Context, name: String) {
        post(ctx, ID_AWAY, Notification.Builder(ctx, CH_AWAY)
            .setSmallIcon(R.drawable.ic_notify).setColor(TINT)
            .setContentTitle("🐱 $name is waiting").setContentText("Come back to Chataltul, or $name wanders off.")
            .setAutoCancel(true).setTimeoutAfter(Repo.GRACE).setContentIntent(open(ctx)).build())
    }

    fun cancelAway(ctx: Context) = cancel(ctx, ID_AWAY)
    fun cancel(ctx: Context, id: Int) = nm(ctx).cancel(id)

    fun enabled(ctx: Context) = nm(ctx).areNotificationsEnabled()

    private fun post(ctx: Context, id: Int, n: Notification) {
        if (enabled(ctx)) runCatching { nm(ctx).notify(id, n) }
    }

    private fun time(t: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
}

/** Where a ping goes: the screen if Kavana is open, the cat over another app, or a notification. */
object Deliver {
    fun ping(ctx: Context, p: Ping, st: UiState) {
        if (MainActivity.visible) { buzz(ctx); return } // the check-in or the end shows on the screen itself
        val pm = ctx.getSystemService(PowerManager::class.java)
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (p.cat == 1 && pm.isInteractive && !locked && CatOverlay.allowed(ctx)) CatOverlay.ping(ctx, p, st)
        else Notify.ping(ctx, p, st)
    }

    fun buzz(ctx: Context) {
        runCatching { ctx.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
    }
}
