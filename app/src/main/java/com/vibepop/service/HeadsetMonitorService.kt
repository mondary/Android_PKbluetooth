package com.vibepop.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vibepop.R
import com.vibepop.VibePopApp
import com.vibepop.receiver.HeadsetBroadcastReceiver
import com.vibepop.ui.MainActivity
import com.vibepop.util.PermissionHelper

/**
 * Foreground Bluetooth monitor that keeps the receiver active.
 */
class HeadsetMonitorService : Service() {

    companion object {
        private const val TAG = "HeadsetMonitorService"
        private const val NOTIFICATION_ID = 10001
        var isServiceRunning = false
            private set

        fun start(context: Context) {
            try {
                if (!com.vibepop.data.repository.PreferencesRepository(context).getPopupConfig().isServiceEnabled) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PermissionHelper.hasBluetoothPermission(context)) {
                    Log.w(TAG, "Bluetooth permission not granted yet, skip starting FGS")
                    return
                }
                val intent = Intent(context, HeadsetMonitorService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HeadsetMonitorService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, HeadsetMonitorService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop HeadsetMonitorService: ${e.message}")
            }
        }
    }

    private var headsetReceiver: HeadsetBroadcastReceiver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gattReader by lazy { GattBattery(this) { addr, percent, _ ->
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val name = runCatching { adapter?.getRemoteDevice(addr)?.name }.getOrNull() ?: addr
        com.vibepop.data.repository.BatteryHistory(this).use { it.record(addr, name, percent) }
        handler.post {
            com.vibepop.overlay.PopupWindowManager.updateBatteryIfShowing(addr, percent)
            com.vibepop.ui.PopupActivity.updateBatteryIfShowing(addr, percent)
        }
    } }
    private val readGatt = object : Runnable {
        override fun run() {
            runCatching {
                val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
                val devices = manager?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT).orEmpty()
                gattReader.poll(devices.filter { it.bondState == BluetoothDevice.BOND_BONDED })
            }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HeadsetMonitorService onCreate")
        startAsForeground()
        registerHeadsetReceiver()
        handler.post(readGatt)
        isServiceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HeadsetMonitorService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "HeadsetMonitorService onDestroy")
        unregisterHeadsetReceiver()
        handler.removeCallbacksAndMessages(null)
        gattReader.close()
        isServiceRunning = false
    }

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, VibePopApp.CHANNEL_ID_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_desc))
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setLargeIcon(appIconBitmap())
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (PermissionHelper.hasBluetoothPermission(this)) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification, 0)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startForeground with type: ${e.message}, falling back to basic notification")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {
                stopSelf()
            }
        }
    }


    /** Render the adaptive launcher icon to a bitmap for the notification shade. */
    private fun appIconBitmap(): android.graphics.Bitmap? = try {
        val size = (64 * resources.displayMetrics.density).toInt()
        val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        if (drawable == null) null else android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    } catch (e: Exception) {
        null
    }

    private fun registerHeadsetReceiver() {
        if (headsetReceiver == null) {
            headsetReceiver = HeadsetBroadcastReceiver()
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(HeadsetBroadcastReceiver.ACTION_BATTERY_LEVEL_CHANGED)
            }
            registerReceiver(headsetReceiver, filter)
            Log.d(TAG, "HeadsetBroadcastReceiver registered dynamically")
        }
    }

    private fun unregisterHeadsetReceiver() {
        headsetReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "HeadsetBroadcastReceiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
            headsetReceiver = null
        }
    }
}
