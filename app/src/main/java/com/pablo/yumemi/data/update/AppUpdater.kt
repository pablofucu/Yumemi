package com.pablo.yumemi.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(val version: String, val apkUrl: String, val notes: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Looks for newer signed APKs in the GitHub Releases of the repo, downloads and hands them to the installer. */
class AppUpdater(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** Returns the newer release, or null when up to date. Throws on network or parse errors. */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use {
            check(it.isSuccessful) { "GitHub respondió ${it.code}" }
            it.body!!.string()
        }
        val release = JSON.decodeFromString<Release>(body)
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        val version = release.tagName.removePrefix("v")
        if (!isNewer(version, currentVersion)) null else UpdateInfo(version, asset.url, release.body.orEmpty())
    }

    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = try {
            fetchLatest()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "No se pudo comprobar")
        }
    }

    /** Downloads the APK and opens the system installer (it always asks the user to confirm). */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        _state.value = UpdateState.Downloading(info)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val out = File(dir, "yumemi-${info.version}.apk")
                http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use {
                    check(it.isSuccessful) { "Descarga fallida (${it.code})" }
                    out.outputStream().use { sink -> it.body!!.byteStream().copyTo(sink) }
                }
                out
            }
            install(file)
            _state.value = UpdateState.Available(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "No se pudo descargar")
        }
    }

    private fun install(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // First time: the user has to allow Yumemi to install apps; they tap the button again afterwards.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        const val REPO = "pablofucu/Yumemi"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Compares dotted numeric versions ("0.10.0" > "0.9.2"); anything non-numeric counts as 0. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
