package xyz.tiresia.kavana

import android.app.Application

class KavanaApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        app = this
        repo = Repo(this)
        Notify.channels(this)
        xyz.tiresia.kavana.widget.Shortcuts.publish(this)
    }

    companion object {
        lateinit var app: KavanaApp
            private set
        val repo: Repo get() = app.repo
    }
}
