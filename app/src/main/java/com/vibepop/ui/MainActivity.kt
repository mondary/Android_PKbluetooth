package com.vibepop.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vibepop.R
import com.vibepop.BuildConfig
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.model.PopupConfig
import com.vibepop.databinding.ActivityMainBinding
import com.vibepop.service.HeadsetMonitorService
import com.vibepop.update.UpdateChecker
import com.vibepop.util.MediaThumbnailLoader
import com.vibepop.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentThemeTargetAddress: String? = null
    private var inDetail = false
    private var hasPromptedBluetoothDialog = false
    private var bluetoothReceiverRegistered = false
    private var bondedDeviceItems = listOf<BondedDeviceItem>()
    private var customThumbKey: String? = null

    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
                when (state) {
                    android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                        updatePermissionsUI()
                        Toast.makeText(this@MainActivity, getString(R.string.bt_off_toast), Toast.LENGTH_SHORT).show()
                    }
                    android.bluetooth.BluetoothAdapter.STATE_ON -> {
                        updatePermissionsUI()
                        viewModel.refreshBondedDevices(this@MainActivity)
                        Toast.makeText(this@MainActivity, getString(R.string.bt_on_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 1. 全屏原生相册选择器（直接完整打开系统相册，避免半屏拖拽）
    private val fullGalleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                handleMediaPicked(uri)
            }
        }
    }

    // 2. 备用文件选择器
    private val fallbackPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            handleMediaPicked(uri)
        }
    }

    private fun handleMediaPicked(uri: android.net.Uri) {
        val targetAddress = currentThemeTargetAddress
        if (!targetAddress.isNullOrBlank()) {
            val (success, _) = viewModel.importDeviceMedia(this, targetAddress, uri)
            if (success) {
                loadThemeTargetUI(targetAddress)
                Toast.makeText(this, getString(R.string.import_success_device), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            val (success, type) = viewModel.importCustomMedia(this, uri)
            if (success) {
                val typeName = when (type) {
                    "video" -> getString(R.string.media_type_video_full)
                    "image" -> getString(R.string.media_type_image_full)
                    else -> getString(R.string.media_type_other)
                }
                loadThemeTargetUI(null)
                Toast.makeText(this, getString(R.string.import_success_global, typeName), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. 相册/多媒体权限申请 Launcher
    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionHelper.hasMediaPermission(this)) {
            Toast.makeText(this, getString(R.string.media_perm_granted_toast), Toast.LENGTH_SHORT).show()
            viewModel.refreshPermissions(this)
        } else {
            Toast.makeText(this, getString(R.string.media_perm_needed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 12+ 未授予 BLUETOOTH_CONNECT 时注册蓝牙广播会抛 SecurityException，先检查后注册
        ensureBluetoothReceiverRegistered()

        HeadsetMonitorService.start(this)
        setupNavigation()
        setupDashboardTab()
        setupThemesTab()
        setupDevicesTab()
        setupSettingsTab()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        HeadsetMonitorService.start(this)
        ensureBluetoothReceiverRegistered()
        viewModel.refreshPermissions(this)
        if (viewModel.consumeTargetPolicyNotice()) {
            showTargetPolicyNotice()
        } else {
            checkAndPromptBluetoothEnabled()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bluetoothReceiverRegistered) {
            bluetoothReceiverRegistered = false
            try {
                unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {}
        }
    }

    /**
     * 保证已授权蓝牙权限时注册系统蓝牙状态广播接收器 (Android 12+ 注册需 BLUETOOTH_CONNECT)
     */
    private fun ensureBluetoothReceiverRegistered() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PermissionHelper.hasBluetoothPermission(this)) return
        try {
            registerReceiver(bluetoothStateReceiver, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            bluetoothReceiverRegistered = true
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to register bluetooth state receiver: ${e.message}")
        }
    }

    /**
     * 弹窗响应策略变更为"默认全不响应"后，向升级用户展示一次性说明
     */
    private fun showTargetPolicyNotice() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.target_policy_notice_title))
            .setMessage(getString(R.string.target_policy_notice_message))
            .setPositiveButton(getString(R.string.target_policy_notice_go)) { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_devices
                checkAndPromptBluetoothEnabled()
            }
            .setNegativeButton(getString(R.string.target_policy_notice_later)) { _, _ ->
                checkAndPromptBluetoothEnabled()
            }
            .setOnCancelListener {
                checkAndPromptBluetoothEnabled()
            }
            .show()
    }

    private fun checkAndPromptBluetoothEnabled() {
        if (!PermissionHelper.isBluetoothEnabled(this)) {
            if (!hasPromptedBluetoothDialog) {
                hasPromptedBluetoothDialog = true
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.bt_not_enabled_title))
                    .setMessage(getString(R.string.bt_not_enabled_message))
                    .setPositiveButton(getString(R.string.bt_enable_button)) { _, _ ->
                        PermissionHelper.promptEnableBluetooth(this)
                    }
                    .setNegativeButton(getString(R.string.bt_later), null)
                    .show()
            }
        }
    }

    /**
     * 底部导航：Appareils / Réglages, plus la page de détail d'une cible
     */
    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        switchTab(R.id.nav_devices)
        renderBondedDevices(bondedDeviceItems)
    }

    private fun switchTab(itemId: Int) {
        inDetail = false
        binding.tabThemesLayout.root.visibility = View.GONE
        binding.tabDevicesLayout.root.visibility = if (itemId == R.id.nav_devices) View.VISIBLE else View.GONE
        binding.tabSettingsLayout.root.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
    }

    /** 打开某个目标的配置页；address 为 null 表示全局默认 */
    private fun openDetail(address: String?) {
        inDetail = true
        currentThemeTargetAddress = address
        binding.tabDevicesLayout.root.visibility = View.GONE
        binding.tabSettingsLayout.root.visibility = View.GONE
        binding.tabThemesLayout.root.visibility = View.VISIBLE

        binding.tabThemesLayout.tvDetailTitle.text = if (address == null) {
            getString(R.string.theme_target_global_plain)
        } else {
            bondedDeviceItems.firstOrNull { it.address == address }?.name
                ?: getString(R.string.rename_target_fallback)
        }
        binding.tabThemesLayout.cardGlobalPopupOptions.visibility =
            if (address == null) View.VISIBLE else View.GONE

        loadThemeTargetUI(address)
        binding.tabThemesLayout.root.scrollTo(0, 0)
    }

    private fun closeDetail() {
        if (!inDetail) return
        inDetail = false
        binding.tabThemesLayout.root.visibility = View.GONE
        val onSettings = binding.bottomNav.selectedItemId == R.id.nav_settings
        binding.tabDevicesLayout.root.visibility = if (onSettings) View.GONE else View.VISIBLE
        binding.tabSettingsLayout.root.visibility = if (onSettings) View.VISIBLE else View.GONE
    }

    override fun onBackPressed() {
        if (inDetail) {
            closeDetail()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * 1. 🏠 控制台面板交互绑定
     */
    private fun setupDashboardTab() {
        // Entrée « Réglages globaux »
        binding.tabDevicesLayout.rowGlobalSettings.setOnClickListener { openDetail(null) }

        // Bandeau permissions
        binding.tabDevicesLayout.cardPermissionWarning.setOnClickListener {
            if (!PermissionHelper.isBluetoothEnabled(this)) {
                PermissionHelper.promptEnableBluetooth(this)
            } else {
                binding.bottomNav.selectedItemId = R.id.nav_settings
            }
        }

        // Retour depuis la page de détail
        binding.tabThemesLayout.btnBackToDevices.setOnClickListener { closeDetail() }

        // Aperçu de la cible courante
        binding.tabThemesLayout.btnTestPopup.setOnClickListener { previewCurrentTarget() }

        // Sortie haut-parleur (option globale)
        binding.tabThemesLayout.switchForceSpeakerphone.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                viewModel.updateForceSpeakerphone(isChecked)
            }
        }

        // Durée d'affichage (option globale)
        binding.tabThemesLayout.sliderDismissDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val seconds = value.toInt()
                binding.tabThemesLayout.tvDismissDelayLabel.text = getString(R.string.setting_dismiss_delay, seconds)
                viewModel.updateDismissSeconds(seconds)
            }
        }
    }

    /**
     * 2. 🎨 动效工坊面板交互绑定
     */
    private fun setupThemesTab() {
        val themes = binding.tabThemesLayout

        themes.cardThemeClassic.setOnClickListener {
            applyThemeToCurrentTarget("classic_airpods")
        }
        themes.cardThemeGreatVictory.setOnClickListener {
            applyThemeToCurrentTarget("great_victory")
        }
        themes.cardThemeCustom.setOnClickListener {
            applyThemeToCurrentTarget("custom_media")
        }

        // 每个主题的试听按钮：只预览，不改变当前配置
        themes.btnPreviewClassic.setOnClickListener { previewTheme("classic_airpods") }
        themes.btnPreviewGreatVictory.setOnClickListener { previewTheme("great_victory") }
        themes.btnPreviewCustom.setOnClickListener { previewTheme("custom_media") }

        // 修改弹窗显示名称重命名
        themes.btnRenameTargetDevice.setOnClickListener {
            showRenameDialog()
        }
        themes.cardThemeDeviceName.setOnClickListener {
            showRenameDialog()
        }

        // 从相册选择
        themes.btnPickFromGallery.setOnClickListener {
            requestMediaAndPick()
        }

        // 视频消退规则
        themes.rgVideoDismissMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbVideoOnComplete) "on_complete" else "timer"
            val address = currentThemeTargetAddress
            if (address == null) {
                viewModel.updateVideoDismissMode(mode)
            } else {
                val devConfig = viewModel.getDevicePopupConfig(address)
                viewModel.saveDevicePopupConfig(this, address, devConfig.copy(videoDismissMode = mode))
            }
        }

        // 内置演示视觉库
        buildDemoLibrary()
    }

    /** 内置演示视觉：res/raw 里的 GIF，点按即应用为当前目标的自定义媒体 */
    private data class DemoVisual(
        val labelRes: Int,
        val rawRes: Int,
        val fileName: String
    )

    private val demoVisuals = listOf(
        DemoVisual(R.string.demo_airpods_white, R.raw.demo_airpods_white, "airpods_white"),
        DemoVisual(R.string.demo_open_ear, R.raw.demo_open_ear_black, "open_ear_black"),
        DemoVisual(R.string.demo_shokz, R.raw.demo_shokz, "shokz"),
        DemoVisual(R.string.demo_peugeot, R.raw.demo_peugeot, "peugeot")
    )

    private fun buildDemoLibrary() {
        val container = binding.tabThemesLayout.layoutDemoLibrary
        if (container.childCount > 0) return

        val density = resources.displayMetrics.density
        val size = (88 * density).toInt()
        val gap = (12 * density).toInt()

        for (demo in demoVisuals) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
                isClickable = true
                isFocusable = true
                contentDescription = getString(demo.labelRes)
                setOnClickListener { applyDemoVisual(demo) }
            }

            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_media_thumb)
                clipToOutline = true
                setImageBitmap(decodeDemoThumbnail(demo.rawRes, size))
            }

            val label = TextView(this).apply {
                text = getString(demo.labelRes)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    size,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            item.addView(preview)
            item.addView(label)
            container.addView(item)
        }
    }

    private fun decodeDemoThumbnail(rawRes: Int, targetPx: Int): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeResource(resources, rawRes, bounds)
            val shortest = minOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (shortest / (sample * 2) >= targetPx) {
                sample *= 2
            }
            android.graphics.BitmapFactory.decodeResource(
                resources,
                rawRes,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun applyDemoVisual(demo: DemoVisual) {
        val address = currentThemeTargetAddress
        val applied = viewModel.applyBundledDemo(
            applicationContext,
            address,
            demo.rawRes,
            demo.fileName,
            "image"
        )
        if (!applied) {
            Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            return
        }
        loadThemeTargetUI(address)
        previewTheme("custom_media")
    }

    private fun showRenameDialog() {
        val address = currentThemeTargetAddress
        val currentName = if (address == null) {
            viewModel.popupConfig.value?.customDeviceName ?: ""
        } else {
            viewModel.getDevicePopupConfig(address).customDeviceName
        }

        val inputEditText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(currentName)
            if (currentName.isNotEmpty()) {
                setSelection(currentName.length)
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
            isSingleLine = true
        }

        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            this,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.rename_dialog_hint)
            boxStrokeColor = ContextCompat.getColor(this@MainActivity, R.color.primary)
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (4 * resources.displayMetrics.density).toInt()
            )
            addView(inputEditText)
        }

        val targetTitle = if (address == null) getString(R.string.rename_global_default) else {
            bondedDeviceItems.firstOrNull { it.address == address }?.name ?: getString(R.string.rename_target_fallback)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rename_dialog_title))
            .setMessage(getString(R.string.rename_dialog_message, targetTitle))
            .setView(inputLayout)
            .setPositiveButton(getString(R.string.btn_confirm)) { _, _ ->
                val newName = inputEditText.text?.toString()?.trim() ?: ""
                if (address == null) {
                    viewModel.updateDeviceName(newName)
                } else {
                    val devConfig = viewModel.getDevicePopupConfig(address)
                    viewModel.saveDevicePopupConfig(this, address, devConfig.copy(customDeviceName = newName))
                }
                binding.tabThemesLayout.tvCurrentTargetDeviceName.text = if (newName.isNotBlank()) newName else getString(R.string.name_empty_placeholder)
                Toast.makeText(
                    this,
                    if (newName.isNotBlank()) getString(R.string.rename_updated_toast, newName) else getString(R.string.rename_cleared_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun applyThemeToCurrentTarget(theme: String) {
        val address = currentThemeTargetAddress
        if (address == null) {
            viewModel.updateAnimationTheme(theme)
            loadThemeTargetUI(null)
        } else {
            val devConfig = viewModel.getDevicePopupConfig(address)
            val updated = if (theme != "custom_media") {
                devConfig.copy(animationTheme = theme, customMediaPath = null, customMediaType = "preset")
            } else {
                devConfig.copy(animationTheme = theme)
            }
            viewModel.saveDevicePopupConfig(this, address, updated)
            loadThemeTargetUI(address)
        }
    }

    private fun loadThemeTargetUI(targetAddress: String?) {
        val config = if (targetAddress == null) {
            viewModel.popupConfig.value ?: return
        } else {
            viewModel.getDevicePopupConfig(targetAddress)
        }

        val themes = binding.tabThemesLayout
        val currentName = if (config.customDeviceName.isNotBlank()) config.customDeviceName else getString(R.string.name_empty_placeholder)
        themes.tvCurrentTargetDeviceName.text = currentName

        // Appareil personnalisable mais popup désactivé : on le dit clairement
        val targetEnabled = targetAddress?.let { address ->
            bondedDeviceItems.firstOrNull { it.address == address }?.isSelected
        } ?: true
        themes.tvTargetDisabledHint.visibility = if (targetEnabled) View.GONE else View.VISIBLE

        updateThemeCardsUI(config.animationTheme, config.customMediaType, config.customMediaPath)
        updateCustomMediaThumbnail(config.customMediaPath, config.customMediaType)

        if (config.videoDismissMode == "on_complete") {
            themes.rbVideoOnComplete.isChecked = true
        } else {
            themes.rbVideoTimer.isChecked = true
        }
    }

    private fun requestMediaAndPick() {
        if (!PermissionHelper.hasMediaPermission(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                mediaPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_IMAGES
                    )
                )
            } else {
                mediaPermissionLauncher.launch(
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
            return
        }

        try {
            val intent = Intent(Intent.ACTION_PICK).apply {
                setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*,video/*")
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            fullGalleryPickerLauncher.launch(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                fullGalleryPickerLauncher.launch(fallbackIntent)
            } catch (e2: Exception) {
                fallbackPickerLauncher.launch("*/*")
            }
        }
    }

    /**
     * 3. 🎧 设备管理面板交互绑定
     */
    private fun setupDevicesTab() {
        val devices = binding.tabDevicesLayout
        devices.btnRefreshDevices.setOnClickListener {
            if (!PermissionHelper.isBluetoothEnabled(this)) {
                Toast.makeText(this, getString(R.string.bt_off_prompt_toast), Toast.LENGTH_SHORT).show()
                PermissionHelper.promptEnableBluetooth(this)
            } else {
                viewModel.refreshBondedDevices(this)
                Toast.makeText(this, getString(R.string.bt_refreshed_toast), Toast.LENGTH_SHORT).show()
            }
        }

        devices.btnClearAllTargets.setOnClickListener {
            val enabledCount = viewModel.popupConfig.value?.targetDeviceAddresses?.size ?: 0
            if (enabledCount <= 0) {
                Toast.makeText(this, getString(R.string.device_none_enabled_toast), Toast.LENGTH_SHORT).show()
            } else {
                viewModel.clearAllTargetDevices()
                Toast.makeText(this, getString(R.string.device_clear_all_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 4. ⚙️ 系统设置与权限面板交互绑定
     */
    private fun setupSettingsTab() {
        val settings = binding.tabSettingsLayout

        settings.btnGrantOverlay.setOnClickListener {
            PermissionHelper.requestOverlayPermission(this)
        }

        settings.btnGrantBluetooth.setOnClickListener {
            if (!PermissionHelper.hasBluetoothPermission(this)) {
                PermissionHelper.requestBluetoothPermission(this)
            } else if (!PermissionHelper.isBluetoothEnabled(this)) {
                PermissionHelper.promptEnableBluetooth(this)
            } else {
                Toast.makeText(this, getString(R.string.bt_granted_on_toast), Toast.LENGTH_SHORT).show()
            }
        }

        settings.btnGrantNotification.setOnClickListener {
            PermissionHelper.requestNotificationPermission(this)
        }

        settings.btnGrantMedia.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                mediaPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_IMAGES
                    )
                )
            } else {
                mediaPermissionLauncher.launch(
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                )
            }
        }

        settings.btnOpenOemSettings.setOnClickListener {
            PermissionHelper.openAppDetailsOrOemSettings(this)
        }

        // 应用内更新
        settings.tvUpdateStatus.text = getString(R.string.update_current_version, BuildConfig.VERSION_NAME)
        settings.btnCheckUpdate.setOnClickListener { checkForUpdates() }
    }

    /**
     * 统一观察 ViewModel 状态
     */
    private fun observeViewModel() {
        // 权限状态驱动
        viewModel.isOverlayGranted.observe(this) { updatePermissionsUI() }
        viewModel.isBluetoothGranted.observe(this) { updatePermissionsUI() }
        viewModel.isNotificationGranted.observe(this) { updatePermissionsUI() }
        viewModel.isMediaGranted.observe(this) { updatePermissionsUI() }
        viewModel.isOemGranted.observe(this) { updatePermissionsUI() }

        // 服务状态
        viewModel.isServiceRunning.observe(this) { running ->
            val dash = binding.tabSettingsLayout
            if (dash.switchService.isChecked != running) {
                dash.switchService.isChecked = running
            }
            if (running) {
                dash.tvServiceStatusBadge.text = getString(R.string.dash_service_running)
                dash.tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
                dash.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_green)
                dash.tvServiceSubStatus.text = getString(R.string.dash_service_sub_running)
            } else {
                dash.tvServiceStatusBadge.text = getString(R.string.dash_service_stopped)
                dash.tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected))
                dash.viewStatusDot.setBackgroundResource(R.drawable.bg_status_dot_gray)
                dash.tvServiceSubStatus.text = getString(R.string.dash_service_sub_stopped)
            }
        }

        // 已配对设备列表展示与动效工坊下拉框联动
        viewModel.bondedDevices.observe(this) { devices ->
            bondedDeviceItems = devices
            renderBondedDevices(devices)
        }

        // 弹窗参数配置
        viewModel.popupConfig.observe(this) { config ->
            val detail = binding.tabThemesLayout

            if (detail.switchForceSpeakerphone.isChecked != config.isForceSpeakerphone) {
                detail.switchForceSpeakerphone.isChecked = config.isForceSpeakerphone
            }

            detail.sliderDismissDelay.value = config.autoDismissSeconds.toFloat()
            detail.tvDismissDelayLabel.text = getString(R.string.setting_dismiss_delay, config.autoDismissSeconds)

            binding.tabDevicesLayout.tvGlobalThemeSummary.text =
                getString(R.string.devices_global_summary, themeLabel(config.animationTheme))

            if (currentThemeTargetAddress == null && inDetail) {
                loadThemeTargetUI(null)
            }
        }
    }

    private fun updatePermissionsUI() {
        val overlay = viewModel.isOverlayGranted.value ?: false
        val bluetooth = viewModel.isBluetoothGranted.value ?: false
        val isBtEnabled = PermissionHelper.isBluetoothEnabled(this)
        val notification = viewModel.isNotificationGranted.value ?: false
        val media = viewModel.isMediaGranted.value ?: false
        val oem = viewModel.isOemGranted.value ?: false

        val settings = binding.tabSettingsLayout
        updatePermissionButton(settings.btnGrantOverlay, overlay)
        if (!bluetooth) {
            updatePermissionButton(settings.btnGrantBluetooth, false)
        } else if (!isBtEnabled) {
            settings.btnGrantBluetooth.text = getString(R.string.bt_enable_button)
            settings.btnGrantBluetooth.isEnabled = true
            settings.btnGrantBluetooth.setTextColor(ContextCompat.getColor(this, R.color.primary))
            settings.btnGrantBluetooth.strokeColor = ContextCompat.getColorStateList(this, R.color.primary)
        } else {
            updatePermissionButton(settings.btnGrantBluetooth, true)
        }
        updatePermissionButton(settings.btnGrantNotification, notification)
        updatePermissionButton(settings.btnGrantMedia, media)
        updatePermissionButton(settings.btnOpenOemSettings, oem, isOem = true)

        // 控制台权限与状态警告条
        val missingList = mutableListOf<String>()
        if (!overlay) missingList.add(getString(R.string.perm_missing_overlay))
        if (!bluetooth) missingList.add(getString(R.string.perm_missing_bluetooth))
        if (!notification) missingList.add(getString(R.string.perm_missing_notification))

        val dash = binding.tabDevicesLayout
        if (missingList.isNotEmpty()) {
            dash.cardPermissionWarning.visibility = View.VISIBLE
            dash.tvMissingPermSummary.text = getString(
                R.string.dash_missing_perm_summary,
                missingList.joinToString(getString(R.string.list_separator))
            )
        } else if (!isBtEnabled) {
            dash.cardPermissionWarning.visibility = View.VISIBLE
            dash.tvMissingPermSummary.text = getString(R.string.dash_bt_off_warning)
        } else {
            dash.cardPermissionWarning.visibility = View.GONE
        }
    }

    private fun updatePermissionButton(
        button: com.google.android.material.button.MaterialButton,
        granted: Boolean,
        isOem: Boolean = false
    ) {
        if (granted) {
            button.text = getString(R.string.btn_granted)
            button.isEnabled = isOem // 厂商设置允许再次点击查看
            button.setTextColor(ContextCompat.getColor(this, R.color.status_connected))
            button.strokeColor = ContextCompat.getColorStateList(this, R.color.status_connected)
        } else {
            button.text = if (isOem) getString(R.string.btn_go_settings) else getString(R.string.btn_grant)
            button.isEnabled = true
            button.setTextColor(ContextCompat.getColor(this, R.color.primary))
            button.strokeColor = ContextCompat.getColorStateList(this, R.color.primary)
        }
    }

    private fun updateThemeCardsUI(theme: String, mediaType: String, mediaPath: String?) {
        val themes = binding.tabThemesLayout

        val activeStrokeColor = ContextCompat.getColor(this, R.color.primary)
        val defaultStrokeColor = ContextCompat.getColor(this, R.color.card_stroke)
        val activeStrokeWidth = (2 * resources.displayMetrics.density).toInt()
        val defaultStrokeWidth = (1 * resources.displayMetrics.density).toInt()

        // 重置三张卡片边框与对勾
        themes.cardThemeClassic.strokeColor = defaultStrokeColor
        themes.cardThemeClassic.strokeWidth = defaultStrokeWidth
        themes.ivCheckClassic.visibility = View.GONE

        themes.cardThemeGreatVictory.strokeColor = defaultStrokeColor
        themes.cardThemeGreatVictory.strokeWidth = defaultStrokeWidth
        themes.ivCheckGreatVictory.visibility = View.GONE

        themes.cardThemeCustom.strokeColor = defaultStrokeColor
        themes.cardThemeCustom.strokeWidth = defaultStrokeWidth
        themes.ivCheckCustom.visibility = View.GONE

        if (theme == "great_victory") {
            themes.cardThemeGreatVictory.strokeColor = activeStrokeColor
            themes.cardThemeGreatVictory.strokeWidth = activeStrokeWidth
            themes.ivCheckGreatVictory.visibility = View.VISIBLE
        } else if (theme == "custom_media") {
            themes.cardThemeCustom.strokeColor = activeStrokeColor
            themes.cardThemeCustom.strokeWidth = activeStrokeWidth
            themes.ivCheckCustom.visibility = View.VISIBLE
            val typeDesc = when (mediaType) {
                "video" -> getString(R.string.media_type_video_mp4)
                "image" -> getString(R.string.media_type_image_gif)
                "lottie" -> getString(R.string.media_type_lottie)
                else -> getString(R.string.media_type_other)
            }
            val fileName = mediaPath?.let { java.io.File(it).name } ?: ""
            themes.tvCustomMediaDetail.text = if (fileName.isNotEmpty()) getString(R.string.media_active_hint, typeDesc, fileName) else getString(R.string.media_active_hint_no_file, typeDesc)
        } else {
            themes.cardThemeClassic.strokeColor = activeStrokeColor
            themes.cardThemeClassic.strokeWidth = activeStrokeWidth
            themes.ivCheckClassic.visibility = View.VISIBLE
        }
    }

    private fun renderBondedDevices(devices: List<BondedDeviceItem>) {
        val container = binding.tabDevicesLayout.layoutBondedDevicesContainer
        container.removeAllViews()

        val selectedCount = devices.count { it.isSelected }
        binding.tabDevicesLayout.tvEnabledDevicesSummary.text = if (selectedCount > 0) {
            getString(R.string.dash_devices_enabled, selectedCount)
        } else {
            getString(R.string.dash_devices_none)
        }

        if (devices.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = getString(R.string.device_empty_hint)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            container.addView(emptyTv)
            return
        }

        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val cardStrokeColor = ContextCompat.getColor(this, R.color.card_stroke)
        val activeBgColor = ContextCompat.getColor(this, R.color.card_selected_bg)
        val defaultBgColor = ContextCompat.getColor(this, R.color.bg_card_dark)
        val textPrimaryColor = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondaryColor = ContextCompat.getColor(this, R.color.text_secondary)
        val statusConnectedColor = ContextCompat.getColor(this, R.color.status_connected)

        val density = resources.displayMetrics.density

        for (device in devices) {
            val isSelected = device.isSelected

            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16f * density
                strokeColor = if (isSelected) primaryColor else cardStrokeColor
                strokeWidth = ((if (isSelected) 2 else 1) * density).toInt()
                setCardBackgroundColor(if (isSelected) activeBgColor else defaultBgColor)
                cardElevation = 2f * density
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (12 * density).toInt())
                }
                layoutParams = lp
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                    (16 * density).toInt(),
                    (14 * density).toInt()
                )
            }

            // 1. 左侧状态圆点
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((10 * density).toInt(), (10 * density).toInt())
                setBackgroundResource(if (isSelected) R.drawable.bg_status_dot_green else R.drawable.bg_status_dot_gray)
            }
            row.addView(dot)

            // 2. 中间耳机信息
            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).toInt()
                    marginEnd = (8 * density).toInt()
                }
                layoutParams = lp
            }

            val nameTv = TextView(this).apply {
                text = device.name
                setTextColor(textPrimaryColor)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val statusSubTv = TextView(this).apply {
                text = if (isSelected) getString(R.string.device_enabled_status) else getString(R.string.device_disabled_status, device.address)
                setTextColor(if (isSelected) statusConnectedColor else textSecondaryColor)
                textSize = 11f
                setPadding(0, (2 * density).toInt(), 0, 0)
            }

            infoLayout.addView(nameTv)
            infoLayout.addView(statusSubTv)
            row.addView(infoLayout)

            // 3. 右侧状态开关（仅点击此处时才切换开启/关闭状态）
            val switchBtn = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                isChecked = isSelected
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    viewModel.toggleTargetDevice(device.address)
                }
            }
            row.addView(switchBtn)

            val chevron = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
                    marginStart = (8 * density).toInt()
                }
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(textSecondaryColor)
            }
            row.addView(chevron)

            card.setOnClickListener { openDetail(device.address) }

            card.addView(row)
            container.addView(card)
        }
    }

    private fun themeLabel(theme: String): String = when (theme) {
        "great_victory" -> getString(R.string.dash_theme_great_victory)
        "custom_media" -> getString(R.string.dash_theme_custom)
        else -> getString(R.string.dash_theme_classic)
    }

    /** 预览当前目标的完整配置（不修改任何偏好） */
    private fun previewCurrentTarget() {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, getString(R.string.overlay_needed_toast), Toast.LENGTH_SHORT).show()
            binding.bottomNav.selectedItemId = R.id.nav_settings
            PermissionHelper.requestOverlayPermission(this)
            return
        }
        val config = currentTargetConfig() ?: return
        viewModel.previewPopup(applicationContext, config, previewDeviceState())
    }

    /**
     * 试听某个主题：仅用于预览，不写回任何偏好
     */
    private fun previewTheme(theme: String) {
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, getString(R.string.overlay_needed_toast), Toast.LENGTH_SHORT).show()
            binding.bottomNav.selectedItemId = R.id.nav_settings
            PermissionHelper.requestOverlayPermission(this)
            return
        }

        val base = currentTargetConfig() ?: return
        val config = if (theme == "custom_media") {
            if (base.customMediaPath.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.preview_need_media), Toast.LENGTH_SHORT).show()
                return
            }
            base.copy(animationTheme = theme)
        } else {
            base.copy(animationTheme = theme, customMediaPath = null, customMediaType = "preset")
        }

        viewModel.previewPopup(applicationContext, config, previewDeviceState())
    }

    private fun currentTargetConfig(): PopupConfig? =
        currentThemeTargetAddress?.let { viewModel.getDevicePopupConfig(it) } ?: viewModel.popupConfig.value

    /** 预览时展示当前目标耳机的真实名称，没有选中设备时退回通用文案 */
    private fun previewDeviceState(): DeviceBatteryState {
        val address = currentThemeTargetAddress ?: return DeviceBatteryState.mock()
        val deviceName = bondedDeviceItems.firstOrNull { it.address == address }?.name ?: ""
        return DeviceBatteryState(
            deviceName = deviceName,
            deviceAddress = address,
            isConnected = true,
            batteryLevel = 85,
            isCharging = false
        )
    }

    /** 为自定义媒体卡片生成缩略图，失败时回退到调色板图标 */
    private fun updateCustomMediaThumbnail(path: String?, type: String) {
        val themes = binding.tabThemesLayout
        val lastModified = path?.let { java.io.File(it).lastModified() } ?: 0L
        val key = "$path|$type|$lastModified"
        if (key == customThumbKey) return
        customThumbKey = key

        if (path.isNullOrBlank() || type == "lottie") {
            showCustomMediaIcon()
            return
        }

        val targetPx = (48 * resources.displayMetrics.density).toInt()
        val isVideo = type == "video" || path.endsWith(".mp4", true) || path.endsWith(".webm", true)

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                MediaThumbnailLoader.load(path, isVideo, targetPx)
            }
            if (customThumbKey != key) return@launch
            if (bitmap != null) {
                themes.ivCustomMediaThumb.setImageBitmap(bitmap)
                themes.ivCustomMediaThumb.visibility = View.VISIBLE
                themes.ivCustomMediaIcon.visibility = View.GONE
            } else {
                showCustomMediaIcon()
            }
        }
    }

    private fun showCustomMediaIcon() {
        val themes = binding.tabThemesLayout
        themes.ivCustomMediaThumb.setImageDrawable(null)
        themes.ivCustomMediaThumb.visibility = View.GONE
        themes.ivCustomMediaIcon.visibility = View.VISIBLE
    }

    /**
     * 检查 GitHub 上的最新版本，有新版本时提示下载
     */
    private fun checkForUpdates() {
        val settings = binding.tabSettingsLayout
        settings.btnCheckUpdate.isEnabled = false
        settings.tvUpdateStatus.text = getString(R.string.update_checking)

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            settings.btnCheckUpdate.isEnabled = true

            if (info == null) {
                settings.tvUpdateStatus.text = getString(R.string.update_check_failed)
                return@launch
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                settings.tvUpdateStatus.text = getString(R.string.update_up_to_date, info.versionName)
                return@launch
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.update_available_title))
                .setMessage(getString(R.string.update_available_message, info.versionName, BuildConfig.VERSION_NAME))
                .setPositiveButton(getString(R.string.update_download)) { _, _ -> startUpdateDownload(info) }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
            settings.tvUpdateStatus.text = getString(R.string.update_available_title)
        }
    }

    /**
     * Téléchargement via le gestionnaire du système : l'APK arrive dans la barre de
     * notifications, et c'est le système qui lance l'installation (l'app n'a donc pas
     * besoin de la permission « installer des applications inconnues »).
     */
    private fun startUpdateDownload(info: UpdateChecker.UpdateInfo) {
        val settings = binding.tabSettingsLayout
        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(info.apkUrl))
                .setTitle("VibePop ${info.versionName}")
                .setDescription(getString(R.string.update_download_notification))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    this,
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "VibePop-${info.versionName}.apk"
                )

            val manager = getSystemService(android.app.DownloadManager::class.java)
            manager?.enqueue(request)

            settings.tvUpdateStatus.text = getString(R.string.update_download_started)
            Toast.makeText(this, getString(R.string.update_download_started), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            settings.tvUpdateStatus.text = getString(R.string.update_download_failed)
        }
    }
}
