package xyz.tiresia.kavana.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import xyz.tiresia.kavana.LivingCat
import xyz.tiresia.kavana.Motion
import kotlin.random.Random

/**
 * A living cat. The box is the painted picture (512 px square); the cat draws a little outside it,
 * with room to hop and swish (Motion.FX0..). A new `id` or `pose` changes the picture with a squash
 * and pop (`morph`), or straight away when `morph` is false. `interactive`: tap to hop, hold to purr
 * (the phone purrs too), and the head follows your finger. `onCat` hands out the cat for scripted moments.
 */
@Composable
fun LiveCat(
    id: String,
    pose: String,
    size: Dp,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    shadow: Boolean = true,
    alpha: Float = 1f,
    morph: Boolean = true,
    grid: Int = if (size.value >= 120f) 32 else 22,
    seed: Int = remember { Random.nextInt() },
    label: String? = null,
    onCat: (LivingCat) -> Unit = {},
) {
    val ctx = LocalContext.current
    val cat = remember { LivingCat(ctx, id, pose, seed, grid, shadow, ValueAnimator.areAnimatorsEnabled()) }
    LaunchedEffect(cat) { onCat(cat) }
    LaunchedEffect(id, pose) { if (cat.id != id || cat.pose != pose) { if (morph) cat.morph(id, pose) else cat.set(id, pose) } }
    var frame by remember { mutableLongStateOf(0L) }
    // Scrolled out of sight (a long Cats list, the café): it rests until it's back, so a screen full of
    // cats costs only the ones you can see.
    var seen by remember { mutableStateOf(true) }
    LaunchedEffect(cat) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (seen) {
                cat.update(if (last == 0L) 0.016f else (now - last) / 1e9f)
                frame = now
            }
            last = now
        }
    }
    val vibrator = remember { ctx.getSystemService(Vibrator::class.java) }
    var m = modifier.size(size).onGloballyPositioned { seen = it.boundsInWindow().height > 0f }.drawBehind {
        frame // redraw every frame
        val w = this.size.width * Motion.FW / 512f
        val h = this.size.height * Motion.FH / 512f
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(this.size.width * Motion.FX0 / 512f, this.size.height * Motion.FY0 / 512f)
            cat.draw(nc, w, h, alpha)
            nc.restore()
        }
    }
    if (interactive) m = m
        .semantics { role = Role.Button; contentDescription = label ?: "A cat. Tap to make it hop."; onClick { cat.poke(); true } }
        .pointerInput(cat) {
            // The frame around the picture, in this box's px (the cat looks in the picture's own px).
            val fx = this.size.width * Motion.FX0 / 512f; val fy = this.size.height * Motion.FY0 / 512f
            val fw = this.size.width * Motion.FW / 512f; val fh = this.size.height * Motion.FH / 512f
            fun look(x: Float, y: Float) { cat.look = (Motion.FX0 + (x - fx) / fw * Motion.FW) to (Motion.FY0 + (y - fy) / fh * Motion.FH) }
            awaitEachGesture {
                val down = awaitFirstDown()
                look(down.position.x, down.position.y)
                var purr = false
                while (true) {
                    val wait = if (purr) Long.MAX_VALUE else 420L - (SystemClock.uptimeMillis() - down.uptimeMillis)
                    val ev = if (wait <= 0) null else withTimeoutOrNull(wait) { awaitPointerEvent() }
                    if (ev == null) {
                        // Held: it purrs, and so does the phone, softly.
                        purr = true; cat.purring = true
                        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(LongArray(40) { if (it % 2 == 0) 22L else 18L }, IntArray(40) { if (it % 2 == 0) 0 else 40 }, 0)) }
                        continue
                    }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    look(ch.position.x, ch.position.y)
                }
                if (purr) runCatching { vibrator?.cancel() } else cat.poke()
                cat.purring = false
                cat.look = null
            }
        }
    Box(m)
}
