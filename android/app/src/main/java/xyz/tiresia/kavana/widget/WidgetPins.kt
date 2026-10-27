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
