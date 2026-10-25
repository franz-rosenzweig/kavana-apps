package xyz.tiresia.kavana

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The account, and the doc synced with it (the web's data.js, the same server and the same rules).
 * Without an account nothing leaves the phone. Signed in, every change goes up a moment later, and
 * what your other devices did comes back when Kavana opens and every 30 seconds while it's open,
 * merged by the engine (E.merge) exactly as the web merges it. The session is the server's cookie,
 * kept in the app's own preferences like a browser keeps it.
 */
class Sync(private val repo: Repo) {
    private val prefs = repo.prefs

    val account = MutableStateFlow(prefs.getString("account", null)?.let { runCatching { KJson.decodeFromString(Account.serializer(), it) }.getOrNull() })
    /** "too big" while the server won't take this phone's copy, even folded. Settings says so until a sync goes through. */
    val trouble = MutableStateFlow<String?>(null)
    /** Whether the server sends email (GET /api/config); null until asked. Without it, only a name and a password. */
    val emailOn = MutableStateFlow<Boolean?>(null)

    private var rev: Long
        get() = prefs.getLong("rev", 0)
        set(v) = prefs.edit().putLong("rev", v).apply()
    private var cookie: String?
        get() = prefs.getString("cookie", null)
        set(v) = prefs.edit().putString("cookie", v).apply()

    /** The server: the web app's own, or (debug builds, for tests) the sandbox `--es server <url>` picked. */
    val server: String get() = (if (BuildConfig.DEBUG) prefs.getString("server", null) else null) ?: BuildConfig.SERVER

    class ApiError(val status: Int, val error: String?, val body: JsonObject?) : Exception(error ?: "HTTP $status")

    /** One call to the server. Throws ApiError for an answer that isn't OK, IOException when it can't be reached. */
    suspend fun api(method: String, path: String, body: Any? = null): JsonObject? = withContext(Dispatchers.IO) {
        val c = URL(server + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            cookie?.let { c.setRequestProperty("Cookie", "$COOKIE=$it") }
            if (method != "GET") {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                val text = if (body is String) body else toJson(body ?: emptyMap<String, Any>()).toString()
                c.outputStream.use { it.write(text.toByteArray()) }
            }
            val code = c.responseCode
            for ((k, v) in c.headerFields) if (k != null && k.equals("Set-Cookie", ignoreCase = true)) v.forEach(::takeCookie)
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
            val data = if (code == 204 || text.isBlank()) null else runCatching { KJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (code !in 200..299) throw ApiError(code, data.str("error"), data)
            data
        } finally {
            c.disconnect()
        }
    }

    /** "kavana=<token>; Path=/; Max-Age=…": a new session, a renewed one, or (empty, Max-Age=0) none. */
    private fun takeCookie(h: String) {
        if (!h.startsWith("$COOKIE=")) return
        val value = h.substringBefore(';').substringAfter('=')
        val age = Regex("Max-Age=(\\d+)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.toLongOrNull()
        cookie = if (value.isEmpty() || age == 0L) null else value
    }

    // ---------- the sync ----------

    private var timer: Job? = null
    private val syncing = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /** After a change: send it up in a moment (several changes in a row go as one). */
    fun queue() {
        if (account.value == null) return
        timer?.cancel()
        timer = repo.scope.launch { delay(700); push() }
    }

    suspend fun push() {
        if (account.value == null) return
        if (!syncing.compareAndSet(false, true)) { again.set(true); return }
        var squeezed = false
        try {
            for (i in 0 until 4) {
                try {
                    val r = api("POST", "api/sync", "{\"rev\":$rev,\"doc\":${repo.docJson()}}")
                    rev = r.long("rev") ?: rev
                    trouble.value = null
                    break
                } catch (e: ApiError) {
                    if (e.status == 409 && e.body != null) { // another device synced first: merge theirs in and try again
                        merge(e.body["doc"], e.body.long("rev") ?: rev)
                        continue
                    }
                    if (e.status == 413) { if (squeeze(squeezed)) { squeezed = true; continue } else break }
                    if (e.status == 401) signedOutElsewhere()
                    break
                } catch (e: IOException) {
                    // Offline, or a doc past what the server reads at all: it cuts the connection instead
                    // of answering, which looks like being offline, except that the doc is too big.
                    val big = repo.act(op("size"))?.result.obj?.get("big")?.jsonPrimitive?.booleanOrNull == true
                    if (big && squeeze(squeezed)) { squeezed = true; continue }
                    break
                }
            }
        } finally {
            syncing.set(false)
            if (again.getAndSet(false)) queue()
        }
    }

    /** Too big: fold the oldest blocks into the past and try once more; the second time, say so. */
    private suspend fun squeeze(already: Boolean): Boolean {
        if (already) { trouble.value = "too big"; return false }
        repo.act(op("squeeze"))
        return true
    }

    /** What the other devices did. Nothing new is a 204. */
    suspend fun pull() {
        if (account.value == null || syncing.get()) return
        try {
            val r = api("GET", "api/sync?rev=$rev") ?: return
            if (merge(r["doc"], r.long("rev") ?: rev)) queue()
        } catch (e: ApiError) {
            if (e.status == 401) signedOutElsewhere()
        } catch (_: IOException) {
        }
    }

    /** The server's copy merged into this phone's; true if the result has something the server's doesn't. */
    private suspend fun merge(theirs: JsonElement?, newRev: Long, adopt: Boolean = false): Boolean {
        val out = repo.act(op("merge", "doc" to theirs, "adopt" to adopt)) ?: return false
        if (out.error != null) { Log.w(Repo.TAG, "merge: ${out.error}"); return false }
        rev = newRev
        return out.result.obj?.get("mineToo")?.jsonPrimitive?.booleanOrNull == true
    }

    /** Kavana came to the front: who you are now (a password changed elsewhere signs you out), then what's new. */
    fun opened() {
        if (account.value == null) return
        repo.scope.launch { refreshMe(); pull() }
    }

    // ---------- signing in and out ----------

    private fun setAccount(me: JsonObject): Account {
        val a = KJson.decodeFromJsonElement(Account.serializer(), JsonObject(me - "created"))
        prefs.edit().putString("account", KJson.encodeToString(Account.serializer(), a)).apply()
        account.value = a
        return a
    }

    /** After any sign-in, this phone's history comes along into the account. */
    private suspend fun joined(me: JsonObject, existing: Boolean) {
        prefs.edit().remove("emailAt").apply()
        setAccount(me)
        try {
            val r = api("GET", "api/sync?rev=-1")
            if (r != null) merge(r["doc"], r.long("rev") ?: 0, adopt = existing)
        } catch (_: Exception) {
        }
        push()
    }

    suspend fun config(): Boolean? {
        emailOn.value?.let { return it }
        return try { (api("GET", "api/config").obj?.get("email")?.jsonPrimitive?.booleanOrNull == true).also { emailOn.value = it } } catch (_: Exception) { null }
    }

    /** kind "signup" or "login" (a name or an email, and the password). */
    suspend fun signIn(kind: String, name: String, password: String) {
        val me = api("POST", if (kind == "signup") "api/signup" else "api/login", mapOf("name" to name, "password" to password))!!
        joined(me, kind == "login")
    }

    /** By email: a code, which signs in, or makes the account if there isn't one. */
    suspend fun emailCode(email: String) { api("POST", "api/email/code", mapOf("email" to email)) }

    /** True if the code made a new account. */
    suspend fun emailSignIn(email: String, code: String): Boolean {
        val r = api("POST", "api/email/signin", mapOf("email" to email, "code" to code))!!
        val created = r["created"]?.jsonPrimitive?.booleanOrNull == true
        joined(r, !created)
        prefs.edit().putLong("emailAt", System.currentTimeMillis()).apply() // for 15 minutes a new password doesn't need the old one
        return created
    }

    /** A code to a new address; null if it's this account's already (else where the code went). */
    suspend fun addEmail(email: String): String? {
        val r = api("POST", "api/account/email", mapOf("email" to email))!!
        if (r["name"] != null) { setAccount(r); return null }
        return r.str("sent") ?: email
    }

    suspend fun confirmEmail(email: String, code: String) { setAccount(api("POST", "api/account/email/confirm", mapOf("email" to email, "code" to code))!!) }

    suspend fun setPassword(password: String, current: String?) {
        setAccount(api("POST", "api/account/password", mapOf("password" to password, "current" to current))!!)
    }

    fun freshEmailSignIn() = System.currentTimeMillis() - prefs.getLong("emailAt", 0) < 14 * 60_000

    /** For an account without a password: a code to its email, which stands in for it. Returns the address. */
    suspend fun accountCode(): String = api("POST", "api/account/code").str("sent") ?: ""

    suspend fun refreshMe() {
        if (account.value == null) return
        try { api("GET", "api/me")?.let { setAccount(it) } } catch (e: ApiError) { if (e.status == 401) signedOutElsewhere() } catch (_: IOException) {}
    }

    /** The account's history leaves the phone on sign out (it's safe on the server). */
    suspend fun signOut() {
        push()
        try { api("POST", "api/logout") } catch (_: Exception) {}
        forget()
        repo.act(op("empty"))
    }

    /** Deletes the server's copy; this phone keeps its own. proof: "password" or "code". */
    suspend fun deleteAccount(proof: Pair<String, String>) {
        api("POST", "api/account/delete", mapOf(proof))
        forget()
    }

    /** The password was changed on another device, or the account removed. */
    private fun signedOutElsewhere() = forget()

    private fun forget() {
        timer?.cancel()
        prefs.edit().remove("account").remove("cookie").remove("emailAt").putLong("rev", 0).apply()
        account.value = null
        trouble.value = null
    }

    companion object {
        const val COOKIE = "kavana"
    }
}

private fun JsonObject?.long(key: String): Long? = this?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull }
