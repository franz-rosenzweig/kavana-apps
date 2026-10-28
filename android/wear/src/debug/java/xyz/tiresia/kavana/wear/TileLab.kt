package xyz.tiresia.kavana.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.tiles.renderer.TileRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Debug builds only: the tile, drawn by the same renderer the watch uses, for a made-up snapshot, on a screen of the app's own (the
 * tile lab, like the phone's widget lab), so a check can look at every state without the watch face's gestures:
 *   adb shell am start -n xyz.tiresia.kavana.debug/xyz.tiresia.kavana.wear.TileLab --es json '{"mode":"READY","catName":"Miso"}'
 * A tap goes to the phone, as on the real tile.
 */
class TileLab : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = intent.getStringExtra("json") ?: return finish()
        val snap = Json { ignoreUnknownKeys = true }.decodeFromString(WatchSnap.serializer(), json)
        val png = runCatching { assets.open("demo-window.webp").use { it.readBytes() } }.getOrNull()
        val m = resources.displayMetrics
        val dev = DeviceParametersBuilders.DeviceParameters.Builder()
            .setScreenWidthDp((m.widthPixels / m.density).toInt()).setScreenHeightDp((m.heightPixels / m.density).toInt())
            .setScreenDensity(m.density).setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
            .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS).build()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val renderer = TileRenderer(this, mainExecutor) { state -> scope.launch { Phone.act(this@TileLab, state.lastClickableId) } }
        renderer.inflateAsync(LayoutElementBuilders.Layout.fromLayoutElement(TileLayout.layout(this, snap, dev)), TileLayout.resources(png, "lab"), root)
    }
}
