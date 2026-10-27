package xyz.tiresia.kavana.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.tiresia.kavana.*

// The sounds you play during a block or a mindful minute: up to two at once, each with its own
// volume, like Forest's ambient sounds. The mix is remembered on this phone (web/app.js soundMix).

object SoundMix {
    /** A mindful minute is on (its sounds play with no block running). */
    @Volatile var breathing = false

    private fun load(): List<Pair<String, Float>> = KavanaApp.repo.prefs.getString("mix", "").orEmpty().split(',')
        .mapNotNull { x -> x.split(':').takeIf { it.size == 2 }?.let { (id, v) -> v.toFloatOrNull()?.let { id to it.coerceIn(0.05f, 1f) } } }
        .filter { Sound.byId(it.first) != null }.take(2)

    var mix by mutableStateOf(load())
        private set

    fun set(m: List<Pair<String, Float>>) {
        mix = m.take(2)
        KavanaApp.repo.prefs.edit().putString("mix", mix.joinToString(",") { "${it.first}:${"%.2f".format(java.util.Locale.ROOT, it.second)}" }).apply()
        xyz.tiresia.kavana.widget.WidgetUpdater.changed(KavanaApp.app)
    }

    /** The widget's pause: the mix stays as it is, but nothing plays until it's played again. */
    private var mutedState by mutableStateOf(KavanaApp.repo.prefs.getBoolean("soundMuted", false))
    var muted: Boolean
        get() = mutedState
        set(v) {
            mutedState = v
            KavanaApp.repo.prefs.edit().putBoolean("soundMuted", v).apply()
            xyz.tiresia.kavana.widget.WidgetUpdater.changed(KavanaApp.app)
        }

    /** What's in the mix that you can play (free, or bought in the shop), unless the widget's pause has it muted. */
    fun playable(s: UiState) = if (muted) emptyList() else mix.filter { owned(s, it.first) }
    fun owned(s: UiState, id: String) = Sound.byId(id)?.free == true || s.owns("sound-$id")
    fun label(s: UiState) = playable(s).joinToString(" + ") { Sound.byId(it.first)?.name ?: it.first }.ifEmpty { "Sound" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(s: UiState, onShop: () -> Unit, onDone: () -> Unit) {
    val mix = SoundMix.mix
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sounds", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (mix.isNotEmpty()) TextButton(onClick = { SoundMix.set(emptyList()) }) { Text("Off") }
                TextButton(onClick = onDone) { Text("Done") }
            }
            Text("Up to two at once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // In their groups (web drawSounds), one free in each.
            for (g in Sound.GROUPS) Group(head = g.name) {
                Sound.KINDS.filter { it.group == g.id }.forEachIndexed { i, k ->
                    if (i > 0) GroupDivider()
                    val owned = SoundMix.owned(s, k.id)
                    val on = mix.firstOrNull { it.first == k.id }
                    Column(Modifier.fillMaxWidth().clickable {
                        when {
                            !owned -> { onShop(); onDone(); KavanaApp.repo.toasts.tryEmit("${k.name} is in the shop, under Sounds.") }
                            on != null -> SoundMix.set(mix - on)
                            else -> SoundMix.set((mix + (k.id to 0.8f)).takeLast(2)) // a third one takes the oldest one's place
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (on != null) Icons.Rounded.Check else if (owned) Icons.Rounded.GraphicEq else Icons.Rounded.Lock, null,
                                tint = if (on != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(k.name, style = MaterialTheme.typography.bodyLarge)
                                Text(if (owned) k.about ?: "Free" else "${k.about} · 🐟 ${s.price("sound-${k.id}")} in the shop",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (on != null) Slider(on.second, { v -> SoundMix.set(mix.map { if (it.first == k.id) k.id to v.coerceIn(0.05f, 1f) else it }) },
                            valueRange = 0.05f..1f, modifier = Modifier.padding(start = 36.dp))
                    }
                }
            }
            Text("One in each group is free. The others are in the shop.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        }
    }
}
