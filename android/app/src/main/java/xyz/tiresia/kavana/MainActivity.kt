package xyz.tiresia.kavana

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.ui.CoverScreen
import xyz.tiresia.kavana.ui.KavanaTheme
import xyz.tiresia.kavana.ui.Root
import xyz.tiresia.kavana.ui.isCoverScreen

/** Things other parts of the phone ask the screens to do. */
object Requests {
    var stop by mutableStateOf(false)
    /** The sound sheet, asked for by the Sound widget. */
    var sounds by mutableStateOf(false)
    /** A screen to show ("cafe"), asked for by a launcher shortcut. */
    var tab by mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    /** Bumped on every resume, so the screens read the permissions again (back from Settings). */
    val resumes = mutableIntStateOf(0)
    /** Kavana itself sent you to Settings or a link: that's not leaving in the middle of a block. */
    private var expectingLeave = false
    private var leftFor = 0L // when (the other app comes up a moment before this one stops)
    /** On a Flip's cover screen (Good Lock, or the app carried over as the phone closed): the cover's pages instead of the app. */
    private var cover by mutableStateOf(false)

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = KavanaApp.repo
        // check.sh: `--el minute 1000` makes a minute last a second in the debug build.
        if (BuildConfig.DEBUG && intent.hasExtra("minute")) repo.prefs.edit().putLong("minuteMs", intent.getLongExtra("minute", 0)).apply()
        // check-account.py: `--es server http://10.0.2.2:<port>/` signs in to a sandbox server instead of the real one.
        if (BuildConfig.DEBUG && intent.hasExtra("server")) repo.prefs.edit().putString("server", intent.getStringExtra("server")).apply()
        // check-widgets.py: `--ei wallpaperHour 14` draws the café wallpaper as at that hour (-1: the clock's), to see it by day and at night.
        if (BuildConfig.DEBUG && intent.hasExtra("wallpaperHour")) repo.prefs.edit().putInt("wallpaperHour", intent.getIntExtra("wallpaperHour", -1)).apply()
        if (BuildConfig.DEBUG && intent.hasExtra("flex")) repo.prefs.edit().putBoolean("flexDebug", intent.getBooleanExtra("flex", false)).apply()
        leaveLab(intent)
        // The sound mix is snapshot state in a global object: create it here, outside any composition, or the
        // first frame of a cold start can read it from a snapshot that doesn't know it yet (a crash).
        xyz.tiresia.kavana.ui.SoundMix.mix
        if (intent.getBooleanExtra("stop", false)) askToStop()
        if (intent.getBooleanExtra("sounds", false)) Requests.sounds = true
        intent.getStringExtra("tab")?.let { Requests.tab = it }
        cover = isCoverScreen(this)
        setContent { KavanaTheme { if (cover) CoverScreen(stopAsked = Requests.stop) { Requests.stop = false } else Root(this) } }
        lifecycleScope.launch { repo.act(op("tick")) }
        // Signed in: what your other devices did, every 30 seconds while Kavana is on the screen.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30_000); repo.sync.pull() } } }
    }

    // A widget's Stop: the app's own Stop dialog (or Cancel in the first seconds), so it says what stopping means.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // (the widgets' check, debug builds: the wallpaper's hour, also when the app is already open)
        if (BuildConfig.DEBUG && intent.hasExtra("wallpaperHour")) KavanaApp.repo.prefs.edit().putInt("wallpaperHour", intent.getIntExtra("wallpaperHour", -1)).apply()
        // (check-flip.py: `--ez flex true|false`, the half-folded layout on the emulator, whose fold always says it's flat)
        if (BuildConfig.DEBUG && intent.hasExtra("flex")) { KavanaApp.repo.prefs.edit().putBoolean("flexDebug", intent.getBooleanExtra("flex", false)).apply(); resumes.intValue++ }
        leaveLab(intent)
        if (intent.getBooleanExtra("stop", false)) askToStop()
        if (intent.getBooleanExtra("sounds", false)) Requests.sounds = true
        intent.getStringExtra("tab")?.let { Requests.tab = it }
    }

    // check-leave.py: `--es leaveMode except --es leaveApps a.b,c.d` picks the apps without the picker (debug builds).
    private fun leaveLab(i: Intent) {
        if (!BuildConfig.DEBUG) return
        if (i.hasExtra("leaveMode")) LeaveWatch.set(this, mode = i.getStringExtra("leaveMode"))
        if (i.hasExtra("leaveApps")) LeaveWatch.set(this, apps = i.getStringExtra("leaveApps").orEmpty().split(',').filter { it.isNotBlank() }.toSet())
    }

    private fun askToStop() {
        Requests.stop = true
        lifecycleScope.launch { delay(4_000); Requests.stop = false } // not a surprise dialog later, if there's no block to stop
    }

    // Opened or closed, a Flip moves the app between its screens without starting it again (configChanges): pick the screens again.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cover = isCoverScreen(this)
    }

    override fun onStart() {
        super.onStart()
        shown(true)
        cover = isCoverScreen(this)
        expectingLeave = false
        LeaveWatch.stop()
        KavanaApp.repo.back(System.currentTimeMillis())
        KavanaApp.repo.sync.opened()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onStop() {
        shown(false)
        // On a cover screen, going to another of its widgets isn't leaving: the phone is closed.
        if (!isChangingConfigurations && expectingLeave && !cover) LeaveWatch.trip(this, leftFor)
        else if (!isChangingConfigurations && !cover) {
            val at = System.currentTimeMillis()
            val d = display
            // A Flip closed is the phone put down, not another app, but its cover screen lights up as it closes, so the phone
            // still counts as on. Wait a moment and look at this screen itself: closed, it's dark.
            if (foldable) Handler(Looper.getMainLooper()).postDelayed({ if (!visible && screenOn(d)) LeaveWatch.behind(this, at) }, 1200)
            else if (screenOn(d)) LeaveWatch.behind(this, at)
        }
        super.onStop()
    }

    private val foldable by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE) }

    private fun screenOn(d: Display?) = getSystemService(PowerManager::class.java).isInteractive && (d == null || d.state == Display.STATE_ON)

    fun notificationsOn() = Notify.enabled(this)
    fun overlayOn() = CatOverlay.allowed(this)

    fun askNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) && !KavanaApp.repo.prefs.getBoolean("askedNotify", false)) {
            KavanaApp.repo.prefs.edit().putBoolean("askedNotify", true).apply()
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            leaveFor(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    fun askOverlay() = leaveFor(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    /** Usage access, for picked apps: straight to Chataltul's switch where the phone can, the list of apps otherwise. */
    fun askUsage() = leaveFor(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) || leaveFor(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    fun openUrl(url: String) = leaveFor(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun leaveFor(i: Intent): Boolean {
        expectingLeave = true
        leftFor = System.currentTimeMillis()
        return runCatching { startActivity(i) }.onFailure { expectingLeave = false }.isSuccess
    }

    fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Chataltul", text))
    }

    companion object {
        private val showing = java.util.concurrent.atomic.AtomicInteger(0)
        /** Kavana is on a screen, the inside one or a Flip's cover (its check-ins show there instead of over another app). */
        val visible: Boolean get() = showing.get() > 0
        /** An activity of Chataltul's came on a screen, or went (the two can overlap as a Flip opens or closes). */
        fun shown(yes: Boolean) { if (yes) showing.incrementAndGet() else showing.updateAndGet { maxOf(0, it - 1) } }
    }
}
