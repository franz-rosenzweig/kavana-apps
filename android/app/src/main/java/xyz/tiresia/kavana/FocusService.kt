package xyz.tiresia.kavana

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs while a block (or a break) does. Its notification is the countdown, on the lock screen and in
 * the status bar, and being a foreground service keeps Kavana around for the check-ins and the cat
 * (Samsung closes background apps quickly). It also notices one way of leaving that the screen can't:
 * the phone locked inside Kavana, then unlocked straight into another app.
 */
class FocusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val at = System.currentTimeMillis()
            main.postDelayed({ if (!MainActivity.visible) KavanaApp.repo.left(at) }, 2500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = KavanaApp.repo
        startForeground(Notify.ID_ONGOING, Notify.ongoing(this, repo.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        running = true
        if (job == null) {
            registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
            job = scope.launch {
                if (repo.state.value == null) repo.act(op("tick")) // started again after the process was gone
                launch { repo.state.collect { refresh(it) } }
                while (true) {
                    delay(30_000)
                    // A pause ends by itself after 5 minutes: let the engine notice (the end moves by exactly the pause).
                    val a = repo.state.value?.view?.active
                    if (a?.pauseEnds != null && a.pauseEnds <= System.currentTimeMillis()) repo.act(op("tick"))
                    refresh(repo.state.value) // the progress bar
                }
            }
        }
        return START_STICKY
    }

    private fun refresh(st: UiState?) {
        if (st != null && st.view.active == null && st.view.onBreak == null) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(Notify.ID_ONGOING, Notify.ongoing(this, st))
    }

    // Kavana swiped away from the recent apps. With "Swiping Kavana away counts as leaving" off, that's
    // not leaving: forget it, and take the cat away (the web's closeCounts, for a closed tab).
    override fun onTaskRemoved(rootIntent: Intent?) {
        val repo = KavanaApp.repo
        if (repo.state.value?.view?.settings?.closeCounts == false) { repo.prefs.edit().remove("left").apply(); CatOverlay.hide() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        runCatching { unregisterReceiver(unlocked) }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set

        /** On while a block or a break runs, off otherwise. */
        fun sync(ctx: Context, st: UiState) {
            val want = st.view.active != null || st.view.onBreak != null
            val i = Intent(ctx, FocusService::class.java)
            if (want && !running) runCatching { ctx.startForegroundService(i) }.onFailure { Log.w(Repo.TAG, "countdown", it) }
            else if (!want && running) ctx.stopService(i)
        }
    }
}
