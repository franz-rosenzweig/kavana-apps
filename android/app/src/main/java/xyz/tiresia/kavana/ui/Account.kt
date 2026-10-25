package xyz.tiresia.kavana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.Account
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.Sync

// The account: sign in by email (a code, no password needed) or with a name and a password, and the
// account page (email, password, username, sign out, delete). The words follow the web's account
// page (web/app.js renderAccount), with "this phone" for "this browser".

/** The first row of Settings: who you're signed in as, or a way to sign in. */
@Composable
fun AccountRow(onOpen: () -> Unit) {
    val sync = KavanaApp.repo.sync
    val a by sync.account.collectAsStateWithLifecycle()
    val trouble by sync.trouble.collectAsStateWithLifecycle()
    Group {
        val acct = a
        Item(
            if (acct == null) "Sign In" else acct.display ?: acct.name,
            sub = if (acct == null) "Back up your history and sync it with the web" else if (trouble != null) "Not syncing: too big for the server" else "Backed up · syncs with your other devices",
            onClick = onOpen, leading = { Avatar(acct?.let { it.display ?: it.name }) },
        )
    }
}

@Composable
private fun Avatar(name: String?, big: Boolean = false) {
    val size = if (big) 52.dp else 36.dp
    Box(Modifier.size(size).background(if (name == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        if (name == null) Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(size * .6f))
        else Text(name.take(1).uppercase(), color = Color.White, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** What went wrong, in words (web/app.js accountTrouble). */
private fun trouble(e: Exception, wrong: String): String {
    if (e !is Sync.ApiError) return "Can't reach Kavana. Check your connection and try again."
    return when {
        e.status == 401 -> wrong
        e.status == 429 -> "Too many tries. Wait a while, then try again."
        e.error == "email" -> "That doesn't look like an email address."
        e.error == "taken" -> "Another account has that email."
        e.error == "mail" -> "Couldn't send the email just now. Try again in a minute."
        e.error == "password" -> "Pick a password of at least 8 characters."
        e.error == "full" -> "Kavana has all the accounts it can hold for now. Try again another day."
        else -> "Can't reach Kavana. Check your connection and try again."
    }
}

@Composable
fun AccountScreen(onBack: () -> Unit) {
    val sync = KavanaApp.repo.sync
    val a by sync.account.collectAsStateWithLifecycle()
    val emailOn by sync.emailOn.collectAsStateWithLifecycle()
    val asker = remember { Asker() }
    LaunchedEffect(Unit) { sync.config() } // email is on unless the server says it isn't
    DisposableEffect(Unit) { onDispose { asker.cancel() } } // a question left open goes away with the page
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp).imePadding()) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Settings")
        }
        val acct = a
        if (acct == null) SignIn(emailOn, onDone = onBack) else SignedIn(acct, emailOn, asker, onGone = onBack)
    }
    AskHost(asker)
}

