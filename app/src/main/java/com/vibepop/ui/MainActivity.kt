package com.vibepop.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.vibepop.data.repository.BatteryHistory
import com.vibepop.databinding.ActivityMainBinding
import com.vibepop.databinding.LayoutTabThemesBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vibepop.service.HeadsetMonitorService
import com.vibepop.update.UpdateChecker
import com.vibepop.util.MediaThumbnailLoader
import com.vibepop.util.PermissionHelper
import app.budpop.core.BudState
import app.budpop.core.Kind
import app.budpop.core.Style
import app.budpop.popup.BareCard
import app.budpop.popup.FlyCard
import app.budpop.popup.PopupActions
import app.budpop.popup.PopupView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentThemeTargetAddress: String? = null
    private var detailSheet: BottomSheetDialog? = null
    private lateinit var detail: LayoutTabThemesBinding
    private var hasPromptedBluetoothDialog = false
    private var bluetoothReceiverRegistered = false
    private var bondedDeviceItems = listOf<BondedDeviceItem>()
    private var customThumbKey: String? = null
    private var animationKind = Kind.EARBUDS
    private var animationStyle = Style.LOVELY
    private var syncingAnimationStyle = false
    private var syncingServiceSwitch = false

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

    private val shareFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val send = Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_file)))
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
    private fun com.google.android.material.switchmaterial.SwitchMaterial.setOnChangeListenerForService() {
        setOnCheckedChangeListener { _, isChecked ->
            if (!syncingServiceSwitch) {
                viewModel.setServiceEnabled(this@MainActivity, isChecked)
            }
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        switchTab(R.id.nav_devices)
        renderBondedDevices(bondedDeviceItems)
    }

    private fun switchTab(itemId: Int) {
        binding.tabDevicesLayout.root.visibility = if (itemId == R.id.nav_devices) View.VISIBLE else View.GONE
        binding.tabSettingsLayout.root.visibility = if (itemId == R.id.nav_settings) View.VISIBLE else View.GONE
    }

    /** 打开某个目标的配置抽屉；address 为 null 表示全局默认 */
    private fun openDetail(address: String?) {
        ensureDetailSheet()
        currentThemeTargetAddress = address
        detail.cardDeviceData.visibility = if (address == null) View.GONE else View.VISIBLE
        detail.deviceHistoryContent.visibility = if (address == null) View.GONE else View.VISIBLE
        detail.ivDeviceHistoryChevron.visibility = View.GONE

        detail.tvDetailTitle.text = if (address == null) {
            getString(R.string.theme_target_global_plain)
        } else {
            bondedDeviceItems.firstOrNull { it.address == address }?.name
                ?: getString(R.string.rename_target_fallback)
        }
        detail.cardGlobalPopupOptions.visibility =
            if (address == null) View.VISIBLE else View.GONE

        loadThemeTargetUI(address)
        val theme = currentTargetConfig()?.animationTheme ?: "classic_airpods"
        syncAnimationBrowser(theme)
        detail.detailTabs.getTabAt(if (com.vibepop.overlay.BudPanel.matches(theme)) 1 else 0)?.select()
        if (address != null) loadBatteryHistory(address, detail.tvDeviceHistoryContent, detail.deviceHistoryChartHost, detail.btnShareDeviceHistory)
        detailSheet?.show()
    }

    private fun closeDetail() {
        detailSheet?.dismiss()
    }

    private fun ensureDetailSheet() {
        if (detailSheet != null) return
        detail = LayoutTabThemesBinding.inflate(layoutInflater)
        detailSheet = BottomSheetDialog(this).apply {
            setContentView(detail.root)
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                setBackgroundResource(R.drawable.bg_bottom_sheet)
                clipToOutline = true
            }
            behavior.isFitToContents = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
        bindDetailSheet()
    }


    /**
     * 1. 🏠 控制台面板交互绑定
     */
    private fun setupDashboardTab() {
        // Service de surveillance (onglet Réglages)
        binding.tabSettingsLayout.switchService.setOnChangeListenerForService()

        // Accès rapide au profil par défaut depuis Appareils et Réglages.
        binding.tabDevicesLayout.rowGlobalSettings.setOnClickListener { openDetail(null) }
        binding.tabSettingsLayout.rowGlobalSettings.setOnClickListener { openDetail(null) }

        // Bandeau permissions
        binding.tabDevicesLayout.cardPermissionWarning.setOnClickListener {
            if (!PermissionHelper.isBluetoothEnabled(this)) {
                PermissionHelper.promptEnableBluetooth(this)
            } else {
                binding.bottomNav.selectedItemId = R.id.nav_settings
            }
        }

    }

    /**
     * 2. 🎨 配置抽屉交互绑定（首次打开时调用一次）
     */
    private fun bindDetailSheet() {
        val themes = detail

        // Retour depuis le drawer
        themes.btnBackToDevices.setOnClickListener { closeDetail() }

        themes.detailTabs.addTab(themes.detailTabs.newTab().setText(R.string.animation_tab_visual))
        themes.detailTabs.addTab(themes.detailTabs.newTab().setText(R.string.animation_tab_motion))
        themes.detailTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                val showVisuals = tab.position == 0
                themes.visualContent.visibility = if (showVisuals) View.VISIBLE else View.GONE
                themes.animationContent.visibility = if (showVisuals) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        // L’aperçu animé est intégré au panneau ; le bouton de test garde le popup réel.
        themes.btnTestPopup.setOnClickListener { previewCurrentTarget() }
        themes.btnSaveTargetName.setOnClickListener { saveTargetName() }
        themes.groupAnimationStyles.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!syncingAnimationStyle && isChecked) {
                animationStyle = when (checkedId) {
                    R.id.btnStyleBare -> Style.BARE
                    R.id.btnStyleFanciest -> Style.FANCIEST
                    else -> Style.LOVELY
                }
                updateAnimationStyleDescription()
                saveAnimationSelection()
            }
        }

        // Sélection d'un média : la carte entière et son action ouvrent le sélecteur.
        themes.cardCurrentMedia.setOnClickListener { requestMediaAndPick() }
        themes.btnChooseCurrentMedia.setOnClickListener { requestMediaAndPick() }

        // Données et partage propres à l'appareil
        themes.rowDeviceShareFile.setOnClickListener { shareFilePicker.launch("*/*") }
        themes.rowDeviceLanShare.setOnClickListener {
            startActivity(Intent(this, com.vibepop.share.ShareActivity::class.java))
        }

        // Sortie haut-parleur (option globale)
        themes.switchForceSpeakerphone.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                viewModel.updateForceSpeakerphone(isChecked)
            }
        }

        // Durée d'affichage (option globale)
        themes.sliderDismissDelay.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val seconds = value.toInt()
                themes.tvDismissDelayLabel.text = getString(R.string.setting_dismiss_delay, seconds)
                viewModel.updateDismissSeconds(seconds)
            }
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

        // Visuels livrés avec l'application
        buildIncludedVisualLibrary()
        buildAnimationBrowser()
    }

    /** Visuel intégré depuis res/raw, appliqué comme média personnalisé à la cible. */
    private data class IncludedVisual(
        val labelRes: Int,
        val rawRes: Int,
        val fileName: String
    )

    private val includedVisuals = listOf(
        IncludedVisual(R.string.demo_airpods_white, R.raw.demo_airpods_white, "airpods_white"),
        IncludedVisual(R.string.demo_open_ear, R.raw.demo_open_ear_black, "open_ear_black"),
        IncludedVisual(R.string.demo_shokz, R.raw.demo_shokz, "shokz"),
        IncludedVisual(R.string.demo_peugeot, R.raw.demo_peugeot, "peugeot")
    )

    private fun buildIncludedVisualLibrary() {
        val container = detail.layoutMediaLibrary
        container.removeAllViews()

        val density = resources.displayMetrics.density
        val size = (88 * density).toInt()
        val gap = (12 * density).toInt()

        for (visual in includedVisuals) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
                isClickable = true
                isFocusable = true
                contentDescription = getString(visual.labelRes)
                setOnClickListener { applyIncludedVisual(visual) }
            }

            val selected = currentTargetConfig()?.let { config ->
                config.animationTheme == "custom_media" &&
                    java.io.File(config.customMediaPath ?: "").name.let { it == "demo_${visual.fileName}.gif" || it.startsWith("demo_${visual.fileName}_") }
            } == true
            val preview = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_media_thumb)
                clipToOutline = true
                setImageBitmap(decodeIncludedThumbnail(visual.rawRes, size))
            }
            val previewCard = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                radius = 12 * density
                strokeWidth = ((if (selected) 3 else 1) * density).toInt()
                strokeColor = ContextCompat.getColor(this@MainActivity, if (selected) R.color.primary else R.color.card_stroke)
                cardElevation = 0f
                addView(preview)
            }

            val label = TextView(this).apply {
                text = getString(visual.labelRes)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    size,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            item.addView(previewCard)
            item.addView(label)
            container.addView(item)
        }
    }

    private fun buildAnimationBrowser() {
        updateAnimationStyleDescription()
        renderAnimationKindChoices()
        renderBudPopPreview()
    }

    private fun syncAnimationBrowser(theme: String) {
        val parts = theme.split(':')
        animationStyle = Style.entries.firstOrNull { it.key == parts.getOrNull(1) } ?: Style.LOVELY
        animationKind = Kind.of(parts.getOrNull(2)) ?: Kind.EARBUDS
        syncingAnimationStyle = true
        detail.groupAnimationStyles.check(
            when (animationStyle) {
                Style.BARE -> R.id.btnStyleBare
                Style.FANCIEST -> R.id.btnStyleFanciest
                else -> R.id.btnStyleLovely
            }
        )
        syncingAnimationStyle = false
        updateAnimationStyleDescription()
        renderAnimationKindChoices()
        renderBudPopPreview()
    }

    private fun updateAnimationStyleDescription() {
        val description = when (animationStyle) {
            Style.BARE -> R.string.animation_style_bare_desc
            Style.LOVELY -> R.string.animation_style_lovely_desc
            Style.FANCIEST -> R.string.animation_style_fanciest_desc
        }
        detail.tvAnimationStyleDescription.setText(description)
    }

    private fun renderAnimationKindChoices() {
        val container = detail.layoutAnimationKinds
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val primary = ContextCompat.getColor(this, R.color.primary)
        val neutralStroke = ContextCompat.getColor(this, R.color.card_stroke)
        val cardBackground = ContextCompat.getColor(this, R.color.bg_card_dark)
        val selectedBackground = ContextCompat.getColor(this, R.color.card_selected_bg)
        val textColor = ContextCompat.getColor(this, R.color.text_primary)

        Kind.entries.forEach { kind ->
            val selected = kind == animationKind
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (108 * density).toInt(),
                    (92 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                radius = 14 * density
                strokeWidth = ((if (selected) 2f else 1f) * density).toInt()
                strokeColor = if (selected) primary else neutralStroke
                setCardBackgroundColor(if (selected) selectedBackground else cardBackground)
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                contentDescription = getString(kindNameResource(kind))
                setOnClickListener {
                    animationKind = kind
                    saveAnimationSelection()
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (30 * density).toInt(),
                    (30 * density).toInt()
                )
                setImageResource(kind.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (selected) primary else textColor
                )
                contentDescription = null
            }
            val label = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
                setText(kindNameResource(kind))
                setTextColor(textColor)
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                maxLines = 2
            }
            content.addView(icon)
            content.addView(label)
            card.addView(content)
            container.addView(card)
        }
    }

    private fun renderBudPopPreview() {
        val host = detail.animationPreviewHost
        host.removeAllViews()
        val actions = object : PopupActions {
            override fun close() = Unit
            override fun touched() = Unit
            override fun fullScreen(on: Boolean) = Unit
        }
        val popup: PopupView = when (animationStyle) {
            Style.BARE -> BareCard(this, actions, animationKind)
            Style.FANCIEST -> FlyCard(this, actions, mini = false, kind = animationKind)
            else -> FlyCard(this, actions, mini = true, kind = animationKind)
        }
        val targetName = currentTargetConfig()?.customDeviceName?.takeIf { it.isNotBlank() }
            ?: currentThemeTargetAddress?.let { address ->
                bondedDeviceItems.firstOrNull { it.address == address }?.name
            }
            ?: getString(R.string.device_name_fallback)
        popup.bind(BudState(name = targetName, battery = 82, connected = true, kind = animationKind))
        host.addView(
            popup.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        popup.root.post {
            if (popup.root.isAttachedToWindow) popup.enter()
        }
        detail.tvAnimationPreviewCaption.text = getString(
            R.string.animation_preview_caption,
            getString(kindNameResource(animationKind)),
            getString(kindDescriptionResource(animationKind))
        )
    }

    private fun saveAnimationSelection() {
        val theme = "budpop:${animationStyle.key}:${animationKind.key}"
        val address = currentThemeTargetAddress
        if (address == null) {
            viewModel.updateAnimationTheme(theme)
        } else {
            val config = viewModel.getDevicePopupConfig(address)
            viewModel.saveDevicePopupConfig(
                this,
                address,
                config.copy(
                    animationTheme = theme,
                    customMediaPath = null,
                    customMediaType = "preset"
                )
            )
        }
        loadThemeTargetUI(address)
        renderAnimationKindChoices()
        renderBudPopPreview()
    }

    private fun kindNameResource(kind: Kind): Int = when (kind) {
        Kind.EARBUDS -> R.string.bud_kind_earbuds
        Kind.HEADPHONES -> R.string.bud_kind_headphones
        Kind.SPEAKER -> R.string.bud_kind_speaker
        Kind.SOUNDBAR -> R.string.bud_kind_soundbar
        Kind.KEYBOARD -> R.string.bud_kind_keyboard
        Kind.MOUSE -> R.string.bud_kind_mouse
        Kind.CAR -> R.string.bud_kind_car
        Kind.GAMEPAD -> R.string.bud_kind_gamepad
        Kind.OTHER -> R.string.bud_kind_other
    }

    private fun kindDescriptionResource(kind: Kind): Int = when (kind) {
        Kind.EARBUDS -> R.string.bud_kind_desc_earbuds
        Kind.HEADPHONES -> R.string.bud_kind_desc_headphones
        Kind.SPEAKER -> R.string.bud_kind_desc_speaker
        Kind.SOUNDBAR -> R.string.bud_kind_desc_soundbar
        Kind.KEYBOARD -> R.string.bud_kind_desc_keyboard
        Kind.MOUSE -> R.string.bud_kind_desc_mouse
        Kind.CAR -> R.string.bud_kind_desc_car
        Kind.GAMEPAD -> R.string.bud_kind_desc_gamepad
        Kind.OTHER -> R.string.bud_kind_desc_other
    }

    private fun decodeIncludedThumbnail(rawRes: Int, targetPx: Int): android.graphics.Bitmap? {
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

    private fun applyIncludedVisual(visual: IncludedVisual) {
        val address = currentThemeTargetAddress
        val applied = viewModel.applyBundledDemo(
            applicationContext,
            address,
            visual.rawRes,
            visual.fileName,
            "image"
        )
        if (!applied) {
            Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            return
        }
        loadThemeTargetUI(address)
        previewCurrentTarget()
    }

    private fun saveTargetName() {
        val address = currentThemeTargetAddress
        val newName = detail.etTargetName.text?.toString()?.trim().orEmpty()
        if (address == null) {
            viewModel.updateDeviceName(newName)
        } else {
            val config = viewModel.getDevicePopupConfig(address)
            viewModel.saveDevicePopupConfig(this, address, config.copy(customDeviceName = newName))
        }
        detail.etTargetName.clearFocus()
        Toast.makeText(
            this,
            if (newName.isNotBlank()) getString(R.string.rename_updated_toast, newName)
            else getString(R.string.rename_cleared_toast),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadThemeTargetUI(targetAddress: String?) {
        val config = if (targetAddress == null) {
            viewModel.popupConfig.value ?: return
        } else {
            viewModel.getDevicePopupConfig(targetAddress)
        }

        val themes = detail
        val originalName = targetAddress?.let { address ->
            bondedDeviceItems.firstOrNull { it.address == address }?.name
        } ?: getString(R.string.rename_global_default)
        themes.tvCurrentTargetDeviceName.text = originalName
        themes.etTargetName.setText(config.customDeviceName)

        // Appareil personnalisable mais popup désactivé : on le dit clairement
        val targetEnabled = targetAddress?.let { address ->
            bondedDeviceItems.firstOrNull { it.address == address }?.isSelected
        } ?: true
        themes.tvTargetDisabledHint.visibility = if (targetEnabled) View.GONE else View.VISIBLE

        updateCurrentMediaUI(config.animationTheme, config.customMediaType, config.customMediaPath)
        updateCustomMediaThumbnail(config.customMediaPath, config.customMediaType)
        buildIncludedVisualLibrary()
        val file = config.customMediaPath?.let { java.io.File(it).name }
        val included = file != null && includedVisuals.any { file == "demo_${it.fileName}.gif" || file.startsWith("demo_${it.fileName}_") }
        themes.tvSelectedVisual.visibility = if (config.animationTheme == "custom_media" && !file.isNullOrBlank() && !included) View.VISIBLE else View.GONE
        if (themes.tvSelectedVisual.visibility == View.VISIBLE) {
            themes.tvSelectedVisual.text = getString(R.string.selected_visual_file, file)
        }

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
        settings.rowCredits.setOnClickListener {
            val expand = settings.settingsCreditsContent.visibility != View.VISIBLE
            settings.settingsCreditsContent.visibility = if (expand) View.VISIBLE else View.GONE
            if (expand && settings.tvCreditsLicenses.text.isNullOrBlank()) {
                settings.tvCreditsBody.text = getString(R.string.credits_body)
                settings.tvCreditsLicenses.text = listOf(
                    R.raw.budpop_license,
                    R.raw.vibepop_license,
                    R.raw.btmanage_license
                ).joinToString("\n\n") { raw ->
                    resources.openRawResource(raw).bufferedReader().use { it.readText() }
                }
            }
        }

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
        settings.tvUpdateStatus.text = getString(R.string.update_ready_to_check)
        settings.btnCheckUpdate.isEnabled = true
        settings.btnCheckUpdate.setOnClickListener { checkForUpdates() }
        settings.btnSupportKoFi.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ko-fi.com/pouark")))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.support_kofi_open_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleDeviceHistory(address: String?) {
        val expand = detail.deviceHistoryContent.visibility != View.VISIBLE
        detail.deviceHistoryContent.visibility = if (expand) View.VISIBLE else View.GONE
        detail.ivDeviceHistoryChevron.rotation = if (expand) 90f else 0f
        if (expand) {
            loadBatteryHistory(
                address = address,
                summary = detail.tvDeviceHistoryContent,
                chartHost = detail.deviceHistoryChartHost,
                shareButton = detail.btnShareDeviceHistory
            )
        }
    }

    private fun loadBatteryHistory(
        address: String?,
        summary: TextView,
        chartHost: FrameLayout,
        shareButton: com.google.android.material.button.MaterialButton
    ) = lifecycleScope.launch {
        val since = System.currentTimeMillis() - 86_400_000L
        val (rows, points) = withContext(Dispatchers.IO) { BatteryHistory(this@MainActivity).use {
            it.recent(address) to (address?.let { a -> it.day(a, since) } ?: emptyList())
        } }
        if (address != null && currentThemeTargetAddress != address) return@launch
        val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val shareText = if (rows.isEmpty()) getString(R.string.history_empty) else rows.joinToString("\n") {
            "${date.format(java.util.Date(it.timestamp))} · ${it.name} · ${it.level}%"
        }
        val visibleRows = rows.take(12)
        val visibleText = if (rows.isEmpty()) {
            getString(R.string.history_empty)
        } else {
            buildString {
                if (rows.size > visibleRows.size) {
                    append(getString(R.string.history_shown_count, visibleRows.size, rows.size))
                    append("\n\n")
                }
                append(visibleRows.joinToString("\n") {
                    "${date.format(java.util.Date(it.timestamp))} · ${it.name} · ${it.level}%"
                })
            }
        }
        summary.text = visibleText
        chartHost.removeAllViews()
        chartHost.visibility = if (address != null && points.isNotEmpty()) View.VISIBLE else View.GONE
        if (address != null && points.isNotEmpty()) {
            chartHost.addView(
                BatteryPlot(this@MainActivity, since, points),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        shareButton.isEnabled = rows.isNotEmpty()
        shareButton.setOnClickListener {
            if (rows.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, shareText)
                startActivity(Intent.createChooser(send, getString(R.string.history_share)))
            }
        }
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
                syncingServiceSwitch = true
                dash.switchService.isChecked = running
                syncingServiceSwitch = false
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
            val summary = getString(R.string.devices_global_summary, themeLabel(config.animationTheme))
            binding.tabDevicesLayout.tvGlobalThemeSummary.text = summary
            binding.tabSettingsLayout.tvGlobalThemeSummary.text = summary
            applyTargetThumb(
                binding.tabDevicesLayout.ivGlobalThemeThumb,
                config.animationTheme,
                config.customMediaType,
                config.customMediaPath
            )
            applyTargetThumb(
                binding.tabSettingsLayout.ivGlobalThemeThumb,
                config.animationTheme,
                config.customMediaType,
                config.customMediaPath
            )

            // le drawer n'existe qu'après sa première ouverture
            if (this::detail.isInitialized) {
                val detail = detail
                if (detail.switchForceSpeakerphone.isChecked != config.isForceSpeakerphone) {
                    detail.switchForceSpeakerphone.isChecked = config.isForceSpeakerphone
                }
                detail.sliderDismissDelay.value = config.autoDismissSeconds.toFloat()
                detail.tvDismissDelayLabel.text = getString(R.string.setting_dismiss_delay, config.autoDismissSeconds)
                if (currentThemeTargetAddress == null) {
                    loadThemeTargetUI(null)
                }
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

    private fun updateCurrentMediaUI(theme: String, mediaType: String, mediaPath: String?) {
        val themes = detail

        val hasCustomMedia = theme == "custom_media" && !mediaPath.isNullOrBlank()
        val typeDesc = when (mediaType) {
            "video" -> getString(R.string.media_type_video_mp4)
            "image" -> getString(R.string.media_type_image_gif)
            "lottie" -> getString(R.string.media_type_lottie)
            else -> getString(R.string.media_type_other)
        }
        val fileName = mediaPath?.let { java.io.File(it).name }.orEmpty()

        themes.tvCurrentMediaDetail.text = when {
            hasCustomMedia && fileName.isNotBlank() ->
                getString(R.string.media_active_hint, typeDesc, fileName)
            hasCustomMedia ->
                getString(R.string.media_active_hint_no_file, typeDesc)
            theme == "great_victory" ->
                getString(R.string.media_legacy_preset, getString(R.string.dash_theme_great_victory))
            theme == "bud_flight" -> getString(R.string.bud_flight)
            com.vibepop.overlay.BudPanel.matches(theme) -> com.vibepop.overlay.BudPanel.label(theme)
            else -> getString(R.string.media_default_active)
        }

        val pathIsVideo = mediaPath?.let {
            it.endsWith(".mp4", ignoreCase = true) || it.endsWith(".webm", ignoreCase = true)
        } == true
        val isVideoTheme = theme == "great_victory" ||
            (theme == "custom_media" && (mediaType == "video" || pathIsVideo))
        themes.cardVideoOptions.visibility = if (isVideoTheme) View.VISIBLE else View.GONE
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

            // 1. 左侧：该耳机已绑定的视觉（无则回退到耳机图标）
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_media_thumb)
                clipToOutline = true
                contentDescription = null
            }
            applyTargetThumb(thumb, device.animationTheme, device.customMediaType, device.customMediaPath)
            row.addView(thumb)

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

    /** Affiche la vignette du visuel lié à une cible (vidéo, GIF, image), ou une icône */
    private fun applyTargetThumb(iv: ImageView, theme: String, mediaType: String?, mediaPath: String?) {
        when {
            com.vibepop.overlay.BudPanel.matches(theme) -> showThumbIcon(iv,
                app.budpop.core.Kind.of(theme.substringAfterLast(':'))?.icon ?: R.drawable.ic_bluetooth)
            theme == "great_victory" -> loadRawVideoThumb(iv, R.raw.video_great_victory)
            theme == "custom_media" && !mediaPath.isNullOrBlank() && mediaType == "lottie" ->
                showThumbIcon(iv, R.drawable.ic_nav_palette)
            theme == "custom_media" && !mediaPath.isNullOrBlank() -> {
                showThumbIcon(iv, R.drawable.ic_bluetooth_headset)
                val isVideo = mediaType == "video"
                val targetPx = (48 * resources.displayMetrics.density).toInt()
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        MediaThumbnailLoader.load(mediaPath, isVideo, targetPx)
                    }
                    if (bitmap != null) {
                        iv.imageTintList = null
                        iv.setPadding(0, 0, 0, 0)
                        iv.setImageBitmap(bitmap)
                    }
                }
            }
            else -> showThumbIcon(iv, R.drawable.ic_bluetooth_headset)
        }
    }

    private fun showThumbIcon(iv: ImageView, res: Int) {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        iv.setPadding(pad, pad, pad, pad)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.accent)
        )
        iv.setImageResource(res)
    }

    private val rawThumbCache = HashMap<Int, Bitmap>()

    private fun loadRawVideoThumb(iv: ImageView, rawRes: Int) {
        rawThumbCache[rawRes]?.let { cached ->
            iv.imageTintList = null
            iv.setPadding(0, 0, 0, 0)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(cached)
            return
        }
        showThumbIcon(iv, R.drawable.ic_sparkles)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { extractRawFrame(rawRes) }
            if (bitmap != null) {
                rawThumbCache[rawRes] = bitmap
                iv.imageTintList = null
                iv.setPadding(0, 0, 0, 0)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageBitmap(bitmap)
            }
        }
    }

    private fun extractRawFrame(rawRes: Int): Bitmap? {
        return try {
            resources.openRawResourceFd(rawRes)?.use { fd ->
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
                val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                retriever.release()
                frame
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun themeLabel(theme: String): String = when (theme) {
        "bud_flight" -> getString(R.string.bud_flight)
        "great_victory" -> getString(R.string.dash_theme_great_victory)
        "custom_media" -> getString(R.string.dash_theme_custom)
        else -> if (com.vibepop.overlay.BudPanel.matches(theme)) com.vibepop.overlay.BudPanel.label(theme) else getString(R.string.dash_theme_classic)
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

    /** Charge la vignette du média actif, avec une icône de repli si nécessaire. */
    private fun updateCustomMediaThumbnail(path: String?, type: String) {
        val themes = detail
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
                themes.ivCurrentMediaThumb.setImageBitmap(bitmap)
                themes.ivCurrentMediaThumb.visibility = View.VISIBLE
                themes.ivCurrentMediaIcon.visibility = View.GONE
            } else {
                showCustomMediaIcon()
            }
        }
    }

    private fun showCustomMediaIcon() {
        val themes = detail
        themes.ivCurrentMediaThumb.setImageDrawable(null)
        themes.ivCurrentMediaThumb.visibility = View.GONE
        themes.ivCurrentMediaIcon.visibility = View.VISIBLE
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
                .setPositiveButton(getString(R.string.update_open_page)) { _, _ -> openUpdatePage(info) }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
            settings.tvUpdateStatus.text = getString(R.string.update_available_title)
        }
    }

    /**
     * Remet le téléchargement à Android/le navigateur, sans demander à VibePop
     * la permission sensible REQUEST_INSTALL_PACKAGES.
     */
    private fun openUpdatePage(info: UpdateChecker.UpdateInfo) {
        val settings = binding.tabSettingsLayout
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.apkUrl)))
            settings.tvUpdateStatus.text = getString(R.string.update_browser_opened)
        } catch (e: Exception) {
            settings.tvUpdateStatus.text = getString(R.string.update_open_failed)
            Toast.makeText(this, getString(R.string.update_open_failed), Toast.LENGTH_LONG).show()
        }
    }
}
