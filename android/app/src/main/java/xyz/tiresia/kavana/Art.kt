package xyz.tiresia.kavana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loader = Executors.newSingleThreadExecutor()
    private val loading = ConcurrentHashMap.newKeySet<String>()

    fun cached(path: String): Bitmap? = cache.get(path)

    // Mipmaps keep the outlines smooth when a cat is drawn small (the Cats grid, the café).
    fun bitmap(ctx: Context, path: String): Bitmap? = cache.get(path)
        ?: runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()?.also { it.setHasMipMap(true); cache.put(path, it) }

    /** Decodes in the background, for the next frames (a living cat draws once it's there). */
    fun prefetch(ctx: Context, path: String) {
        if (cache.get(path) != null || !loading.add(path)) return
        val app = ctx.applicationContext
        loader.execute { try { bitmap(app, path) } finally { loading.remove(path) } }
    }

    fun thing(id: String) = "cafe/$id-1.webp"
    fun portrait(id: String) = cats[id]?.portrait ?: "cats/$id-1.webp"
    fun kitten(id: String) = cats[id]?.kitten ?: "cats/$id-kitten-1.webp"
    fun nap(id: String) = cats[id]?.nap ?: "cats/$id-nap-1.webp"

    /** Each cat's painting paths, from the engine's cats (cats.js), learned with every state: a repainted cat's have a new number. */
    @Volatile private var cats: Map<String, CatInfo> = emptyMap()
    fun learn(list: List<CatInfo>) { if (list.size != cats.size || list.any { cats[it.id]?.portrait != it.portrait }) cats = list.associateBy { it.id } }
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
