package xyz.tiresia.kavana.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import xyz.tiresia.kavana.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

// The Cats tab, like Forest's collection, store and achievements in one place:
// - My Cats: every cat, in colour. The ones you haven't met yet are asleep, with how close you are.
// - Missions: the starter challenge, this month's challenge, today's and this week's missions, achievements.
// - Shop: café things, cats to adopt, café themes, sounds, shelter cats, and the event when there is one.

/** Something to buy: a café thing, a cat, a theme, a sound or a shelter cat. */
data class Offer(val id: String, val name: String, val kind: String)

/** The sounds in the shop (web/sounds.js KINDS; purring, rain, brown and white noise are free). */
val SOUNDS = listOf(
    Triple("cafe", "Café", "Murmur and the odd cup."), Triple("fire", "Fireplace", "A low fire that crackles."),
    Triple("waves", "Waves", "Slow surf on a beach."), Triple("birds", "Birds", "A quiet morning garden."),
    Triple("night", "Night", "Crickets on a warm night."), Triple("wind", "Wind", "Wind around the house."),
)

@Composable
fun CatsTab(s: UiState, act: MainActivity, pane: String, onPane: (String) -> Unit) {
    var sheetCat by remember { mutableStateOf<String?>(null) }
    var buying by remember { mutableStateOf<Offer?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 28.dp)) {
        LargeTitle("Cats") { TreatsChip(s.treats.left) }
        Seg(listOf("cats" to "My Cats", "missions" to if (s.toCollect > 0) "Missions · ${s.toCollect}" else "Missions", "shop" to "Shop"), pane, Modifier.padding(top = 8.dp)) { onPane(it) }
        when (pane) {
            "missions" -> MissionsPane(s)
            "shop" -> ShopPane(s, act, onCat = { sheetCat = it }) { buying = it }
            else -> MyCats(s) { sheetCat = it }
        }
    }
    sheetCat?.let { id -> CatSheet(s, id, onBuy = { buying = it }) { sheetCat = null } }
    buying?.let { o -> BuyDialog(s, o) { buying = null } }
}

// ---------- My Cats ----------

