package com.pablo.yumemi

import android.content.Context
import androidx.room.Room
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.local.YumemiDatabase
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.settings.SettingsStore

/** Manual dependency container. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsStore = SettingsStore(appContext)
    val client: HermesClient = HermesClient(settings)
    val database: YumemiDatabase =
        Room.databaseBuilder(appContext, YumemiDatabase::class.java, "yumemi.db")
            .fallbackToDestructiveMigration()
            .build()
    val repository: HermesRepository = HermesRepository(client, database, settings)
}
