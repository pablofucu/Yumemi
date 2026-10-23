package com.pablo.yumemi.notify

import android.content.Context

/** What has already been announced (or deliberately skipped), so nothing is notified twice. */
class SeenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("yumemi_notify", Context.MODE_PRIVATE)

    /** False until the first check has recorded what already existed. */
    var initialized: Boolean
        get() = prefs.getBoolean(KEY_INIT, false)
        set(v) {
            if (v && !prefs.contains(KEY_BASE)) prefs.edit().putLong(KEY_BASE, System.currentTimeMillis()).apply()
            prefs.edit().putBoolean(KEY_INIT, v).apply()
        }

    /** Threads never seen before count as announced up to this time (when notifications were set up). */
    fun baseline(now: Long): Long = prefs.getLong(KEY_BASE, now)

    fun chat(id: String, default: Long): Long = prefs.getLong("chat:$id", default)
    fun setChat(id: String, updatedAt: Long) = prefs.edit().putLong("chat:$id", updatedAt).apply()

    /** A reply that finished in this app is already known; the server clock may run slightly ahead. */
    fun markChatHandled(id: String) = setChat(id, System.currentTimeMillis() + CLOCK_SLACK_MS)

    fun feed(tag: String, default: Long): Long = prefs.getLong("feed:$tag", default)
    fun setFeed(tag: String, runAt: Long) = prefs.edit().putLong("feed:$tag", runAt).apply()

    fun cronIds(): Set<String> = prefs.getStringSet(KEY_CRON, null).orEmpty().toSet()
    fun addCronIds(ids: Collection<String>) {
        prefs.edit().putStringSet(KEY_CRON, HashSet(cronIds() + ids)).apply()
    }

    private companion object {
        const val KEY_INIT = "initialized"
        const val KEY_BASE = "baseline"
        const val KEY_CRON = "cron_ids"
        const val CLOCK_SLACK_MS = 5 * 60_000L
    }
}
