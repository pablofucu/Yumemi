package com.pablo.yumemi

import android.content.Context
import androidx.room.Room
import com.pablo.yumemi.data.HermesRepository
import com.pablo.yumemi.data.cron.CronRepository
import com.pablo.yumemi.data.chat.TurnRunner
import com.pablo.yumemi.data.local.YumemiDatabase
import com.pablo.yumemi.data.remote.HermesClient
import com.pablo.yumemi.data.remote.NetworkMonitor
import com.pablo.yumemi.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val cronRepository: CronRepository = CronRepository(client, appContext)
    /** Registered once for the process lifetime, never unregistered. */
    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)

    /** App-level scope: assistant turns must outlive any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val turnRunner: TurnRunner = TurnRunner(appContext, repository, client, settings, networkMonitor, appScope)
}