@Composable
private fun SignIn(emailOn: Boolean?, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    val sync = repo.sync
    val scope = accountScope()
    var tab by rememberSaveable { mutableStateOf("login") }
    var wayPicked by rememberSaveable { mutableStateOf("email") }
    var email by rememberSaveable { mutableStateOf("") }
    var codeFor by rememberSaveable { mutableStateOf<String?>(null) } // the address the last code went to
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val signup = tab == "signup"
    val way = if (emailOn == false) "name" else wayPicked

    fun signedIn(created: Boolean) {
        onDone()
        repo.toasts.tryEmit(if (created) "Account made. Your history is backed up." else "Signed in. Your history is here.")
    }

    LargeTitle(if (signup) "Create Account" else "Sign In")
    if (way == "email") {
        fun go() {
            if (busy) return
            err = null; busy = true
            scope.launch {
                try {
                    val to = codeFor
                    if (to == null) {
                        sync.emailCode(email.trim())
                        codeFor = email.trim().lowercase()
                    } else {
                        val created = sync.emailSignIn(to, code)
                        code = ""; codeFor = null
                        signedIn(created)
                    }
                } catch (e: Exception) {
                    err = trouble(e, "That code doesn't match, or it's used up. Check it, or send a new one.")
                } finally { busy = false }
            }
        }
        Group(foot = if (codeFor != null) "We sent a 6-digit code to $codeFor. It works for 10 minutes. Not there? Look in spam, or send a new one."
            else "We'll email you a 6-digit code. If you're new, it makes your account, with no password to remember. Your history on this phone comes with you.") {
            FieldRow("Email", email, { email = it.take(254) }, "you@example.com", KeyboardType.Email, ContentType.EmailAddress,
                enabled = codeFor == null, imeAction = if (codeFor == null) ImeAction.Go else ImeAction.Next, onGo = { go() })
            if (codeFor != null) {
                GroupDivider()
                FieldRow("Code", code, { code = it.filter { c -> c.isDigit() || c == ' ' }.take(7) }, "6 digits", KeyboardType.NumberPassword, ContentType.SmsOtpCode,
                    imeAction = ImeAction.Go, onGo = { go() }, focus = true)
            }
        }
        Problem(err)
        BigButton(if (codeFor == null) "Email Me a Code" else if (signup) "Create Account" else "Sign In", enabled = !busy) { go() }
        if (codeFor != null) TextButton(onClick = {
            err = null
            scope.launch { try { sync.emailCode(codeFor!!); repo.toasts.tryEmit("Sent a new code.") } catch (e: Exception) { err = trouble(e, "") } }
        }, Modifier.fillMaxWidth()) { Text("Send a new code") }
        TextButton(onClick = { err = null; if (codeFor != null) { codeFor = null; code = "" } else wayPicked = "name" }, Modifier.fillMaxWidth()) {
            Text(if (codeFor != null) "Use a different email" else "Use a name and password instead")
        }
    } else {
        fun go() {
            if (busy) return
            err = null; busy = true
            scope.launch {
                try {
                    sync.signIn(tab, name, password)
                    password = ""
                    signedIn(signup)
                } catch (e: Exception) {
                    val why = (e as? Sync.ApiError)?.error
                    err = when (why) {
                        "taken" -> "That name is taken. Try another one."
                        "name" -> "Names are 2 to 24 letters or digits (dots, dashes and underscores are OK too)."
                        else -> trouble(e, if (emailOn != false) "That name and password don't match. Forgot your password? Sign in with an email code instead, then set a new one." else "That name and password don't match.")
                    }
                } finally { busy = false }
            }
        }
        Seg(listOf("login" to "Sign In", "signup" to "Create Account"), tab, Modifier.padding(top = 8.dp)) { tab = it; err = null }
        Group(foot = if (signup) "Your history on this phone comes with you. Pick a name your friends will recognize, and a password of at least 8 characters."
            else "Signing in brings your history here, and anything you did on this phone comes along.") {
            FieldRow("Name", name, { name = it.take(254) }, if (signup || emailOn == false) "Required" else "Name or email", KeyboardType.Email,
                if (signup) ContentType.NewUsername else ContentType.Username, imeAction = ImeAction.Next)
            GroupDivider()
            FieldRow("Password", password, { password = it.take(200) }, "Required", KeyboardType.Password,
                if (signup) ContentType.NewPassword else ContentType.Password, secret = !show, imeAction = ImeAction.Go, onGo = { go() }) {
                TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            }
        }
        Problem(err)
        BigButton(if (signup) "Create Account" else "Sign In", enabled = !busy && name.isNotBlank() && password.isNotEmpty()) { go() }
        if (emailOn != false) TextButton(onClick = { wayPicked = "email"; err = null }, Modifier.fillMaxWidth()) { Text("Use email instead") }
    }
}

