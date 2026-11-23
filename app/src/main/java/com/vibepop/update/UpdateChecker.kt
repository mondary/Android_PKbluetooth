package com.vibepop.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：
 * 从 GitHub Releases 的 latest 标签读取 update.json 并比较 versionCode。
 * APK 下载与安装交给系统浏览器/安装器处理。仅用于 PK 分支的构建。
 */
object UpdateChecker {

    const val MANIFEST_URL = "https://github.com/mondary/vibepop/releases/latest/download/update.json"

    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "VibePop-FR-Updater"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String
    )

    /** 读取发布清单，网络或格式错误时返回 null */
    fun fetchLatest(): UpdateInfo? {
        // The inherited feed belongs to VibePop and must not update this separate app.
        // Enable update checks only when PKbluetooth has its own release channel.
        return null
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
