package xyz.tiresia.kavana.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.tiresia.kavana.art

/** A tab's big title, the way every Kavana screen starts. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Lede(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))

/** A grouped card with an optional small heading above it and a footnote under it. */
@Composable
fun Group(head: String? = null, foot: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (head != null) Text(head, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
        if (foot != null) Text(foot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

/** A row in a group: an icon on a coloured tile, a label, and whatever goes on the right. */
@Composable
fun Item(
    label: String,
    icon: ImageVector? = null,
    tile: Color = MaterialTheme.colorScheme.primary,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    labelColor: Color = Color.Unspecified,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tile), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
        if (chevron) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** One of a few choices, as a segmented row (How long, Check-ins). */
@Composable
fun <T> Seg(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {},
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

/** A painted cat (or the paw prints), from the web app's pictures. Locked cats are a silhouette. */
@Composable
fun CatArt(path: String, size: Dp, modifier: Modifier = Modifier, locked: Boolean = false, alpha: Float = 1f) {
    val img = art(path)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (img != null) Image(
            img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().alpha(alpha),
            colorFilter = if (locked) ColorFilter.tint(MaterialTheme.colorScheme.onSurface.copy(alpha = .18f), BlendMode.SrcIn) else null,
        )
    }
}

/** A big emoji choice (Yes / Drifted, Done / Partly / Not really), like the web's sheets. */
@Composable
fun Choice(emoji: String, label: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    val bg = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (strong) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = fg, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** The round buttons under the timer (Stop, Pause, Resume, End), like the iPhone's Clock. `sub`: a small line under it (Cancel's seconds). */
@Composable
fun RoundButton(label: String, color: Color, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    Column(
        modifier.size(84.dp).clip(CircleShape).background(color.copy(alpha = .2f)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
        if (sub != null) Text(sub, color = color, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 1.sp)

@Composable
fun TreatsChip(n: Int) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text("🐟 $n", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun Empty(cat: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CatArt(cat, 120.dp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
