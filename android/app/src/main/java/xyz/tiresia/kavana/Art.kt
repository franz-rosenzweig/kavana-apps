package xyz.tiresia.kavana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The web app's painted cats (assets/cats, assets/cafe), decoded once and kept for a while. */
object Art {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(path: String): Bitmap? = cache.get(path)

    fun bitmap(ctx: Context, path: String): Bitmap? = cache.get(path)
        ?: runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { cache.put(path, it) }

    fun portrait(id: String) = "cats/$id-1.webp"
    fun kitten(id: String) = "cats/$id-kitten-1.webp"
    fun nap(id: String) = "cats/$id-nap-1.webp"
    const val GONE = "cafe/gone-1.webp"
}

@Composable
fun art(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    val img by produceState(Art.cached(path)?.asImageBitmap(), path) {
        value = withContext(Dispatchers.IO) { Art.bitmap(ctx, path)?.asImageBitmap() }
    }
    return img
}
