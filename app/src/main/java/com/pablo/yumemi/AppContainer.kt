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

    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val client: HermesClient by lazy { HermesClient(settings) }
    val database: YumemiDatabase by lazy {
        Room.databaseBuilder(appContext, YumemiDatabase::class.java, "yumemi.db")
            .addMigrations(YumemiDatabase.MIGRATION_1_2, YumemiDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }
    val repository: HermesRepository by lazy { HermesRepository(client, database, settings) }
    val cronRepository: CronRepository by lazy { CronRepository(client, appContext, database.cronResultDao()) }
    /** Registered once for the process lifetime, never unregistered. */
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(appContext) }

    /** App-level scope: assistant turns must outlive any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val turnRunner: TurnRunner by lazy { TurnRunner(appContext, repository, client, settings, networkMonitor, appScope) }

    /** Builds the slow parts (Keystore prefs, Room, TLS to the server) on a background thread while the UI starts. */
    fun warmUp() {
        Thread {
            runCatching {
                settings; database; networkMonitor
                client.prewarm()
            }
        }.start()
    }
}
