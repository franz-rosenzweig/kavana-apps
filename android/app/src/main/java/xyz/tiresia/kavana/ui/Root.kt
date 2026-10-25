package xyz.tiresia.kavana.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.R
import xyz.tiresia.kavana.Sound
import xyz.tiresia.kavana.UiState
import xyz.tiresia.kavana.fmtMin
import xyz.tiresia.kavana.op
import kotlin.math.max
import kotlin.math.min

/** The screens. Goals and Shop are panes of Cats; the menu has the rest. */
enum class Tab(val label: String, val icon: ImageVector) {
    Focus("Focus", Icons.Rounded.TrackChanges),
    Later("Later", Icons.Rounded.Checklist),
    Cafe("Café", Icons.Rounded.LocalCafe),
    Cats("Cats", Icons.Rounded.Pets),
    Settings("Settings", Icons.Rounded.Settings),
}

/** The start form, kept above the screens so Later's "Start" and Again can fill it in. */
class StartForm {
    var intention by mutableStateOf("")
    var step by mutableStateOf("")
    /** 5 to 120 from the ring (or 5, 25, 50 from the sheet), or 0 for Custom. */
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
    var newsSeen by remember { mutableStateOf(repo.prefs.getString("newsSeen", null)) }
    val newsUnread = s.news.firstOrNull()?.id.let { it != null && it != newsSeen }
    var sounds by remember { mutableStateOf(false) }
    // Sounds play while a block runs (not paused) or during a mindful minute, and stop otherwise.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val playable = SoundMix.playable(s)
    val wantSound = playable.isNotEmpty() && ((s.view.active != null && s.view.active.paused == null) || breathing)
    LaunchedEffect(wantSound, playable) { if (wantSound) Sound.play(ctx, playable) else Sound.stop() }
    val form = remember { StartForm() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { repo.toasts.collect { snack.showSnackbar(it) } }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

    // The dive: a block that has just started (Start, or Again) opens out of the window as a sky.
    val dive = remember { Animatable(1f) }
    var seenBlock by remember { mutableStateOf(a?.id) }
    LaunchedEffect(a?.id) {
        val fresh = a != null && a.id != seenBlock && System.currentTimeMillis() - a.start < 5000
        seenBlock = a?.id
        if (fresh) {
            tab = Tab.Focus
            if (ValueAnimator.areAnimatorsEnabled()) { dive.snapTo(0f); dive.animateTo(1f, tween(1050, easing = CubicBezierEasing(.5f, 0f, .2f, 1f))) }
        } else dive.snapTo(1f)
    }

    val dark = isSystemInDarkTheme()
    val wall = wallFor(s.view.settings.theme, dark)
    val onSky = tab == Tab.Focus && a != null && !breathing
    val onWall = tab == Tab.Focus && a == null && s.view.onBreak == null && !breathing
    val p = if (a != null) runProgress(a, now) else 0f
    val darkBackdrop = when { onSky -> skyDusk(p, dark); onWall -> wall.lightInk; else -> dark }
    SideEffect { WindowCompat.getInsetsController(act.window, act.window.decorView).isAppearanceLightStatusBars = !darkBackdrop }

    val soundSheet = @Composable { if (sounds) SoundSheet(s, onShop = { breathing = false; tab = Tab.Cats; catsPane = "shop" }) { sounds = false } }
    androidx.activity.compose.BackHandler(enabled = breathing) { breathing = false }
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    DisposableEffect(Unit) { onDispose { if (!act.isChangingConfigurations && repo.state.value?.view?.active?.let { it.paused == null } != true) Sound.stop() } }
    if (breathing && s.view.active == null) {
        BreatheScreen(s, onSound = { sounds = true }, onStart = { breathing = false; tab = Tab.Focus }, onLeave = { breathing = false })
        soundSheet()
        return
    }

    fun go(to: String) {
        scope.launch { drawer.close() }
        when (to) {
            "focus" -> tab = Tab.Focus
            "cafe" -> tab = Tab.Cafe
            "cats", "missions", "shop" -> { tab = Tab.Cats; catsPane = to }
            "later" -> tab = Tab.Later
            "mindful" -> breathing = true
            "news" -> news = true
            "settings" -> tab = Tab.Settings
        }
    }
    val here = when (tab) { Tab.Cats -> catsPane; else -> tab.name.lowercase() }

    val walks = remember(s.cats) { s.cats.mapNotNull { c -> c.poses?.get("walk")?.let { c.id to it } }.toMap() }
    CompositionLocalProvider(LocalWalks provides walks) {
    ModalNavigationDrawer(
        drawerState = drawer, gesturesEnabled = drawer.isOpen,
        drawerContent = { Menu(s, here, newsUnread) { go(it) } },
    ) {
        val sky = Modifier.drawBehind {
            val d = dive.value
            if (d >= 1f) drawSky(p, a?.paused != null, dark) else {
                // the sky opens out from where the window was
                drawWall(wall)
                val r = 0.16f * min(size.width, size.height) + (1.5f * max(size.width, size.height) - 0.16f * min(size.width, size.height)) * d
                val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(size.width / 2, size.height * 0.44f), r)) }
                clipPath(path) { drawSky(p, a?.paused != null, dark) }
            }
        }
        Box(Modifier.fillMaxSize().then(when { onSky -> sky; onWall -> Modifier.wall(wall); else -> Modifier.background(MaterialTheme.colorScheme.background) })) {
            Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                    val under = Modifier.fillMaxSize().padding(top = 48.dp) // room for the menu button
                    when (tab) {
                        Tab.Focus -> FocusTab(s, now, form, act, wall, newsUnread, dive.value,
                            onNews = { news = true }, onGoals = { go("missions") }, onSettings = { tab = Tab.Settings }, onSound = { sounds = true })
                        Tab.Later -> Box(under) { LaterTab(s) { item -> form.intention = item.text; form.fromLater = item.id; tab = Tab.Focus } }
                        Tab.Cafe -> Box(under) { CafeTab(s) }
                        Tab.Cats -> Box(under) { CatsTab(s, act, catsPane) { catsPane = it } }
                        Tab.Settings -> Box(under) { SettingsTab(s, act) { tab = it } }
                    }
                    MenuButton(dot = newsUnread || s.toCollect > 0, dim = darkBackdrop, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) { scope.launch { drawer.open() } }
                }
            }
        }
    }
    Sheets(s, act, wall, form)
    }
    soundSheet()
    if (news) NewsSheet(s, onGo = { to -> go(to ?: "focus") }) {
        news = false
        s.news.firstOrNull()?.let { repo.prefs.edit().putString("newsSeen", it.id).apply(); newsSeen = it.id }
    }
}

