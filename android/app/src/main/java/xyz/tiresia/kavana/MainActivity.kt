package xyz.tiresia.kavana

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
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
import xyz.tiresia.kavana.ui.KavanaTheme
import xyz.tiresia.kavana.ui.Root

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

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = KavanaApp.repo
        // check.sh: `--el minute 1000` makes a minute last a second in the debug build.
        if (BuildConfig.DEBUG && intent.hasExtra("minute")) repo.prefs.edit().putLong("minuteMs", intent.getLongExtra("minute", 0)).apply()
        // check-account.py: `--es server http://10.0.2.2:<port>/` signs in to a sandbox server instead of the real one.
        if (BuildConfig.DEBUG && intent.hasExtra("server")) repo.prefs.edit().putString("server", intent.getStringExtra("server")).apply()
        // The sound mix is snapshot state in a global object: create it here, outside any composition, or the
        // first frame of a cold start can read it from a snapshot that doesn't know it yet (a crash).
        xyz.tiresia.kavana.ui.SoundMix.mix
        if (intent.getBooleanExtra("stop", false)) askToStop()
        if (intent.getBooleanExtra("sounds", false)) Requests.sounds = true
        intent.getStringExtra("tab")?.let { Requests.tab = it }
        setContent { KavanaTheme { Root(this) } }
        lifecycleScope.launch { repo.act(op("tick")) }
        // Signed in: what your other devices did, every 30 seconds while Kavana is on the screen.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(30_000); repo.sync.pull() } } }
    }

    // A widget's Stop: the app's own Stop dialog (or Cancel in the first seconds), so it says what stopping means.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("stop", false)) askToStop()
        if (intent.getBooleanExtra("sounds", false)) Requests.sounds = true
        intent.getStringExtra("tab")?.let { Requests.tab = it }
    }

    private fun askToStop() {
        Requests.stop = true
        lifecycleScope.launch { delay(4_000); Requests.stop = false } // not a surprise dialog later, if there's no block to stop
    }

    override fun onStart() {
        super.onStart()
        visible = true
        expectingLeave = false
        KavanaApp.repo.back(System.currentTimeMillis())
        KavanaApp.repo.sync.opened()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onStop() {
        visible = false
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        if (!isChangingConfigurations && !expectingLeave && screenOn) KavanaApp.repo.left(System.currentTimeMillis())
        super.onStop()
    }

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

    fun openUrl(url: String) = leaveFor(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun leaveFor(i: Intent) {
        expectingLeave = true
        runCatching { startActivity(i) }.onFailure { expectingLeave = false }
    }

    fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Chataltul", text))
    }

    companion object {
        /** Kavana is on the screen (its check-ins show there instead of over another app). */
        @Volatile var visible = false
            private set
    }
}
