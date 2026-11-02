package xyz.tiresia.kavana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.tiresia.kavana.LeaveRules.EVERY
import xyz.tiresia.kavana.LeaveRules.EXCEPT
import xyz.tiresia.kavana.LeaveRules.Move
import xyz.tiresia.kavana.LeaveRules.ONLY
import xyz.tiresia.kavana.LeaveRules.Verdict
import xyz.tiresia.kavana.LeaveRules.judge
import xyz.tiresia.kavana.LeaveRules.move

class LeaveRulesTest {
    private val work = setOf("com.example.docs", "com.example.mail")
    private val home = "com.example.launcher"
    private val never = setOf(home, "com.android.systemui", "com.example.dialer", "com.example.keyboard", "xyz.tiresia.kavana")

    @Test fun `no list is today's way`() {
        assertFalse(LeaveRules.listOn(EVERY, work))
        assertFalse(LeaveRules.listOn(EXCEPT, emptySet()))
        assertFalse(LeaveRules.listOn(ONLY, emptySet()))
        assertFalse(LeaveRules.listOn("something else", work))
        assertTrue(LeaveRules.listOn(EXCEPT, work))
        assertTrue(LeaveRules.listOn(ONLY, work))
    }

    @Test fun `every app counts, the home screen and calls too`() {
        for (app in listOf("com.example.game", "com.example.docs", home)) assertEquals(Verdict.COUNTS, judge(EVERY, work, app, never))
        assertEquals(Verdict.COUNTS, judge(EVERY, work, "com.example.game", never, inCall = true))
    }

    @Test fun `an empty list behaves like every app`() {
        assertEquals(Verdict.COUNTS, judge(EXCEPT, emptySet(), home, never))
        assertEquals(Verdict.COUNTS, judge(ONLY, emptySet(), "com.example.docs", never))
    }

    @Test fun `every app except these`() {
        assertEquals(Verdict.ALLOWED, judge(EXCEPT, work, "com.example.docs", never))
        assertEquals(Verdict.ALLOWED, judge(EXCEPT, work, "com.example.mail", never))
        assertEquals(Verdict.COUNTS, judge(EXCEPT, work, "com.example.game", never))
    }

    @Test fun `only these apps`() {
        val games = setOf("com.example.game", "com.example.video")
        assertEquals(Verdict.COUNTS, judge(ONLY, games, "com.example.game", never))
        assertEquals(Verdict.COUNTS, judge(ONLY, games, "com.example.video", never))
        assertEquals(Verdict.ALLOWED, judge(ONLY, games, "com.example.docs", never))
    }

    @Test fun `some apps never count, picked or not`() {
        for (app in never) {
            assertEquals(app, Verdict.NEVER, judge(EXCEPT, work, app, never))
            assertEquals(app, Verdict.NEVER, judge(ONLY, work + app, app, never))
        }
    }

    @Test fun `nothing counts during a call, with a list`() {
        assertEquals(Verdict.NEVER, judge(EXCEPT, work, "com.example.game", never, inCall = true))
        assertEquals(Verdict.NEVER, judge(ONLY, setOf("com.example.game"), "com.example.game", never, inCall = true))
    }

    @Test fun `no app in front is nothing`() {
        assertEquals(Verdict.NEVER, judge(EXCEPT, work, null, never))
        assertEquals(Verdict.NEVER, judge(EVERY, work, null, never))
    }

    @Test fun `a trip's app is added to never`() {
        val browser = "com.example.browser"
        assertEquals(Verdict.COUNTS, judge(EXCEPT, work, browser, never))
        assertEquals(Verdict.NEVER, judge(EXCEPT, work, browser, never + browser))
    }

    @Test fun `what follows`() {
        assertEquals(Move.LEAVE, move(Verdict.COUNTS, away = false))
        assertEquals(Move.NONE, move(Verdict.COUNTS, away = true)) // already counting down
        assertEquals(Move.BACK, move(Verdict.ALLOWED, away = true)) // an allowed app within the grace: back
        assertEquals(Move.NONE, move(Verdict.ALLOWED, away = false))
        assertEquals(Move.NONE, move(Verdict.NEVER, away = true)) // the home screen doesn't take the cat away
        assertEquals(Move.NONE, move(Verdict.NEVER, away = false)) // nor bring it
    }
}
