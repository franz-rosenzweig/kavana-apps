package xyz.tiresia.kavana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.tiresia.kavana.Art
import xyz.tiresia.kavana.MainActivity
import xyz.tiresia.kavana.UiState

/** First run: what Kavana is, then the two permissions it asks for (notifications, the cat over apps). */
@Composable
fun Welcome(act: MainActivity, s: UiState, onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val resumes by act.resumes
    val k = LocalK.current
    val on = remember(resumes) { act.overlayOn() }
    // The words scroll if they must; the buttons stay at the bottom, where a thumb finds them.
    // Outside the tabs' Scaffold: the Surface gives the text its colour (dark mode drew the titles dark on dark).
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (page == 0) {
                LiveCat(s.companion, "portrait", 120.dp, Modifier.padding(top = 8.dp), interactive = true)
                Text("Welcome to Chataltul", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                Point(Icons.Rounded.TrackChanges, MaterialTheme.colorScheme.primary, "One thing at a time", "Pick how long and press Start. A title is up to you.")
                Point(Icons.Rounded.Notifications, k.orange, "Gentle check-ins", "\"Still on it?\" every so often, even when the app is closed.")
                Point(Icons.Rounded.Pets, k.pink, "A kitten grows while you focus", "Stay to the end and it comes home to your Cat Café.")
                Point(Icons.Rounded.Lock, k.blue, "Stays on your phone", "No account needed. Your history only leaves the phone if you sign in to sync.")
            } else if (page == 1) {
                // A glimpse of where this goes (Forest's "watch it grow"): the café filling up.
                val things = s.shop.items.filter { it.limited == null }
                val cats = seatCats(s.cats.filter { !it.adopt }.map { "welcome-${it.id}" to it.id }, things, emptyList(), 7)
                Text("Your café fills up", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
                Room("classic", things, { true }, cats, dropIn = true)
                Point(Icons.Rounded.Pets, k.pink, "Every block brings a cat home", "Stay to the end and your kitten comes home to the café.")
                Point(Icons.Rounded.Favorite, k.orange, "${s.cats.size} cats to meet", "Some you meet by focusing, some you adopt with treats.")
                Point(Icons.Rounded.Chair, k.indigo, "Treats for the café", "A treat a minute: a box, a cat tree, a window to watch birds.")
            } else {
                LiveCat(s.companion, "portrait", 150.dp, Modifier.padding(top = 16.dp), interactive = true, onCat = { it.hop() })
                Text("Let the cat find you", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "When you wander off to another app in the middle of a block, the cat pops up over it and says \"Boo! It's time to focus.\" Check-ins can show up the same way, with Yes and Drifted right there.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    if (on) "All set." else "Android calls this \"Display over other apps\". Find Chataltul in the list and switch it on, then come back.",
                    style = if (on) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                page == 0 -> Button(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
                page == 1 -> Button(onClick = { page = 2; if (!act.notificationsOn()) act.askNotifications() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
                on -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start Focusing") }
                else -> {
                    Button(onClick = { act.askOverlay() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Allow") }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Not Now") }
                }
            }
        }
    }
    }
}

@Composable
private fun Point(icon: ImageVector, tile: Color, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tile), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
