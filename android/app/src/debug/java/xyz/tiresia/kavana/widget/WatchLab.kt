package xyz.tiresia.kavana.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import xyz.tiresia.kavana.KJson
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.settings

/**
 * Debug builds only: plays the watch for tools/check-widgets.py's `watch` phase. A tap from the watch arrives here as the
 * Wearable listener would hand it over, with no watch and no Play services needed:
 *
 *     am broadcast -f 32 -a xyz.tiresia.kavana.DEBUG_WATCH -p xyz.tiresia.kavana.debug --es json '{"op":"pause"}'
 *
 * `--ez overtime true` turns "Count extra time" on first.
 */
class WatchLab : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val done = goAsync()
        KavanaApp.repo.scope.launch {
            try {
                if (intent.hasExtra("overtime")) KavanaApp.repo.act(settings("overtime" to intent.getBooleanExtra("overtime", false)))
                intent.getStringExtra("json")?.let { WatchSync.handle(app, KJson.parseToJsonElement(it).jsonObject) }
                Log.i("ChataltulWatch", "lab: handled ${intent.getStringExtra("json")}")
            } finally {
                done.finish()
            }
        }
    }
}
