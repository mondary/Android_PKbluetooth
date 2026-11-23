package com.vibepop.update

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内更新：
 * 从 GitHub Releases 的 latest 标签读取 update.json，比较 versionCode，
 * 下载新的 APK 并用 SHA-256 校验。仅用于 PK 分支的构建。
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
        val body = httpGetText(MANIFEST_URL) ?: return null
        return try {
            val json = JSONObject(body)
            val apkUrl = json.optString("apkUrl")
            if (apkUrl.isBlank()) return null
            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName"),
                apkUrl = apkUrl,
                sha256 = json.optString("sha256"),
                notes = json.optString("notes")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Télécharge l’APK et transmet la progression quand le serveur la fournit. */
    fun downloadApk(url: String, target: File, onProgress: (Int) -> Unit): Boolean {
        target.parentFile?.mkdirs()
        var connection: HttpURLConnection? = null
        var complete = false
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode !in 200..299) return false

            val totalBytes = connection.contentLengthLong
            var receivedBytes = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        receivedBytes += count
                        if (totalBytes > 0) {
                            val percent = (receivedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
            complete = target.isFile && target.length() > 0
            complete
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
            if (!complete) target.delete()
        }
    }

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    } catch (e: Exception) {
        null
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
