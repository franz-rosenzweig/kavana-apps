package xyz.tiresia.kavana.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import xyz.tiresia.kavana.KavanaApp

/** The widgets there are, and putting one on the home screen from Settings (the launcher asks "Add to home screen?"). */
object WidgetPins {
    class Kind(val name: String, val about: String, val receiver: Class<*>)

    val KINDS = listOf(
        Kind("The Window", "Your cat in a round window", WindowWidgetReceiver::class.java),
        Kind("Quick start", "15, 25, 50 minutes or a stopwatch", QuickStartWidgetReceiver::class.java),
        Kind("Today", "Your goal, your streak and your missions", TodayWidgetReceiver::class.java),
        Kind("Later", "Your list, and a place to park a thought", LaterWidgetReceiver::class.java),
        Kind("Sound", "The sounds you focus to, with volume and pause", SoundWidgetReceiver::class.java),
        Kind("Streak", "Your streak, with a paw for each day of the week", StreakWidgetReceiver::class.java),
        Kind("Daily goal", "Today's focus as a ring", GoalWidgetReceiver::class.java),
        Kind("Missions", "Today's missions, with Collect", MissionsWidgetReceiver::class.java),
        Kind("Pet the cat", "Tap your cat; tap again and it purrs", PetCatWidgetReceiver::class.java),
        Kind("Constellation", "This month as a night sky, a star for every block", ConstellationWidgetReceiver::class.java),
        Kind("This week, as weather", "The last seven days as little skies, with their cats", WeatherWidgetReceiver::class.java),
        Kind("Your café", "Your café with today's cats; the month's at night", CafeWidgetReceiver::class.java),
        Kind("Friends' table", "Who's focusing, who's on a break (needs an account)", FriendsWidgetReceiver::class.java),
        Kind("A cat with a day", "The time, the date and what your cat is up to", ClockWidgetReceiver::class.java),
    )

    fun supported(ctx: Context) = AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported

    /** Asks the launcher to add this widget; false if it can't (some launchers don't take the request). */
    fun request(ctx: Context, k: Kind): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) {
            KavanaApp.repo.toasts.tryEmit("Press and hold on your home screen, tap Widgets, and look for Chataltul.")
            return false
        }
        return mgr.requestPinAppWidget(ComponentName(ctx, k.receiver), null, null)
    }

    var hideTitles: Boolean
        get() = KavanaApp.repo.prefs.getBoolean("hideTitles", false)
        set(v) { KavanaApp.repo.prefs.edit().putBoolean("hideTitles", v).apply(); WidgetUpdater.refresh(KavanaApp.app) }
}
