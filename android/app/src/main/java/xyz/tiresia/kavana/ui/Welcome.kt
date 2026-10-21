package xyz.tiresia.kavana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
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
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (page) {
            0 -> {
                CatArt(Art.portrait(s.companion), 140.dp, Modifier.padding(top = 12.dp))
                Text("Welcome to Kavana", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                Point(Icons.Rounded.TrackChanges, MaterialTheme.colorScheme.primary, "One thing at a time", "Say what you're working on and pick how long.")
                Point(Icons.Rounded.Notifications, k.orange, "Gentle check-ins", "\"Still on it?\" every so often, even when the app is closed.")
                Point(Icons.Rounded.Pets, k.pink, "A kitten grows while you focus", "Stay to the end and it comes home to your Cat Café.")
                Point(Icons.Rounded.Lock, k.blue, "Stays on your phone", "No account. Nothing is sent anywhere.")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { page = 1; if (!act.notificationsOn()) act.askNotifications() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Continue") }
            }
            else -> {
                val on = remember(resumes) { act.overlayOn() }
                CatArt(Art.portrait(s.companion), 160.dp, Modifier.padding(top = 24.dp))
                Text("Let the cat find you", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "When you wander off to another app in the middle of a block, the cat pops up over it and says \"Boo! It's time to focus.\" Check-ins can show up the same way, with Yes and Drifted right there.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Android calls this \"Display over other apps\". Find Kavana in the list and switch it on, then come back.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(28.dp))
                if (on) {
                    Text("All set.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start Focusing") }
                } else {
                    Button(onClick = { act.askOverlay() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Allow") }
                    TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Not Now") }
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
