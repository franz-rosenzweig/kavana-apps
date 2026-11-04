package xyz.tiresia.kavana.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: a made-up snapshot, for the emulator (which has no phone to send one):
 *   adb shell am broadcast -a xyz.tiresia.kavana.wear.DEBUG_SNAP -p xyz.tiresia.kavana.debug --es json '{"mode":"READY","catName":"Miso"}'
 * The window's picture is a painting of Miso from the app.
 */
class DebugSnap : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        val pic = runCatching { context.assets.open("demo-window.webp").use { it.readBytes() } }.getOrNull()
        WatchStore.put(context, json, pic)
    }
}
