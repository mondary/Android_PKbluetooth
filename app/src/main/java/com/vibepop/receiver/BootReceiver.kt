package com.vibepop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vibepop.data.repository.PreferencesRepository
import com.vibepop.service.HeadsetMonitorService

/**
 * Restart the monitoring service after boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferencesRepository(context)
            if (prefs.getPopupConfig().isServiceEnabled) {
                HeadsetMonitorService.start(context)
            }
        }
    }
}