@Composable
private fun MyCats(s: UiState, onCat: (String) -> Unit) {
    val meet = s.cats.filter { !it.adopt }
    val metN = meet.count { it.met }
    val adopted = s.cats.count { it.adopt && it.met }
    Column(Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp)) {
        Text("$metN of ${meet.size} met" + if (adopted > 0) " · $adopted adopted" else "", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { metN.toFloat() / meet.size.coerceAtLeast(1) }, Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round, drawStopIndicator = {})
    }
    Head("Meet them by focusing")
    CatGrid(meet, s, onCat)
    Head("Adopt with treats")
    CatGrid(s.cats.filter { it.adopt && it.limited == null }, s, onCat)
    // Limited cats: while their event is on, or a few days before it (asleep, "in 3 days"), or once they're yours.
    val limited = s.cats.filter { it.limited != null && (it.met || s.forSale(it.id) || s.shop.soon?.id == it.limited) }
    if (limited.isNotEmpty()) {
        val ev = s.shop.season ?: s.shop.soon?.let { Season(it.id, it.name) }
        Head(ev?.let { "${eventIcon(it.id)} ${it.name}" } ?: "Limited")
        CatGrid(limited, s, onCat)
    }
    val sponsored = s.shop.shelter.filter { s.owns(it.id) }
    if (sponsored.isNotEmpty()) {
        Head("Shelter cats you sponsor")
        Grid(sponsored, 2) { c ->
            Card(onClick = { onCat(c.id) }, mine = true) {
                LiveCat(c.id, "portrait", 96.dp, shadow = false)
                Text(c.name, style = MaterialTheme.typography.titleSmall)
                Text("Sponsored", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun CatGrid(cats: List<CatInfo>, s: UiState, onCat: (String) -> Unit) {
    Grid(cats, 2) { c ->
        val asleep = !c.met && !c.adopt // not met yet: here, but asleep
        val coming = c.met && c.id == s.companion
        val soon = c.limited != null && !c.met && !s.forSale(c.id)
        Card(onClick = { onCat(c.id) }, mine = coming) {
            LiveCat(c.id, if (asleep || soon) "nap" else "portrait", 96.dp, shadow = false, label = c.name)
            Text(c.name, style = MaterialTheme.typography.titleSmall)
            when {
                coming -> Text("Coming next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                c.met -> Text(c.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                soon -> Text(s.shop.soon?.let { if (it.days == 1) "Here tomorrow" else "Here in ${it.days} days" } ?: "Not in the shop now",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                c.adopt -> Price(c.price, s.treats.left)
                else -> {
                    Text(c.how ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
                    Bar(c.have, c.need, Modifier.padding(top = 4.dp).fillMaxWidth(0.8f))
                }
            }
        }
    }
}

/** A cat, big and alive: the three stages you see in a block, and what it takes (or costs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatSheet(s: UiState, id: String, onBuy: (Offer) -> Unit, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    val shelter = s.shop.shelter.firstOrNull { it.id == id }
    val c = if (shelter != null) CatInfo(shelter.id, shelter.name, shelter.about, met = s.owns(shelter.id)) else s.cat(id)
    val asleep = shelter == null && !c.met && !c.adopt
    var pose by remember(id) { mutableStateOf(if (asleep) "nap" else "portrait") }
    var cat by remember { mutableStateOf<LivingCat?>(null) }
    // Not met yet: it wakes up to say hello.
    LaunchedEffect(id) { if (asleep) { delay(700); pose = "portrait"; delay(450) }; cat?.let { it.flick(0); it.flick(1) } }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LiveCat(c.id, pose, 180.dp, interactive = true, label = "${c.name}. Tap to make it hop, hold it to make it purr.", onCat = { cat = it })
            Text(c.name, style = MaterialTheme.typography.headlineSmall)
            Text(c.about, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (shelter == null) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("nap" to "Arrives", "kitten" to "Grows", "portrait" to "Comes home").forEach { (p, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LiveCat(c.id, p, 72.dp, shadow = false)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    when {
                        shelter != null -> {
                            Text(if (c.met) "You sponsor ${c.name}" else "Sponsor ${c.name}", style = MaterialTheme.typography.titleMedium)
                            Text("Stands for a real shelter cat at ${s.shop.shelterOrg.name}, and lives in your café.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        c.met && c.id == s.companion -> Text("Coming to your next block", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        c.met -> Button(onClick = { repo.launch(settings("companion" to c.id)); repo.toasts.tryEmit("${c.name} comes to your next block."); onDone() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Bring ${c.name} to My Next Block")
                        }
                        c.adopt && !s.forSale(c.id) -> Text(s.shop.soon?.takeIf { it.id == c.limited }?.let { "In the shop in ${if (it.days == 1) "1 day" else "${it.days} days"}, for one week." }
                            ?: "Only in the shop during its event.", style = MaterialTheme.typography.bodyMedium)
                        c.adopt -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Adopt ${c.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Price(c.price, s.treats.left)
                            }
                            Button(onClick = { onBuy(Offer(c.id, c.name, "cat")); onDone() }, enabled = s.treats.left >= c.price, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(if (s.treats.left >= c.price) "Adopt" else "${c.price - s.treats.left} more treats")
                            }
                        }
                        else -> {
                            Text(c.how ?: "", style = MaterialTheme.typography.titleMedium)
                            Bar(c.have, c.need, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            Text(progressText(c.have, c.need, c.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun progressText(have: Int, need: Int, unit: String?): String =
    if (unit == "min") "${fmtMin(have)} of ${fmtMin(need)}" else "$have of $need${unit?.let { " $it" } ?: ""}"

// ---------- Missions ----------

@Composable
private fun MissionsPane(s: UiState) {
    val repo = KavanaApp.repo
    fun collect(id: String) = repo.launch(op("claim", "id" to id)) { out ->
        val n = (out?.result as? JsonObject)?.get("treats")?.jsonPrimitive?.int
        if (out?.error == null && n != null) repo.toasts.tryEmit("🐟 +$n treats")
    }
    if (!s.starter.finished) Group("Starter Challenge", foot = "Seven first steps, 20 treats each, and 100 more for all of them.") {
        s.starter.steps.forEachIndexed { i, x ->
            if (i > 0) GroupDivider()
            RewardRow(x.text, x.have, x.goal, null, x.done, x.claimed, x.reward) { collect(x.id) }
        }
        GroupDivider()
        val a = s.starter.all
        RewardRow("Do all seven", s.starter.steps.count { it.done }, s.starter.steps.size, null, a.done, a.claimed, a.reward, strong = true) { collect(a.id) }
    }
    s.monthly.forEach { m ->
        val month = Instant.ofEpochMilli(m.from).atZone(ZoneId.systemDefault()).month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val days = ((m.to - s.now + 86_399_999) / 86_400_000).toInt()
        Group("$month Focus Challenge", foot = if (m.done) "Done for $month." else if (m.to < s.now) null else if (days == 1) "Last day." else "$days days left. A new challenge every month.") {
            RewardRow("Bring kittens home on ${m.need} days in $month", m.have, m.need, "days", m.done, m.claimed, m.reward, strong = true) { collect(m.id) }
        }
    }
    val ms = s.missions
    val hours = maxOf(1L, Math.round((ms.dayEnds - s.now) / 3_600_000.0))
    Group("Today's missions", foot = if (ms.dayClosed) "You cleared your history today, so today's missions are closed. New ones come at midnight." else "New missions in ${if (hours == 1L) "an hour" else "$hours hours"}.") {
        ms.day.forEachIndexed { i, m -> if (i > 0) GroupDivider(); MissionRow(m) }
    }
    val days = ((ms.weekEnds - s.now) / 86_400_000).toInt()
    Group("This week", foot = if (ms.weekClosed) "You cleared your history this week, so this week's mission is closed." else if (days <= 1) "A new one tomorrow." else "A new one in $days days.") {
        ms.week.forEachIndexed { i, m -> if (i > 0) GroupDivider(); MissionRow(m) }
    }
    val ach = s.achievements
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text("Achievements", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text("${ach.count { it.done }} of ${ach.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Ready to collect first, then in progress, then collected.
    val order = { a: Ach -> if (a.done && !a.claimed) 0 else if (!a.done) 1 else 2 }
    Grid(ach.sortedBy(order), 2) { a ->
        Card(onClick = null, mine = a.done && !a.claimed) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (a.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(achIcon(a.stat), null, tint = if (a.done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(a.name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Text(a.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, minLines = 2, maxLines = 3)
            when {
                a.claimed -> Text("✓ Collected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                a.done -> FilledTonalButton(onClick = { collect(a.id) }, modifier = Modifier.padding(top = 4.dp)) { Text("Collect 🐟 ${a.reward}") }
                else -> { Bar(a.have, a.goal, Modifier.fillMaxWidth(0.85f).padding(top = 8.dp)); Text("🐟 ${a.reward}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}

@Composable
private fun RewardRow(text: String, have: Int, goal: Int, unit: String?, done: Boolean, claimed: Boolean, reward: Int, strong: Boolean = false, onCollect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
            if (!done && goal > 1) { Bar(have, goal, Modifier.fillMaxWidth().padding(top = 6.dp)); Text(progressText(have, goal, unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        when {
            claimed -> Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            done -> FilledTonalButton(onClick = onCollect, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("🐟 $reward") }
            else -> Text("🐟 $reward", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MissionRow(m: Mission) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(if (m.done) LocalK.current.green else LocalK.current.orange), contentAlignment = Alignment.Center) {
            Icon(if (m.done) Icons.Rounded.Check else Icons.Rounded.TrackChanges, null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Text(if (m.done) "Done" else progressText(m.have, m.need, m.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!m.done) Bar(m.have, m.need, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text("🐟 +${m.pay}", style = MaterialTheme.typography.labelLarge, color = if (m.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun achIcon(stat: String): ImageVector = when (stat) {
    "home" -> Icons.Rounded.Pets; "minutes" -> Icons.Rounded.TrackChanges; "streak" -> Icons.Rounded.LocalFireDepartment
    "long", "stopwatch" -> Icons.Rounded.Timer; "early" -> Icons.Rounded.WbSunny; "night" -> Icons.Rounded.Bedtime
    "naps", "friends" -> Icons.Rounded.Groups; "missions" -> Icons.Rounded.Flag; "tags" -> Icons.Rounded.Sell
    "met" -> Icons.Rounded.Favorite; "adopted" -> Icons.Rounded.Home; "things" -> Icons.Rounded.Chair
    "shelter" -> Icons.Rounded.VolunteerActivism; else -> Icons.Rounded.EmojiEvents
}

// ---------- Shop ----------

@Composable
private fun ShopPane(s: UiState, act: MainActivity, onCat: (String) -> Unit, onBuy: (Offer) -> Unit) {
    Text("A block whose kitten comes home pays one treat a minute. Missions and rewards pay more.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
    val ev = s.shop.season
    val soon = s.shop.soon
    if (ev != null) {
        Head("${eventIcon(ev.id)} ${ev.name} · until ${monthDay(ev.to)}")
        val cats = s.cats.filter { it.limited == ev.id }
        val things = s.shop.items.filter { it.limited == ev.id }
        Grid(cats.map { it.id to it.name } + things.map { it.id to it.name }, 3) { (id, name) ->
            val isCat = cats.any { it.id == id }
            ShopCard(s, id, name, if (isCat) "cat" else "thing", onClick = { if (isCat) onCat(id) else onBuy(Offer(id, name, "thing")) })
        }
        Foot("Only for a week. What you get, you keep.")
    } else if (soon != null) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(eventIcon(soon.id), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                Text("${soon.name} starts in ${if (soon.days == 1) "1 day" else "${soon.days} days"}: ${eventTease(soon.id)}, for one week only.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
    Head("For your café")
    val regular = s.shop.items.filter { it.limited == null || (s.owns(it.id) && !s.forSale(it.id)) }.sortedBy { s.price(it.id) }
    Grid(regular, 3) { x -> ShopCard(s, x.id, x.name, "thing") { onBuy(Offer(x.id, x.name, "thing")) } }
    Head("Cats to adopt")
    Grid(s.cats.filter { it.adopt && (it.limited == null || (it.met && !s.forSale(it.id))) }.sortedBy { it.price }, 3) { c ->
        ShopCard(s, c.id, c.name, "cat") { onCat(c.id) }
    }
    Head("Café themes")
    Grid(s.shop.themes.filter { it.id != "classic" }, 3) { th ->
        val id = "theme-${th.id}"
        ShopCard(s, id, th.name, "theme", art = { ThemeSwatch(th.id, Modifier.fillMaxWidth(0.8f)) }) {
            if (s.owns(id)) KavanaApp.repo.launch(settings("theme" to th.id)) else onBuy(Offer(id, th.name, "theme"))
        }
    }
    Foot("Tap one you have to use it in your café.")
    Head("Sounds")
    Group {
        SOUNDS.forEachIndexed { i, (id, name, about) ->
            if (i > 0) GroupDivider()
            val sid = "sound-$id"
            Item(name, Icons.Rounded.GraphicEq, LocalK.current.indigo, sub = about, onClick = if (s.owns(sid)) null else ({ onBuy(Offer(sid, name, "sound")) }), chevron = false) {
                if (s.owns(sid)) Text("Yours", color = MaterialTheme.colorScheme.primary) else Price(s.price(sid), s.treats.left)
            }
        }
    }
    Foot("Purring, rain, brown noise and white noise are free. Play them during a block.")
    // Shelter cats: Forest's real trees, for cats. They come one after the other.
    Head("Shelter cats")
    val next = s.shop.shelter.firstOrNull { !s.owns(it.id) }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (next != null) {
                LiveCat(next.id, "portrait", 120.dp, interactive = true)
                Text("Sponsor ${next.name}", style = MaterialTheme.typography.titleMedium)
                Text(next.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Button(onClick = { onBuy(Offer(next.id, next.name, "shelter")) }, enabled = s.treats.left >= s.price(next.id), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(if (s.treats.left >= s.price(next.id)) "Sponsor for 🐟 ${s.price(next.id)}" else "🐟 ${s.price(next.id)} · ${s.price(next.id) - s.treats.left} more to go")
                }
            } else Text("You sponsor all four. Thank you.", style = MaterialTheme.typography.titleMedium)
            Text("Each one stands for a real shelter cat. No money goes through Kavana; to help a real one, give to ${s.shop.shelterOrg.name}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            if (s.shop.shelterOrg.url.isNotEmpty()) TextButton(onClick = { act.openUrl(s.shop.shelterOrg.url) }) { Text("Visit ${s.shop.shelterOrg.name}") }
        }
    }
}

@Composable
private fun ShopCard(s: UiState, id: String, name: String, kind: String, art: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val owned = s.owns(id)
    Card(onClick = onClick, mine = owned) {
        when {
            art != null -> art()
            kind == "cat" -> LiveCat(id, "portrait", 72.dp, shadow = false)
            else -> CatArt(Art.thing(id), 64.dp)
        }
        Text(name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
        if (owned) Text(if (kind == "theme" && s.view.settings.theme == id.removePrefix("theme-")) "In use" else if (kind == "thing") "In your café" else "Yours",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        else Price(s.price(id), s.treats.left)
    }
}

@Composable
private fun BuyDialog(s: UiState, o: Offer, onDone: () -> Unit) {
    val repo = KavanaApp.repo
    val price = s.price(o.id)
    val enough = s.treats.left >= price
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(when (o.kind) { "cat" -> "Adopt ${o.name}?"; "shelter" -> "Sponsor ${o.name}?"; else -> "Buy ${o.name}?" }) },
        text = { Text(if (enough) "🐟 $price treats. You have ${s.treats.left}." else "🐟 $price treats. You have ${s.treats.left}: ${price - s.treats.left} more to go.") },
        confirmButton = {
            TextButton(enabled = enough, onClick = {
                onDone()
                repo.launch(op("buy", "id" to o.id)) { out ->
                    if (out?.error != null) { repo.toasts.tryEmit(if (out.error == "treats") "Not enough treats yet." else "That's not in the shop right now."); return@launch }
                    if (o.kind == "theme") repo.launch(settings("theme" to o.id.removePrefix("theme-")))
                    repo.toasts.tryEmit(when (o.kind) {
                        "cat" -> "${o.name} is yours. Bring ${o.name} to a block from My Cats."
                        "shelter" -> "Thank you. ${o.name} lives in your café now."
                        "theme" -> "Your café is ${o.name} now."
                        "sound" -> "${o.name} is in your sounds."
                        else -> "${o.name} is in your café."
                    })
                }
            }) { Text(if (o.kind == "cat") "Adopt" else if (o.kind == "shelter") "Sponsor" else "Buy") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------- small pieces ----------

@Composable
private fun Head(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))

@Composable
private fun Foot(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))

/** Cards in rows of `n`, the last row left-aligned. */
@Composable
private fun <T> Grid(list: List<T>, n: Int, card: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.chunked(n).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Box(Modifier.weight(1f).fillMaxHeight()) { card(it) } }
                repeat(n - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Card(onClick: (() -> Unit)?, mine: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxSize().then(if (onClick != null) Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
fun Price(price: Int, left: Int) {
    Surface(shape = RoundedCornerShape(50), color = if (left >= price) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("🐟 $price", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = if (left >= price) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Bar(have: Int, need: Int, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { if (need <= 0) 0f else (have.toFloat() / need).coerceIn(0f, 1f) }, modifier.height(6.dp), strokeCap = StrokeCap.Round, drawStopIndicator = {})
}

fun eventIcon(id: String) = when (id) { "halloween" -> "🎃"; "winter" -> "❄️"; else -> "✨" }
private fun eventTease(id: String) = when (id) {
    "halloween" -> "a pumpkin cat and spooky things for your café"
    "winter" -> "Flurry the winter cat, a fireplace, a snowman and more for your café"
    else -> "new things for your café"
}
private fun monthDay(md: List<Int>): String = if (md.size < 2) "" else java.time.MonthDay.of(md[0], md[1]).let {
    "${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${it.dayOfMonth}"
}
