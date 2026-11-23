package com.vibepop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.vibepop.data.repository.PreferencesRepository
import com.vibepop.service.HeadsetMonitorService

class VibePopApp : Application() {

    companion object {
        const val CHANNEL_ID_SERVICE = "vibepop_headset_monitor_channel"
        lateinit var instance: VibePopApp
            private set
    }

    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        instance = this
        createNotificationChannel()

        // Start the foreground monitor when it is enabled.
        val config = PreferencesRepository(this).getPopupConfig()
        if (config.isServiceEnabled) {
            try {
                HeadsetMonitorService.start(this)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_SERVICE,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
