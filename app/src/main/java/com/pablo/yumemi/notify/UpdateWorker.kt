package com.pablo.yumemi.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pablo.yumemi.YumemiApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Once a day, looks for a newer release on GitHub and notifies when there is one. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val updater = (applicationContext as YumemiApplication).container.updater
        val latest = try {
            updater.fetchLatest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.success()
        } ?: return Result.success()
        val prefs = applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)
        // Announce each version only once.
        if (prefs.getString("notified", null) != latest.version) {
            prefs.edit().putString("notified", latest.version).apply()
            Notifier.update(applicationContext, latest.version)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "yumemi-update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
