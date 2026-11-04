package xyz.tiresia.kavana

/**
 * Which apps bring the leave cat during a block (Settings → Apps during a block, "The cat comes for").
 * Plain Kotlin, so the unit tests run without a phone; LeaveWatch hands it the app in front.
 */
object LeaveRules {
    /** "Every app": today's way. Chataltul going behind anything (the home screen too) brings the cat. */
    const val EVERY = "every"
    /** "Every app except these": the picked apps are fine during a block ("only allow work apps"). */
    const val EXCEPT = "except"
    /** "Only these apps": only the picked apps bring the cat. */
    const val ONLY = "only"

    enum class Verdict { COUNTS, ALLOWED, NEVER }
    enum class Move { NONE, LEAVE, BACK }

    /** The list decides: a mode that has one, and apps in it. An empty list is today's way. */
    fun listOn(mode: String, picked: Set<String>) = (mode == EXCEPT || mode == ONLY) && picked.isNotEmpty()

    /**
     * What the app in front means, while Chataltul is behind. `never`: the home screen, the phone app and the
     * call screen, System UI and permission dialogs, the keyboard, Chataltul itself and the app one of its own
     * trips went to. Those neither bring the cat nor count as coming back, and neither does anything during a call.
     * With no list, every app counts, the home screen too.
     */
    fun judge(mode: String, picked: Set<String>, front: String?, never: Set<String>, inCall: Boolean = false): Verdict = when {
        front == null -> Verdict.NEVER
        !listOn(mode, picked) -> Verdict.COUNTS
        inCall || front in never -> Verdict.NEVER
        (front in picked) == (mode == ONLY) -> Verdict.COUNTS
        else -> Verdict.ALLOWED
    }

    /** What follows from it. `away`: the cat (or its grace) has already started. */
    fun move(v: Verdict, away: Boolean): Move = when {
        v == Verdict.COUNTS && !away -> Move.LEAVE
        v == Verdict.ALLOWED && away -> Move.BACK
        else -> Move.NONE
    }
}