@Composable
private fun SignedIn(a: Account, emailOn: Boolean?, asker: Asker, onGone: () -> Unit) {
    val repo = KavanaApp.repo
    val sync = repo.sync
    val scope = accountScope()
    val trouble by sync.trouble.collectAsStateWithLifecycle()
    val pw = a.password
    fun toast(t: String) = repo.toasts.tryEmit(t)

    // A code we emailed, asked for until it matches (five tries, like the server's).
    suspend fun askCode(email: String, label: String, use: suspend (String) -> Unit): Boolean {
        repeat(5) {
            val c = asker.ask("Check Your Email", "We sent a 6-digit code to $email. It works for 10 minutes.", label, input = "6 digits", kind = Kind.Code)
            if (c.isNullOrBlank()) return false
            try { use(c); return true } catch (e: Exception) {
                if (e !is Sync.ApiError || e.status != 401) { toast(trouble(e, "")); return false }
                toast("That code doesn't match. Try again.")
            }
        }
        return false
    }

    LargeTitle("Account")
    Group {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(a.display ?: a.name, big = true)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(a.display ?: a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (trouble != null) "This phone's history is too big for the server, so it isn't syncing. It's all still here on this phone." else "Your history is backed up and syncs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Group(foot = when {
        a.email != null && pw -> "On another device, sign in with a code we email you, or with your email (or username) and password."
        a.email != null -> "On another device, sign in with a code we email you. Set a password if you'd rather type one."
        emailOn != false -> "On another device, sign in with your username and password. Add your email to sign in with a code instead, and to get back in if you forget your password."
        else -> "On another device, sign in with your username and password."
    }) {
        if (a.email != null || emailOn != false) {
            Item("Email", onClick = {
                scope.launch {
                    val had = a.email
                    val email = asker.ask(if (had != null) "Change Your Email" else "Add Your Email",
                        if (had != null) "It's $had now. We'll send a code to the new one."
                        else "Then you can sign in with a code we email you, and get back in if you forget your password. It's only used for that.",
                        "Send Code", input = "you@example.com", kind = Kind.Email)?.trim()?.lowercase()
                    if (email.isNullOrEmpty()) return@launch
                    try {
                        if (sync.addEmail(email) == null) { toast("That's your email already."); return@launch }
                    } catch (e: Exception) { toast(trouble(e, "")); return@launch }
                    if (askCode(email, if (had != null) "Change Email" else "Add Email") { sync.confirmEmail(email, it) }) toast(if (had != null) "Email changed." else "Email added.")
                }
            }) { Text(a.email ?: "Add", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            GroupDivider()
        }
        Item("Password", onClick = {
            scope.launch {
                var current: String? = null
                if (pw && !sync.freshEmailSignIn()) {
                    current = asker.ask("Change Your Password",
                        if (a.email != null) "Type the one you have now first. Forgot it? Sign out, sign in with an email code, and come back here within 15 minutes." else "Type the one you have now first.",
                        "Next", input = "Current password", kind = Kind.Password) ?: return@launch
                    if (current.isEmpty()) return@launch
                }
                val n = asker.ask(if (pw) "New Password" else "Set a Password",
                    if (pw) "At least 8 characters. Your other devices will ask you to sign in again."
                    else "At least 8 characters. Then you can sign in by typing your email (or username) and this password, as well as with a code.",
                    if (pw) "Change" else "Set Password", input = "New password", kind = Kind.NewPassword)
                if (n.isNullOrEmpty()) return@launch
                if (n.length < 8) { toast("Pick a password of at least 8 characters."); return@launch }
                try {
                    sync.setPassword(n, current)
                    toast(if (pw) "Password changed. Your other devices will ask you to sign in again." else "Password set.")
                } catch (e: Exception) { toast(trouble(e, "That password doesn't match.")) }
            }
        }) { Text(if (pw) "Change" else "Set one", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        GroupDivider()
        Item("Username", chevron = false) { Text(a.name, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
    Group {
        Item("Sign Out", chevron = false, onClick = {
            scope.launch {
                if (asker.ask("Sign Out?", "Your history is safe in your account, and it leaves this phone.", "Sign Out") == null) return@launch
                sync.signOut()
                onGone()
                toast("Signed out.")
            }
        }, labelColor = MaterialTheme.colorScheme.primary)
        GroupDivider()
        Item("Delete My Account", chevron = false, onClick = {
            scope.launch {
                if (!pw) { // no password: an emailed code makes sure it's you
                    if (asker.ask("Delete Your Account?", "This deletes everything of yours on the server. Your history stays on this phone. We'll email you a code to make sure it's you.", "Send Code", danger = true) == null) return@launch
                    val sent = try { sync.accountCode() } catch (e: Exception) { toast(trouble(e, "")); return@launch }
                    if (askCode(sent, "Delete") { sync.deleteAccount("code" to it) }) { onGone(); toast("Account deleted.") }
                    return@launch
                }
                val p = asker.ask("Delete Your Account?", "This deletes everything of yours on the server. Your history stays on this phone. Type your password to go ahead.",
                    "Delete", input = "Password", kind = Kind.Password, danger = true)
                if (p.isNullOrEmpty()) return@launch
                try {
                    sync.deleteAccount("password" to p)
                    onGone()
                    toast("Account deleted.")
                } catch (e: Exception) { toast(if (e is Sync.ApiError && e.status == 401) "That password doesn't match." else "Couldn't delete it. Check your connection.") }
            }
        }, labelColor = MaterialTheme.colorScheme.error)
    }
}

/**
 * Where the account's actions run: the app's own scope on the main thread, not the page's. Signing in
 * turns the page into the account page and signing out turns it back, which would cancel the page's
 * own coroutines halfway (before the first sync, or before the history leaves the phone).
 */
private fun accountScope() = object {
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = KavanaApp.repo.scope.launch(kotlinx.coroutines.Dispatchers.Main, block = block)
}

/** A labelled field in a group row: Email, Code, Name, Password. */
@Composable
private fun FieldRow(
    label: String, value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, content: ContentType,
    enabled: Boolean = true, secret: Boolean = false, imeAction: ImeAction = ImeAction.Next, onGo: () -> Unit = {}, focus: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val fr = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
        TextField(value, onChange, placeholder = { Text(hint) }, singleLine = true, enabled = enabled, colors = plainField(),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = imeAction, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
            keyboardActions = KeyboardActions(onGo = { onGo() }, onDone = { onGo() }),
            modifier = Modifier.weight(1f).focusRequester(fr).semantics { contentType = content })
        trailing()
    }
}

@Composable
private fun Problem(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

// ---------- questions in a dialog, one after another (the web's ask()) ----------

enum class Kind { Text, Email, Code, Password, NewPassword }

private class Ask(val title: String, val message: String, val ok: String, val input: String?, val kind: Kind, val danger: Boolean, val done: CompletableDeferred<String?>)

/** Asks with an AlertDialog and waits: the text typed ("" with no field), or null for Cancel. */
class Asker {
    private var req by mutableStateOf<Ask?>(null)

    /** The page went away: a question still open is answered Cancel. */
    fun cancel() { req?.done?.complete(null) }

    suspend fun ask(title: String, message: String, ok: String, input: String? = null, kind: Kind = Kind.Text, danger: Boolean = false): String? {
        val d = CompletableDeferred<String?>()
        req = Ask(title, message, ok, input, kind, danger, d)
        return try { d.await() } finally { req = null }
    }

    @Composable
    fun Host() {
        val r = req ?: return
        var text by remember(r) { mutableStateOf("") }
        val fr = remember(r) { FocusRequester() }
        AlertDialog(
            onDismissRequest = { r.done.complete(null) },
            title = { Text(r.title) },
            text = {
                Column {
                    Text(r.message)
                    if (r.input != null) {
                        LaunchedEffect(r) { runCatching { fr.requestFocus() } }
                        val secret = r.kind == Kind.Password || r.kind == Kind.NewPassword
                        OutlinedTextField(text, { text = it.take(254) }, placeholder = { Text(r.input) }, singleLine = true,
                            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = when (r.kind) {
                                Kind.Email -> KeyboardType.Email; Kind.Code -> KeyboardType.NumberPassword; Kind.Password, Kind.NewPassword -> KeyboardType.Password; else -> KeyboardType.Text
                            }),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(fr).semantics {
                                contentType = when (r.kind) {
                                    Kind.Email -> ContentType.EmailAddress; Kind.Code -> ContentType.SmsOtpCode; Kind.Password -> ContentType.Password
                                    Kind.NewPassword -> ContentType.NewPassword; else -> ContentType.Username
                                }
                            })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { r.done.complete(if (r.input != null) text else "") }) {
                    Text(r.ok, color = if (r.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { r.done.complete(null) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AskHost(a: Asker) = a.Host()
