package com.pablo.yumemi.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.pablo.yumemi.MainActivity

/** True while the app is on screen; the user sees changes themselves then. */
object AppVisibility {
    @Volatile
    var visible = false
}

/** Notifications for new chats, replies, cron results and feed reports. */
object Notifier {
    const val EXTRA_ROUTE = "yumemi_route"

    private const val CH_CHATS = "chats"
    private const val CH_REPORTS = "reports"
    private const val CH_UPDATES = "updates"

    fun chat(context: Context, threadId: String, title: String, text: String, route: String) =
        post(context, CH_CHATS, "chat:$threadId", title.ifBlank { "Conversación nueva" }, text, route)

    fun report(context: Context, key: String, title: String, text: String, route: String) =
        post(context, CH_REPORTS, key, title, text, route)

    fun update(context: Context, version: String) =
        post(context, CH_UPDATES, "update", "Yumemi $version disponible", "Ábrela y pulsa Actualizar en Ajustes.", "settings")

    private fun post(context: Context, channel: String, key: String, title: String, text: String, route: String) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH_CHATS, "Chats", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_REPORTS, "Informes y cronjobs", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Actualizaciones", NotificationManager.IMPORTANCE_LOW))
        }
        val id = key.hashCode()
        val open = PendingIntent.getActivity(
            app,
            id,
            Intent(app, MainActivity::class.java)
                .putExtra(EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = text.trim().take(300)
        nm.notify(
            id,
            NotificationCompat.Builder(app, channel)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title.take(80))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
