package xyz.tiresia.kavana.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.tiresia.kavana.LeaveRules
import xyz.tiresia.kavana.LeaveWatch
import xyz.tiresia.kavana.MainActivity

private val MODES = listOf(LeaveRules.EVERY to "Every app", LeaveRules.EXCEPT to "Every app except these", LeaveRules.ONLY to "Only these apps")

/** Settings → Apps during a block: which apps bring the leave cat. The list stays on this phone (LeaveWatch). */
@Composable
fun LeaveAppsScreen(act: MainActivity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val k = LocalK.current
    val resumes by act.resumes
    val usageOn = remember(resumes) { LeaveWatch.usageOn(ctx) }
    var mode by remember { mutableStateOf(LeaveWatch.mode(ctx)) }
    var picked by remember { mutableStateOf(LeaveWatch.picked(ctx)) }
    val onTop = remember { picked } // picked when the screen opened: on top, and nothing jumps while you tick
    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<LeaveWatch.App>?>(null) { value = withContext(Dispatchers.IO) { LeaveWatch.launchable(ctx) } }
    val shown = remember(apps, query) {
        apps.orEmpty().filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
            .sortedWith(compareBy({ it.pkg !in onTop }, { it.label.lowercase() }))
    }
    BackHandler(onBack = onBack)
    // (nothing above this screen sets a text colour: without this, the title is black in dark mode)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Settings")
            }
            LargeTitle("The cat comes for")
            Group {
                MODES.forEachIndexed { i, (m, label) ->
                    if (i > 0) GroupDivider()
                    Item(label, leading = { RadioButton(mode == m, null) }, onClick = { mode = m; LeaveWatch.set(ctx, mode = m) }, chevron = false)
                }
            }
        }
        if (mode == LeaveRules.EVERY) return@LazyColumn
        item {
            Group {
                Item("Usage access", Icons.Rounded.QueryStats, k.green, sub = if (usageOn) "On" else "Off") {
                    if (!usageOn) TextButton(onClick = { act.askUsage() }) { Text("Allow") }
                }
            }
            TextField(query, { query = it.take(40) }, placeholder = { Text("Search") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp))
        }
        if (apps == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        items(shown, key = { it.pkg }) { app ->
            val first = app === shown.first()
            val last = app === shown.last()
            val shape = RoundedCornerShape(topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
                bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp)
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
                Column {
                    val on = app.pkg in picked
                    Row(Modifier.fillMaxWidth().clickable {
                        picked = if (on) picked - app.pkg else picked + app.pkg
                        LeaveWatch.set(ctx, apps = picked)
                    }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app.pkg)
                        Spacer(Modifier.width(14.dp))
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Checkbox(on, null)
                    }
                    if (!last) GroupDivider()
                }
            }
        }
    }
    }
}

@Composable
private fun AppIcon(pkg: String) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { 34.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = ctx.packageManager.getApplicationIcon(pkg)
                Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, px, px); d.draw(Canvas(b)) }.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(34.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
}
