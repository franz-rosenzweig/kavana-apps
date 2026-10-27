package xyz.tiresia.kavana.widget

/** Made-up snapshots to look at every state of a widget (the Widget lab), with the numbers of the mockups. */
object Demos {
    private const val M = 60_000L

    fun base(cat: String = "miso", name: String = "Miso", theme: String = "classic") = Snap(
        cat = cat, catName = name, theme = theme,
        portrait = if (cat == "pepper") "cats/pepper-2.webp" else "cats/$cat-1.webp",
        kitten = if (cat == "pepper") "cats/pepper-kitten-2.webp" else "cats/$cat-kitten-1.webp",
        nap = if (cat == "pepper") "cats/pepper-nap-2.webp" else "cats/$cat-nap-1.webp",
        todayMin = 80, goalMin = 120, streak = 5, missionsDone = 2, missionsTotal = 3, treats = 120,
        collect = listOf(SnapReward("starter-1", 20)),
        later = listOf(SnapLater("l1", "Book the dentist"), SnapLater("l2", "Look up that podcast episode"), SnapLater("l3", "Return the library books"), SnapLater("l4", "Reply to Dana")),
        mix = listOf(SnapSound("rain", "Rain", 0.7f), SnapSound("purr", "Purring", 0.38f)),
    )

    private fun running(now: Long, leftMin: Double, totalMin: Int = 30, pending: Int? = null, kitten: String = "growing", paused: Long? = null, pauseEnds: Long? = null, over: Long? = null, title: String = "Draft the newsletter") =
        SnapBlock("b1", title, start = now - ((totalMin - leftMin) * M).toLong(), end = now + (leftMin * M).toLong(), total = totalMin * M,
            paused = paused, pauseEnds = pauseEnds, over = over, kitten = kitten, pending = pending, next = now + 6 * M, still = true)

    fun snap(mode: String, now: Long = System.currentTimeMillis(), theme: String = "classic", cat: String = "miso", name: String = "Miso"): Snap {
        val b = base(cat, name, theme)
        return when (mode) {
            "ready" -> b
            "focusing" -> b.copy(block = running(now, 18.67))
            "nap" -> b.copy(block = running(now, 26.0))
            "grown" -> b.copy(block = running(now, 5.0))
            "notitle" -> b.copy(block = running(now, 18.67, title = ""))
            "checkin" -> b.copy(block = running(now, 12.0, pending = 1))
            "home" -> b.copy(awaiting = SnapEnd("b1", "Draft the newsletter", "home", 50, 50, false))
            "stopped" -> b.copy(awaiting = SnapEnd("b1", "Draft the newsletter", "gone", 12, 0, true))
            "paused" -> b.copy(block = running(now, 18.67, paused = now - 20_000, pauseEnds = now + 4 * M + 40_000))
            "gone" -> b.copy(block = running(now, 18.67, kitten = "gone"))
            "extra" -> b.copy(block = running(now, 0.0, kitten = "home", over = now - 4 * M))
            "break" -> b.copy(breakEnd = now + 4 * M)
            "muted" -> b.copy(block = running(now, 18.67), muted = true)
            "nocollect" -> b.copy(collect = emptyList())
            "empty" -> b.copy(later = emptyList(), mix = emptyList(), streak = 0, missionsDone = 0, goalMin = 0, todayMin = 0, collect = emptyList())
            else -> b
        }
    }
}
