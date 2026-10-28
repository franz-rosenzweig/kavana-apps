package xyz.tiresia.kavana.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A complication for watch faces: during a block, the minutes left (counting down by itself) and the ring of what's gone; otherwise
 * today's focus against the daily goal ("1h20"). Never a block's title (a watch face is seen by anyone).
 */
class FocusComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, WatchSnap("READY", "Miso", todayMin = 80, goalMin = 120))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, WatchStore.snap(this) ?: WatchSnap("READY", "Miso"))

    companion object {
        private fun plain(s: String): ComplicationText = PlainComplicationText.Builder(s).build()

        private fun compact(m: Int) = if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}" else "${m}m"

        /** What the complication shows for a snapshot (the watch face, and in debug builds the complication lab, draw it). */
        fun data(type: ComplicationType, s: WatchSnap, now: Long = System.currentTimeMillis()): ComplicationData? {
            if (s.counting) {
                val left = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(Instant.ofEpochMilli(s.end)))
                    .setMinimumTimeUnit(TimeUnit.MINUTES).build()
                val gone = (1f - (s.end - now).toFloat() / s.total.coerceAtLeast(1)).coerceIn(0f, 1f) * 100f
                return when (type) {
                    ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(gone, 0f, 100f, plain("Focusing")).setText(left).setTitle(plain("left")).build()
                    ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(left, plain("Focusing")).setTitle(plain("left")).build()
                    else -> null
                }
            }
            val goal = if (s.goalMin > 0) s.goalMin else 120
            val what = plain("Today's focus: ${s.todayMin} minutes")
            return when (type) {
                ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(s.todayMin.coerceAtMost(goal).toFloat(), 0f, goal.toFloat(), what)
                    .setText(plain(compact(s.todayMin))).setTitle(plain("today")).build()
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(compact(s.todayMin)), what).setTitle(plain("today")).build()
                else -> null
            }
        }
    }
}
