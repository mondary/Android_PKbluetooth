package com.vibepop.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetch the latest GitHub release manifest and compare its version code.
 * The browser and system installer handle APK downloads and installation.
 */
object UpdateChecker {

    const val MANIFEST_URL = "https://github.com/mondary/Android_PKbluetooth/releases/latest/download/update.json"

    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "VibePop-FR-Updater"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String
    )

    /** Return the release manifest, or null on network or format errors. */
    fun fetchLatest(): UpdateInfo? {
        val text = httpGetText(MANIFEST_URL) ?: return null
        return try {
            val root = JSONObject(text)
            UpdateInfo(
                versionCode = root.getInt("versionCode"),
                versionName = root.getString("versionName"),
                apkUrl = root.getString("apkUrl"),
                sha256 = root.optString("sha256"),
                notes = root.optString("notes")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGetText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
