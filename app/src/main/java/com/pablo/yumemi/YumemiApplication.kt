package com.pablo.yumemi

import android.app.Application

class YumemiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        com.pablo.yumemi.notify.NotifyWorker.schedule(this)
        com.pablo.yumemi.notify.UpdateWorker.schedule(this)
    }
}
