package xyz.tiresia.kavana

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.ui.CoverPage
import xyz.tiresia.kavana.ui.CoverScreen
import xyz.tiresia.kavana.ui.KavanaTheme

/**
 * Chataltul on a Galaxy Flip's cover screen, opened from one of its cover widgets (they start it on the cover's own
 * display). The cover is mostly a lock screen, so this shows over it; your Later list stays hidden until you unlock.
 * `page` picks the page ("Focus", "Today", "Pet", "Later"); `stop` asks to stop the block, as the Window's Stop does.
 */
class CoverActivity : ComponentActivity() {
    private var page by mutableStateOf(CoverPage.Focus)
    private var stop by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        read(intent)
        xyz.tiresia.kavana.ui.SoundMix.mix // (made outside any composition, as MainActivity does)
        setContent { KavanaTheme { CoverScreen(page, stop) { stop = false } } }
        lifecycleScope.launch { KavanaApp.repo.act(op("tick")) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(i: Intent) {
        i.getStringExtra("page")?.let { p -> CoverPage.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }?.let { page = it } }
        if (i.getBooleanExtra("stop", false)) stop = true
    }

    override fun onStart() {
        super.onStart()
        MainActivity.shown(true)
        KavanaApp.repo.back(System.currentTimeMillis())
        KavanaApp.repo.sync.opened()
    }

    // Leaving the cover screen (it times out, or you swipe to another of its widgets) isn't leaving Chataltul: the phone is closed.
    override fun onStop() {
        MainActivity.shown(false)
        super.onStop()
    }
}
