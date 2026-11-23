package com.vibepop.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    /**
     * Whether the display-over-other-apps permission is granted.
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Open the overlay permission settings.
     */
    fun requestOverlayPermission(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, requestCode)
        }
    }

    /**
     * Whether the Bluetooth permission is granted (BLUETOOTH_CONNECT on Android 12+).
     */
    fun hasBluetoothPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Whether Bluetooth is enabled.
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            @Suppress("DEPRECATION")
            val adapter = bluetoothManager?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Prompt to enable Bluetooth, or open Bluetooth settings.
     */
    fun promptEnableBluetooth(activity: Activity) {
        try {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivity(enableBtIntent)
        } catch (e: Exception) {
            try {
                val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                activity.startActivity(settingsIntent)
            } catch (e2: Exception) {
                // ignore
            }
        }
    }

    /**
     * Request the Bluetooth runtime permission.
     */
    fun requestBluetoothPermission(activity: Activity, requestCode: Int = 1002) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            ActivityCompat.requestPermissions(activity, permissions, requestCode)
        }
    }

    /**
     * Whether notification permission is granted (POST_NOTIFICATIONS on Android 13+).
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Request notification permission.
     */
    fun requestNotificationPermission(activity: Activity, requestCode: Int = 1003) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                requestCode
            )
        }
    }

    /**
     * Whether media read permission is granted.
     */
    fun hasMediaPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasImages = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
            val hasVideos = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED
            hasImages || hasVideos
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request media read permission.
     */
    fun requestMediaPermission(activity: Activity, requestCode: Int = 1004) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                ),
                requestCode
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                requestCode
            )
        }
    }

    /**
     * Check manufacturer-specific lock-screen and background popup permissions.
     */
    fun hasOemLockScreenPermission(context: Context): Boolean {
        val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Redmi", ignoreCase = true) ||
                Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Redmi", ignoreCase = true)

        if (isXiaomi) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return true
            return try {
                val method = appOps.javaClass.getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java
                )
                val opShowWhenLocked = 10020 // MIUI lock-screen display.
                val opBackgroundStart = 10021 // MIUI background popup.
                val result1 = method.invoke(appOps, opShowWhenLocked, android.os.Process.myUid(), context.packageName) as Int
                val result2 = method.invoke(appOps, opBackgroundStart, android.os.Process.myUid(), context.packageName) as Int
                result1 == android.app.AppOpsManager.MODE_ALLOWED && result2 == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) {
                try {
                    val method = appOps.javaClass.getMethod(
                        "checkOpNoThrow",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    val opShowWhenLocked = 10020
                    val result1 = method.invoke(appOps, opShowWhenLocked, android.os.Process.myUid(), context.packageName) as Int
                    result1 == android.app.AppOpsManager.MODE_ALLOWED
                } catch (e2: Exception) {
                    hasOverlayPermission(context)
                }
            }
        }

        // Other manufacturers follow the overlay permission.
        return hasOverlayPermission(context)
    }

    /**
     * Open app details or the manufacturer permission page.
     */
    fun openAppDetailsOrOemSettings(activity: Activity) {
        try {
            // Xiaomi / MIUI / HyperOS permission editor.
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", activity.packageName)
            }
            activity.startActivity(miuiIntent)
            return
        } catch (e: Exception) {}

        try {
            val detailsIntent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(detailsIntent)
        } catch (e: Exception) {}
    }
}
