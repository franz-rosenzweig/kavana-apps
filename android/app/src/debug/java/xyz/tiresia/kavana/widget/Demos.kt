package xyz.tiresia.kavana.widget

/** Made-up snapshots to look at every state of a widget (the Widget lab), with the numbers of the mockups. */
object Demos {
    private const val M = 60_000L

    private fun midnight(daysAgo: Int): Long = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -daysAgo); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The last fourteen days: the minutes and the hour of the longest block, then the week as the board has it (dawn, day, sunset, day, dawn, night with Boo, today). */
    fun days(empty: Boolean = false): List<SnapDay> {
        val mins = listOf(35, 0, 60, 45, 0, 70, 30) + listOf(40, 120, 50, 90, 60, 45, 0)
        val hours = listOf(9, -1, 14, 19, -1, 11, 8) + listOf(7, 12, 18, 13, 8, 23, -1)
        val arts = listOf("cats/miso-1.webp", "", "cats/mochi-1.webp", "cats/biscuit-1.webp", "", "cats/miso-1.webp", "cats/earl-1.webp") +
            listOf("cats/mochi-1.webp", "cats/miso-1.webp", "cats/biscuit-1.webp", "cats/miso-1.webp", "cats/earl-1.webp", "cats/boo-1.webp", "")
        return (0 until 14).map { i -> SnapDay(midnight(13 - i), if (empty) 0 else mins[i], if (empty) "" else arts[i], if (empty) -1 else hours[i]) }
    }

    /** About a month of blocks, a day in each of most of this month's days, and a run of days at the end. */
    fun stars(): List<SnapStar> {
        val cal = java.util.Calendar.getInstance()
        val dom = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val rnd = java.util.Random(5)
        val out = ArrayList<SnapStar>()
        for (d in 1..dom) {
            val run = d > dom - 5
            if (!run && rnd.nextInt(10) > 6) continue
            repeat(if (run) 1 else 1 + rnd.nextInt(2)) {
                val c = (cal.clone() as java.util.Calendar).apply { set(java.util.Calendar.DAY_OF_MONTH, d); set(java.util.Calendar.HOUR_OF_DAY, 8 + rnd.nextInt(12)); set(java.util.Calendar.MINUTE, rnd.nextInt(60)) }
                out.add(SnapStar(c.timeInMillis, listOf(15, 25, 25, 50, 50, 90)[rnd.nextInt(6)]))
            }
        }
        return out.takeLast(22)
    }

    /** Today at this time, in milliseconds. */
    fun at(hour: Int, minute: Int): Long = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute); set(java.util.Calendar.SECOND, 0) }.timeInMillis

    fun base(cat: String = "miso", name: String = "Miso", theme: String = "classic") = Snap(
        cat = cat, catName = name, theme = theme,
        portrait = if (cat == "pepper") "cats/pepper-2.webp" else "cats/$cat-1.webp",
        kitten = if (cat == "pepper") "cats/pepper-kitten-2.webp" else "cats/$cat-kitten-1.webp",
        nap = if (cat == "pepper") "cats/pepper-nap-2.webp" else "cats/$cat-nap-1.webp",
        todayMin = 80, goalMin = 120, streak = 5, missionsDone = 2, missionsTotal = 3, treats = 120,
        missionNext = "Bring 3 kittens home: 2 of 3",
        boo = true, todayHome = 3, todayTreats = 174, monthHome = 38, monthMin = 1513, monthGone = 2, monthTreats = 1474, month = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(java.util.Date()), stars = stars(), days = days(), loaf = when (cat) { "pepper" -> "cats/pepper-loaf-2.webp"; "miso", "mochi" -> "cats/$cat-loaf-1.webp"; else -> null },
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
