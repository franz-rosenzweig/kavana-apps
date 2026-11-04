package xyz.tiresia.kavana.wear

import android.content.Context
import android.graphics.BitmapFactory
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/**
 * The Chataltul tile: the cat waiting, with Start 25 and 15, 50, ∞; a block, with the kitten in its window, the ring of what's left,
 * the time left and Pause; a check-in, with Yes and Drifted; paused, with Resume; the kitten come home, with Done. A tap goes to the
 * phone, which does it; the phone's next snapshot draws the tile again.
 */
class FocusTile : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = SuspendToFutureAdapter.launchFuture {
        val ctx = this@FocusTile
        val tapped = req.currentState.lastClickableId
        if (tapped.isNotEmpty()) Phone.act(ctx, tapped)
        val s = WatchStore.snap(ctx)
        if (s == null || System.currentTimeMillis() - s.sent > 6 * 3_600_000L) Phone.act(ctx, "hello") // nothing yet, or old: ask the phone
        TileBuilders.Tile.Builder()
            .setResourcesVersion(WatchStore.version(ctx))
            // a running block: the minutes once a minute (and a young stopwatch, until it can take Done)
            .setFreshnessIntervalMillis(if (s != null && (s.counting || s.stopwatch && s.over == null && !s.finishes(System.currentTimeMillis()))) 60_000L else 0L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(TileLayout.layout(ctx, s, req.deviceConfiguration)))
            .build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        TileLayout.resources(WatchStore.window(this@FocusTile), req.version)
    }
}

/**
 * What the tile looks like for a snapshot (the tile, and in debug builds the tile lab, draw it). Each state is one column in the middle
 * of the round screen, sized from its width (a 192 dp Pixel Watch to the 227 dp ones), with compact chips that stay inside the circle.
 */
object TileLayout {
    private val teal = 0xFF5FD0C0.toInt()
    private val onTeal = 0xFF10302B.toInt()
    private val amber = 0xFFFFCF8A.toInt()
    private val orange = 0xFFE98A3C.toInt()
    private val green = 0xFF7FDC9B.toInt()
    private val onGreen = 0xFF0E2A18.toInt()
    private val ink = 0xFFF2EDE6.toInt()
    private val muted = 0xFFB7AEA5.toInt()
    private val chip = 0xFF2E2733.toInt()

