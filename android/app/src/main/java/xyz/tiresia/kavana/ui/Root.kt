package xyz.tiresia.kavana.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.Sound
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.op

enum class Tab(val label: String, val icon: ImageVector) {
    Focus("Focus", Icons.Rounded.TrackChanges),
    Later("Later", Icons.Rounded.Checklist),
    Cafe("Café", Icons.Rounded.LocalCafe),
    Cats("Cats", Icons.Rounded.Pets),
    Settings("Settings", Icons.Rounded.Settings),
}

/** The start form, kept above the tabs so Later's "Start" can fill it in. */
class StartForm {
    var intention by mutableStateOf("")
    var step by mutableStateOf("")
    /** 5, 25 or 50, or 0 for Custom. */
    var minutes by mutableIntStateOf(25)
    var custom by mutableStateOf("40")
    /** null: the last style used (settings.style). */
    var style by mutableStateOf<String?>(null)
    /** "timer" or "stopwatch"; null: the last one used (settings.mode). */
    var mode by mutableStateOf<String?>(null)
    /** A tag, or "" for none; null: the last one used (settings.tag). */
    var tag by mutableStateOf<String?>(null)
    var fromLater by mutableStateOf<String?>(null)
}

@Composable
fun Root(act: MainActivity) {
    val repo = KavanaApp.repo
    val st by repo.state.collectAsStateWithLifecycle()
    val broken by repo.broken.collectAsStateWithLifecycle()
    val s = st
    if (s == null) { Splash(broken); return }

    var welcomed by rememberSaveable { mutableStateOf(repo.prefs.getBoolean("welcomed", false)) }
    if (!welcomed) {
        Welcome(act, s) { repo.prefs.edit().putBoolean("welcomed", true).apply(); welcomed = true }
        return
    }

    val now = rememberNow(s)
    var tab by rememberSaveable { mutableStateOf(Tab.Focus) }
    var catsPane by rememberSaveable { mutableStateOf("cats") }
    var breathing by rememberSaveable { mutableStateOf(false) }
    var news by remember { mutableStateOf(false) }
    var sounds by remember { mutableStateOf(false) }
    // Sounds play while a block runs (not paused) or during a mindful minute, and stop otherwise.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val playable = SoundMix.playable(s)
    val wantSound = playable.isNotEmpty() && ((s.view.active != null && s.view.active.paused == null) || breathing)
    LaunchedEffect(wantSound, playable) { if (wantSound) Sound.play(ctx, playable) else Sound.stop() }
    val form = remember { StartForm() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { repo.toasts.collect { snack.showSnackbar(it) } }

    // The next thing the engine has to notice (a check-in coming due, the end, a break's end).
    // The alarm does this too; this is for when Kavana is open and the alarm is a little late.
    val a = s.view.active
    val nextAt = listOfNotNull(a?.takeIf { it.paused == null }?.let { minOf(it.next ?: it.end, it.end) }, a?.pauseEnds, s.view.onBreak?.end).minOrNull()
    LaunchedEffect(nextAt) {
        if (nextAt != null) {
            delay((nextAt - System.currentTimeMillis()).coerceAtLeast(0) + 400)
            repo.act(op("tick"))
        }
    }

    val soundSheet = @Composable { if (sounds) SoundSheet(s, onShop = { breathing = false; tab = Tab.Cats; catsPane = "shop" }) { sounds = false } }
    if (breathing && s.view.active == null) {
        BreatheScreen(s, onSound = { sounds = true }, onStart = { breathing = false; tab = Tab.Focus }, onLeave = { breathing = false })
        soundSheet()
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = {
                            val n = when (t) { Tab.Later -> s.view.later.size; Tab.Cats -> s.toCollect; else -> 0 }
                            if (n > 0) BadgedBox(badge = { Badge { Text("$n") } }) { Icon(t.icon, null) } else Icon(t.icon, null)
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            when (tab) {
                Tab.Focus -> FocusTab(s, now, form, act, onBreathe = { breathing = true }, onNews = { news = true }, onSound = { sounds = true })
                Tab.Later -> LaterTab(s) { item -> form.intention = item.text; form.fromLater = item.id; tab = Tab.Focus }
                Tab.Cafe -> CafeTab(s)
                Tab.Cats -> CatsTab(s, act, catsPane) { catsPane = it }
                Tab.Settings -> SettingsTab(s, act) { tab = it }
            }
        }
    }
    Sheets(s, act)
    soundSheet()
    if (news) NewsSheet(s, onGo = { go ->
        when (go) { "shop", "missions" -> { tab = Tab.Cats; catsPane = go }; "cafe" -> tab = Tab.Cafe; else -> tab = Tab.Focus }
    }) {
        news = false
        s.news.firstOrNull()?.let { repo.prefs.edit().putString("newsSeen", it.id).apply() }
    }
}

/** The time, every second while a block or a break runs. */
@Composable
fun rememberNow(s: UiState): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val live = s.view.active != null || s.view.onBreak != null // a pause counts down too
    LaunchedEffect(live) {
        now = System.currentTimeMillis()
        while (live) {
            delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun Splash(broken: String?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        if (broken == null) CircularProgressIndicator()
        else Text(
            if (broken == "nosandbox") "Kavana needs a newer Android System WebView. Update it from the Play Store, then open Kavana again."
            else "Kavana couldn't start: $broken",
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
        )
    }
}
