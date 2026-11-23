package com.vibepop.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新检查：
 * 从 GitHub Releases 的最新版本读取 update.json，与本地 versionCode 比较。
 * 只做版本比对，下载与安装交给浏览器 / 系统安装器，
 * 因此应用本身不申请 REQUEST_INSTALL_PACKAGES 权限。
 */
object UpdateChecker {

    const val MANIFEST_URL = "https://github.com/mondary/vibepop/releases/latest/download/update.json"

    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "VibePop-FR-Updater"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String
    )

    /** 读取发布清单，网络或格式错误时返回 null */
    fun fetchLatest(): UpdateInfo? {
        val body = httpGetText(MANIFEST_URL) ?: return null
        return try {
            val json = JSONObject(body)
            val apkUrl = json.optString("apkUrl")
            if (apkUrl.isBlank()) return null
            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName"),
                apkUrl = apkUrl,
                notes = json.optString("notes")
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
