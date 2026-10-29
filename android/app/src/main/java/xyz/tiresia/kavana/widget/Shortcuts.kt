package xyz.tiresia.kavana.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.R

/** The launcher's long-press shortcuts: Start 25, Start 50, Park a thought, Open the café. */
object Shortcuts {
    fun publish(ctx: Context) {
        runCatching {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
            fun go(id: String, short: String, long: String, icon: Int, what: String, min: Int = 0) = ShortcutInfo.Builder(ctx, id)
                .setShortLabel(short).setLongLabel(long).setIcon(Icon.createWithResource(ctx, icon))
                .setIntent(Intent(ctx, ShortcutActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("do", what).putExtra("min", min)).build()
            sm.dynamicShortcuts = listOf(
                go("start25", "Start 25", "Start 25 minutes", R.drawable.sc_start, "start", 25),
                go("start50", "Start 50", "Start 50 minutes", R.drawable.sc_start, "start", 50),
                go("park", "Park a thought", "Park a thought", R.drawable.sc_park, "park"),
                go("cafe", "Open the café", "Open the café", R.drawable.sc_cafe, "cafe"),
            )
        }
    }
}

/** Does what a shortcut says and goes; it has no screen of its own. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("do")) {
            "start" -> {
                val m = intent.getIntExtra("min", 25)
                KavanaApp.repo.scope.launch { WidgetOps.start(m); WidgetUpdater.refresh(applicationContext) }
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            "park" -> startActivity(Intent(this, ParkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "cafe" -> startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "cafe").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