/** The round button at the top left that opens the menu, with a dot when something's waiting. */
@Composable
private fun MenuButton(dot: Boolean, dim: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape)
            .background(if (dim) Color.White.copy(alpha = .18f) else MaterialTheme.colorScheme.surface.copy(alpha = .9f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Menu, "Menu", tint = if (dim) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(9.dp).background(LocalK.current.red, CircleShape))
    }
}

/** The menu: today and the streak on top, then every place in Kavana, and your cat at the bottom. */
@Composable
private fun Menu(s: UiState, here: String, newsUnread: Boolean, onGo: (String) -> Unit) {
    val k = LocalK.current
    ModalDrawerSheet(Modifier.width(310.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 18.dp)) {
            Row(Modifier.padding(start = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.kavana_icon), null, Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)))
                Spacer(Modifier.width(10.dp))
                Text("Kavana", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.padding(horizontal = 4.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val goal = s.view.settings.goal
                val today = s.stats.today
                Widget(Modifier.weight(1f), fmtMin(today), if (goal > 0) (if (today >= goal) "Goal reached!" else "of ${fmtMin(goal)} today") else "focused today") {
                    val f = (today.toFloat() / (if (goal > 0) goal else 60)).coerceIn(0f, 1f)
                    val track = k.red.copy(alpha = .18f)
                    Canvas(Modifier.size(34.dp)) {
                        val sw = 5.dp.toPx()
                        drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                        drawArc(k.red, -90f, 360f * f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
                val streak = s.stats.streak
                Widget(Modifier.weight(1f), if (streak == 1) "1 day" else "$streak days", if (streak > 0) "in a row" else "Start a streak today") {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(k.pink), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Pets, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                }
            }
            val missions = s.missions.day
            MenuItem("Focus", Icons.Rounded.TrackChanges, MaterialTheme.colorScheme.primary, here == "focus") { onGo("focus") }
            MenuItem("Café", Icons.Rounded.LocalCafe, k.blue, here == "cafe", if (s.catsOn) "🐟 ${s.treats.left}" else null) { onGo("cafe") }
            MenuItem("Cats", Icons.Rounded.Pets, k.pink, here == "cats", "${s.cats.count { it.met }}/${s.cats.count { it.limited == null }}") { onGo("cats") }
            MenuItem("Goals", Icons.Rounded.Flag, k.orange, here == "missions", if (s.toCollect > 0) "${s.toCollect} to collect" else if (missions.isNotEmpty()) "${missions.count { it.done }} of ${missions.size}" else null) { onGo("missions") }
            MenuItem("Shop", Icons.Rounded.ShoppingBag, k.green, here == "shop") { onGo("shop") }
            MenuItem("Later", Icons.Rounded.Checklist, k.orange, here == "later", s.view.later.size.takeIf { it > 0 }?.toString()) { onGo("later") }
            MenuItem("A mindful minute", Icons.Rounded.SelfImprovement, k.mint, false) { onGo("mindful") }
            MenuItem("Shelter cats", Icons.Rounded.Favorite, k.red, false) { onGo("shop") }
            if (s.news.isNotEmpty()) MenuItem("What's new", Icons.AutoMirrored.Rounded.Article, k.indigo, false, dot = newsUnread) { onGo("news") }
            MenuItem("Settings", Icons.Rounded.Settings, k.gray, here == "settings") { onGo("settings") }
            Spacer(Modifier.height(18.dp))
            if (s.catsOn) Box(Modifier.fillMaxWidth().padding(bottom = 0.dp), contentAlignment = Alignment.BottomCenter) {
                LiveCat(s.companion, "portrait", 150.dp, interactive = true)
            }
        }
    }
}

@Composable
private fun Widget(modifier: Modifier, big: String, small: String, art: @Composable () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        art()
        Spacer(Modifier.width(8.dp))
        Column {
            Text(big, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 18.sp)
            Text(small, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, tile: Color, on: Boolean, count: String? = null, dot: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(tile), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (count != null) Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        if (dot) Box(Modifier.size(9.dp).background(LocalK.current.red, CircleShape))
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
