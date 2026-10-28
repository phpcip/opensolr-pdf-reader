package com.opensolr.pdf.update

import android.content.Context
import com.opensolr.pdf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** The GitHub build checks the latest GitHub release once a day; the only network the app uses. */
object Updates {

    data class Update(val version: String, val apkUrl: String)

    const val AVAILABLE = true

    private const val LATEST = "https://api.github.com/repos/phpcip/opensolr-pdf-reader/releases/latest"
    const val APK_LATEST = "https://github.com/phpcip/opensolr-pdf-reader/releases/latest/download/opensolr-pdf-reader.apk"

    suspend fun check(): Result<Update?> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST).openConnection() as HttpsURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext Result.failure(IOException("GitHub answered ${conn.responseCode}"))
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext Result.success(null)
                val tag = json.optString("tag_name").removePrefix("v")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext Result.success(null)
                Result.success(Update(tag, apkOf(json, tag)))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun apkOf(json: JSONObject, tag: String): String {
        val assets = json.optJSONArray("assets") ?: return APK_LATEST
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            if (a.optString("name") == "opensolr-pdf-reader.apk" && url.startsWith("https://github.com/phpcip/opensolr-pdf-reader/releases/download/v$tag/")) return url
        }
        return APK_LATEST
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.trim().split('.').map { it.toIntOrNull() ?: return false }
        val b = current.trim().split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun fromStore(context: Context): Boolean = SelfUpdate.fromStore(context)
    fun canInstall(context: Context): Boolean = SelfUpdate.canInstall(context)
    fun openInstallPermission(context: Context) = SelfUpdate.openInstallPermission(context)
    sealed class Outcome {
        object Started : Outcome()
        object NeedsPermission : Outcome()
        object Invalid : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    suspend fun install(context: Context, update: Update, onProgress: (Int) -> Unit): Outcome =
        when (val o = SelfUpdate.downloadAndInstall(context, update.apkUrl, onProgress)) {
            is SelfUpdate.Outcome.Started -> Outcome.Started
            is SelfUpdate.Outcome.NeedsPermission -> Outcome.NeedsPermission
            is SelfUpdate.Outcome.Invalid -> Outcome.Invalid
            is SelfUpdate.Outcome.Failed -> Outcome.Failed(o.message)
        }
}
