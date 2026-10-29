package xyz.tiresia.kavana.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import xyz.tiresia.kavana.KavanaApp
import xyz.tiresia.kavana.Notify
import xyz.tiresia.kavana.op

/** What a tap on a widget does, through the same engine the app uses. They run in the app's process, so the widget needn't be drawn by it. */
object WidgetKeys {
    val MIN = ActionParameters.Key<Int>("min")
    val ID = ActionParameters.Key<String>("id")
    val N = ActionParameters.Key<Int>("n")
    val WHAT = ActionParameters.Key<String>("what")
    val TEXT = ActionParameters.Key<String>("text")
}

object WidgetOps {
    /** A block, started as "Start" does in the app: no title, the last tag and style, and the cat who's coming. */
    suspend fun start(minutes: Int, mode: String = "timer", intention: String = "", fromLater: String? = null) {
        val repo = KavanaApp.repo
        val st = repo.state.value ?: repo.act(op("tick"))?.state ?: return
        val set = st.view.settings
        val tag = set.tag.takeIf { it in set.tags } ?: ""
        repo.act(op("start", "intention" to intention, "minutes" to if (mode == "stopwatch") 180 else minutes, "style" to set.style, "mode" to mode,
            "tag" to tag, "fromLater" to fromLater, "cat" to (if (st.catsOn) st.companion else null)))
    }
}

private suspend fun done(ctx: Context) {
    WidgetUpdater.refresh(ctx) // even if the engine said no (a stale tap), so the widget catches up
}

class StartAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val m = parameters[WidgetKeys.MIN] ?: 25
        WidgetOps.start(if (m < 0) 180 else m, if (m < 0) "stopwatch" else "timer")
        done(context)
    }
}

class AnswerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.ID] ?: return
        KavanaApp.repo.act(op("answer", "id" to id, "n" to (parameters[WidgetKeys.N] ?: 0), "answer" to parameters[WidgetKeys.WHAT]))
        Notify.cancel(context, Notify.ID_PING)
        done(context)
    }
}

class OutcomeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.ID] ?: return
        KavanaApp.repo.act(op("outcome", "id" to id, "outcome" to parameters[WidgetKeys.WHAT]))
        Notify.cancel(context, Notify.ID_PING)
        done(context)
    }
}

class PauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        KavanaApp.repo.act(op("pause")); done(context)
    }
}

class ResumeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        KavanaApp.repo.act(op("resume")); done(context)
    }
}

class EndBreakAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        KavanaApp.repo.act(op("stopBreak")); done(context)
    }
}

/** Collects every reward that's waiting (the ids are in the snapshot the widget was drawn from). */
class CollectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        for (r in WidgetStore.get(context).collect) KavanaApp.repo.act(op("claim", "id" to r.id))
        done(context)
    }
}

/** A Later item ticked off. */
class DoneLaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.ID] ?: return
        KavanaApp.repo.act(op("doneLater", "id" to id)); done(context)
    }
}

/** A block titled with a Later item (which is ticked off as it starts, as in the app). */
class StartLaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.ID] ?: return
        WidgetOps.start(25, "timer", parameters[WidgetKeys.TEXT].orEmpty(), id); done(context)
    }
}

/** The Sound widget's play and pause: the mix stays, only whether it's heard changes (while a block runs). */
class SoundToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ui = xyz.tiresia.kavana.ui.SoundMix
        ui.muted = !ui.muted
        val st = KavanaApp.repo.state.value
        if (st?.view?.active?.let { it.paused == null } == true) {
            if (ui.muted) xyz.tiresia.kavana.Sound.stop() else xyz.tiresia.kavana.Sound.play(context.applicationContext, ui.playable(st))
        }
        done(context)
    }
}
