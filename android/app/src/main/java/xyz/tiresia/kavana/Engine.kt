package xyz.tiresia.kavana

import android.content.Context
import androidx.concurrent.futures.await
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox

/**
 * Kavana's engine: the web app's own engine.js (with lines.js, cats.js, news.js and the app's bridge.js),
 * run in Android's JavaScript sandbox, the isolated V8 that ships with the WebView. One isolate
 * holds the doc between calls. If the sandbox goes away (a WebView update, low memory), the next
 * call starts a new one and Repo loads the doc into it again from the file.
 */
class Engine(private val ctx: Context) {
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null

    /** True when this isolate is new and has no doc yet. */
    var fresh = true
        private set

    private suspend fun ready(): JavaScriptIsolate {
        isolate?.let { return it }
        val sb = sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(ctx.applicationContext).await().also { sandbox = it }
        val iso = sb.createIsolate()
        val code = FILES.joinToString("\n;\n") { f -> ctx.assets.open("js/$f").bufferedReader().use { it.readText() } }
        iso.evaluateJavaScriptAsync("$code\n;'ok'").await()
        isolate = iso
        fresh = true
        return iso
    }

    suspend fun eval(code: String): String {
        val iso = ready()
        return try {
            iso.evaluateJavaScriptAsync(code).await().also { fresh = false }
        } catch (e: Exception) {
            reset()
            throw e
        }
    }

    fun reset() {
        runCatching { isolate?.close() }
        isolate = null
        runCatching { sandbox?.close() }
        sandbox = null
        fresh = true
    }

    companion object {
        val FILES = listOf("lines.js", "engine.js", "cats.js", "news.js", "bridge.js")
        val supported: Boolean get() = JavaScriptSandbox.isSupported()
    }
}