    /** The window's picture, as the tile's one image. */
    fun resources(png: ByteArray?, version: String): ResourceBuilders.Resources =
        ResourceBuilders.Resources.Builder().setVersion(version).apply {
            if (png != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, o)
                addIdToImageMapping("window", ResourceBuilders.ImageResource.Builder().setInlineResource(
                    ResourceBuilders.InlineImageResource.Builder().setData(png).setWidthPx(o.outWidth).setHeightPx(o.outHeight)
                        .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED).build()).build())
            }
        }.build()

    private fun tap(id: String) = ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()

    private fun text(ctx: Context, s: String, typo: Int, color: Int, lines: Int = 1) =
        Text.Builder(ctx, s).setTypography(typo).setColor(argb(color)).setMaxLines(lines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END).build()

    private fun window(size: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Image.Builder().setResourceId("window").setWidth(dp(size)).setHeight(dp(size)).build()

    private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()
    private fun across(w: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(dp(w)).build()

    private fun column(width: Float, vararg e: LayoutElementBuilders.LayoutElement) = LayoutElementBuilders.Column.Builder().apply {
        setWidth(dp(width))
        setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        e.forEach { addContent(it) }
    }.build()

    private fun row(vararg e: LayoutElementBuilders.LayoutElement) = LayoutElementBuilders.Row.Builder().apply {
        setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        e.forEach { addContent(it) }
    }.build()

    /** The whole screen, black, with this in the middle. */
    private fun screen(content: LayoutElementBuilders.LayoutElement) = LayoutElementBuilders.Box.Builder()
        .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
        .addContent(content).build()

    private fun action(ctx: Context, label: String, id: String, dev: DeviceParameters, bg: Int = teal, fg: Int = onTeal) =
        CompactChip.Builder(ctx, label, tap(id), dev).setChipColors(ChipColors(bg, fg)).build()

    private fun round(ctx: Context, label: String, id: String, size: Float) =
        Button.Builder(ctx, tap(id)).setTextContent(label).setButtonColors(ButtonColors(chip, ink)).setSize(dp(size)).build()

    private fun minutesLeft(s: WatchSnap, now: Long) = ((s.end - (s.paused ?: now) + 59_999L) / 60_000L).coerceAtLeast(0)

    fun layout(ctx: Context, s: WatchSnap?, dev: DeviceParameters): LayoutElementBuilders.LayoutElement {
        val now = System.currentTimeMillis()
        val w = dev.screenWidthDp.toFloat().coerceAtLeast(150f)
        val k = (w / 192f).coerceIn(0.85f, 1.25f) // a Pixel Watch is 192 dp across, the bigger ones up to 227
        val inner = w * 0.72f                     // what fits across the middle of the circle, with room to spare
        if (s == null) return screen(column(inner, text(ctx, "Waiting for the phone", Typography.TYPOGRAPHY_BODY1, muted, 2)))
        val name = if (s.catsOn) s.catName else "Chataltul"
        return when (s.mode) {
            "FOCUSING", "EXTRA", "GONE" -> {
                val progress = when {
                    s.over != null || s.stopwatch -> 1f
                    else -> (1f - (s.end - now).toFloat() / s.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
                val clock = when { s.over != null -> "extra time"; s.stopwatch -> "focusing"; else -> "${minutesLeft(s, now)} min left" }
                val under = if (s.mode == "GONE") "$name wandered off" else s.title.ifEmpty { if (s.catsOn) "$name is growing" else "" }
                EdgeContentLayout.Builder(dev)
                    .setEdgeContent(CircularProgressIndicator.Builder().setProgress(progress)
                        .setCircularProgressIndicatorColors(ProgressIndicatorColors(teal, 0x33FFFFFF)).build())
                    .setContent(column(w * 0.66f,
                        window(46f * k),
                        text(ctx, clock, Typography.TYPOGRAPHY_TITLE2, ink),
                        text(ctx, under, Typography.TYPOGRAPHY_CAPTION1, muted),
                        gap(6f),
                        if (s.finishes(now)) action(ctx, "Done", "stop", dev, green, onGreen) else action(ctx, "Pause", "pause", dev, amber, 0xFF3A2510.toInt()),
                    ))
                    .build()
            }
            "CHECKIN" -> screen(column(inner,
                text(ctx, "BOO! CHECK-IN", Typography.TYPOGRAPHY_CAPTION1, orange),
                gap(4f),
                text(ctx, if (s.title.isNotEmpty()) "Still on: ${s.title}?" else "Still focusing?", Typography.TYPOGRAPHY_BODY1, ink, 2),
                gap(10f),
                row(action(ctx, "Yes", "yes", dev), across(6f), action(ctx, "Drifted", "drifted", dev, chip, ink)),
            ))
            "PAUSED" -> screen(column(inner,
                text(ctx, "PAUSED", Typography.TYPOGRAPHY_CAPTION1, orange),
                gap(4f), window(44f * k),
                text(ctx, "${minutesLeft(s, now)} min left", Typography.TYPOGRAPHY_TITLE3, ink),
                gap(8f), action(ctx, "Resume", "resume", dev, green, onGreen),
            ))
            "HOME", "STOPPED" -> screen(column(inner,
                text(ctx, if (s.mode == "HOME") "${name.uppercase()} CAME HOME" else "STOPPED EARLY", Typography.TYPOGRAPHY_CAPTION1, if (s.mode == "HOME") teal else orange),
                gap(4f), window(44f * k),
                text(ctx, if (s.mode == "HOME") "+${s.treats} treats" else "${s.minutes} min", Typography.TYPOGRAPHY_TITLE3, ink),
                gap(8f), action(ctx, "Done", "done", dev),
            ))
            else -> { // ready (or a break): Start 25, and 15, 50, ∞
                val b = 34f * k
                screen(column(inner,
                    text(ctx, if (s.mode == "BREAK") "BREAK" else if (s.catsOn) "$name is waiting" else "Ready", Typography.TYPOGRAPHY_CAPTION1, teal),
                    gap(4f), window(38f * k), gap(6f),
                    row(round(ctx, "15", "start15", b), across(8f), round(ctx, "50", "start50", b), across(8f), round(ctx, "∞", "startInf", b)),
                    gap(8f),
                    action(ctx, "Start 25", "start25", dev),
                ))
            }
        }
    }
}
