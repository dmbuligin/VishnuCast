package com.buligin.vishnucast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.view.View
import android.net.wifi.WifiManager
import android.net.Uri
import androidx.work.WorkManager







class MainActivity : AppCompatActivity() {

    //private val ACTION_DOWNLOAD_UPDATE = "download_update"
    //private val EXTRA_UPDATE_URL = "extra_update_url"
    //private val EXTRA_UPDATE_NAME = "extra_update_name"
    //private val EXTRA_UPDATE_VER = "extra_update_ver"


    companion object {
        private const val PREFS_NAME = "vishnucast_prefs"
        private const val KEY_APP_LANG = "app_lang" // "ru" | "en"
    }

    private lateinit var tvNetBadge: TextView
    private var netMon: NetworkMonitor? = null
    private lateinit var arrowHint: HintArrowView
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvClients: TextView
    private lateinit var ivQr: ImageView
    private lateinit var btnToggle: SeekBar
    private lateinit var levelBar: ProgressBar
    private lateinit var sliderContainer: FrameLayout

    // Foreground-стрелка
    private var fgArrow: Drawable? = null
    //private var fgAnim: ValueAnimator? = null

    private val isRunning = AtomicBoolean(false)
    private var lastUrl: String? = null
    private var userIsTracking = false

    // Аудио-индикация
    private lateinit var audioManager: AudioManager
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { updateInputBadge() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { updateInputBadge() }
    }

    private fun isUsbMicConnected(): Boolean {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.any { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
    }

    private fun updateInputBadge() {
        val usb = isUsbMicConnected()
        val iconRes = if (usb) R.drawable.ic_headset_mic_24 else R.drawable.ic_mic_24
        val icon = ContextCompat.getDrawable(this, iconRes)
        val size = dp(32)
        icon?.setBounds(0, 0, size, size)
        TextViewCompat.setCompoundDrawablesRelative(tvStatus, icon, null, null, null)
        tvStatus.compoundDrawablePadding = dp(8)
        TextViewCompat.setCompoundDrawableTintList(tvStatus, ColorStateList.valueOf(Color.parseColor("#6B7280")))
    }

    private val requestRecordAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
                updateUiRunning(false)
            }
        }

    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCastService() else {
                showNotificationsDeniedDialog()
                updateUiRunning(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvNetBadge = findViewById(R.id.tvNetBadge)

        val toolbar: androidx.appcompat.widget.Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvClients = findViewById(R.id.tvClients)
        ivQr = findViewById(R.id.ivQr)
        sliderContainer = findViewById(R.id.sliderContainer)
        sliderContainer.foreground = null
        sliderContainer.overlay.clear()
        btnToggle = findViewById(R.id.btnToggle)
        arrowHint = findViewById(R.id.arrowHint)
        levelBar = findViewById(R.id.signalLevelBar)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        updateInputBadge()

        btnToggle.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                arrowHint.stopHint()
                userIsTracking = true
                hideArrowHint()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userIsTracking = false
                val toStart = (btnToggle.progress >= 50)
                if (toStart) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val notifGranted = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!notifGranted) {
                            updateUiRunning(false)
                            requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return
                        }
                    }
                    val micGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!micGranted) {
                        updateUiRunning(false)
                        requestRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
                        return
                    }
                    startCastService()
                } else {
                    stopCastService()
                }
            }
        })

        // После измерения контейнера — синхронизируем состояние и покажем подсказку
        sliderContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                sliderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateUiRunning(CastService.isRunning)
            }
        })

        applyIpToUi(getLocalIpAddress())


        SignalLevel.live.observe(this) { level -> levelBar.progress = if (isRunning.get()) level.coerceIn(0, 100) else 0 }
        ClientCount.live.observe(this) { count -> updateClientsCount(count) }

        updateUiRunning(false)
        updateClientsCount(0)
        UpdateCheckWorker.ensureScheduled(this)
        intent?.let { handleUpdateIntent(it) }  // обработать, если пришли из уведомления

    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        updateUiRunning(CastService.isRunning)
        netMon = NetworkMonitor(this) { newIp ->
            runOnUiThread { applyIpToUi(newIp) }
        }.also { it.start() }

    }

    override fun onStop() {
        super.onStop()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        arrowHint.stopHint()
        hideArrowHint()
        netMon?.stop()
        netMon = null

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_language -> {
            showLanguagePicker()
            true
        }
        R.id.action_check_updates -> {
            checkForUpdates()
            true
        }
        R.id.action_about -> {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
    private fun startCastService() {
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(this, Intent(this, CastService::class.java))
        } else {
            startService(Intent(this, CastService::class.java))
        }
        updateUiRunning(true)
    }
    private fun stopCastService() {
        stopService(Intent(this, CastService::class.java))
        updateUiRunning(false)
    }
    private fun updateUiRunning(running: Boolean) {
        isRunning.set(running)



        if (!userIsTracking) btnToggle.progress = if (running) 100 else 0

        tvStatus.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        if (!running) levelBar.progress = 0

        val colorBg = if (running) Color.parseColor("#DC2626") else Color.parseColor("#2563EB")
        ViewCompat.setBackgroundTintList(btnToggle, ColorStateList.valueOf(colorBg))
        arrowHint.setDirectionLeft(running)
        arrowHint.startHint()

        updateInputBadge()
    }
    private fun hideArrowHint() {
        //fgAnim?.cancel()
        //fgAnim = null
        fgArrow?.alpha = 0
        sliderContainer.foreground = null
        fgArrow = null
    }
    // -----------------------------------------------
    private fun updateClientsCount(count: Int) {
        val isRu = AppCompatDelegate.getApplicationLocales()
            ?.toLanguageTags()
            ?.lowercase(Locale.ROOT)
            ?.startsWith("ru") == true
        tvClients.text = if (isRu) "Подключено клиентов: $count" else "Connected clients: $count"
    }
    private fun showLanguagePicker() {
        val items = arrayOf(getString(R.string.lang_ru), getString(R.string.lang_en))
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val checkedIndex = if (currentLangCode() == "ru") 0 else 1
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lang_title))
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val chosen = if (which == 0) "ru" else "en"
                prefs.edit().putString(KEY_APP_LANG, chosen).apply()
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(if (chosen == "ru") "ru" else "en")
                )
                dialog.dismiss(); recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun showNotificationsDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage("Для фоновой работы требуется разрешение на уведомления.\nОткрыть настройки приложения?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun getLocalIpAddress(): String? = NetUtils.getLocalIpv4(this)
    private fun generateQrAsync(text: String, onReady: (Bitmap) -> Unit) {
        thread {
            try {
                val size = 512
                val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                    put(EncodeHintType.MARGIN, 1)
                }
                val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                for (x in 0 until size) for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
                runOnUiThread { onReady(bmp) }
            } catch (_: Throwable) { }
        }
    }
    private fun currentLangCode(): String {
        AppCompatDelegate.getApplicationLocales()?.toLanguageTags()?.let { tags ->
            if (tags.isNotEmpty()) return if (tags.lowercase(Locale.ROOT).startsWith("ru")) "ru" else "en"
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.getString(KEY_APP_LANG, null)?.let { saved -> return if (saved == "ru") "ru" else "en" }
        val sys = resources.configuration.locales[0]
        return if (sys != null && sys.language.lowercase(Locale.ROOT).startsWith("ru")) "ru" else "en"
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun applyIpToUi(ip: String?) {
        val port = getServerPort()
        val url = if (ip != null) "http://$ip:$port" else getString(R.string.placeholder_url)
        lastUrl = if (ip != null) url else null

        tvHint.text = getString(R.string.hint_open_url, url)

        // Бейдж сети
        val kind = detectNetKind(ip)
        val color = when (kind) {
            NetKind.AP   -> 0xFFF59E0B.toInt()
            NetKind.WIFI -> 0xFF2563EB.toInt()
            NetKind.ETH  -> 0xFF6B7280.toInt()
            NetKind.OTHER-> 0xFFE5E7EB.toInt()
        }
        tvNetBadge.background?.mutate()?.setTint(color)

        tvNetBadge.text = when (kind) {
            NetKind.AP   -> getString(R.string.badge_ap)
            NetKind.WIFI -> getString(R.string.badge_wifi)
            NetKind.ETH  -> getString(R.string.badge_eth)
            NetKind.OTHER -> getString(R.string.badge_other)
        }
        tvNetBadge.visibility = if (ip != null) View.VISIBLE else View.GONE

        // QR
        if (ip != null) {
            generateQrAsync(url) { bmp -> ivQr.setImageBitmap(bmp) }
        } else {
            ivQr.setImageDrawable(null)
        }
    }
    private enum class NetKind { AP, WIFI, ETH, OTHER }
    private fun detectNetKind(ip: String?): NetKind {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)

        // 1) По активной сети (если есть)
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return NetKind.ETH
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     return NetKind.WIFI
        }

        // 2) Fallback: ищем интерфейс, которому принадлежит наш IP, и решаем по имени + состоянию Wi-Fi
        if (ip != null) {
            try {
                val target = java.net.InetAddress.getByName(ip)
                val ifs = java.net.NetworkInterface.getNetworkInterfaces()
                while (ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a.hostAddress == target.hostAddress) {
                            val n = ni.name.lowercase()
                            // Если интерфейс ap* — это явно хотспот
                            if (n.startsWith("ap")) return NetKind.AP
                            // Если интерфейс wlan*, но Wi-Fi (STA) выключен — значит Soft AP
                            if (n.startsWith("wlan")) {
                                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                return if (!wm.isWifiEnabled) NetKind.AP else NetKind.WIFI
                            }
                            if (n.startsWith("eth")) return NetKind.ETH
                            return NetKind.OTHER
                        }
                    }
                }
            } catch (_: Throwable) { /* no-op */ }
        }

        // 3) Нет активной сети, но локальный приватный IP — вероятнее всего локальный хотспот
        if (ip != null && isPrivateIpv4(ip) && active == null) return NetKind.AP

        return NetKind.OTHER
    }
    private fun isPrivateIpv4(ip: String): Boolean =
        ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            (ip.startsWith("172.") && ip.substringAfter("172.").substringBefore(".").toIntOrNull() in 16..31)

    private fun getServerPort(): Int {
        val sp = getSharedPreferences("vishnucast", Context.MODE_PRIVATE)
        return sp.getInt("server_port", 8080)
    }
    private fun checkForUpdates() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        UpdateChecker.checkLatest { result ->
            result.onFailure {
                Toast.makeText(this, R.string.update_error, Toast.LENGTH_LONG).show()
            }.onSuccess { info ->
                if (info == null) {
                    Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show()
                } else {
                    showUpdateDialog(info)
                }
            }
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.ReleaseInfo) {
        val body = info.body.trim().take(1200) // короткий changelog
        val msg = getString(R.string.update_found_msg, info.versionName, body)

        val b = AlertDialog.Builder(this)
            .setTitle(R.string.update_found_title)
            .setMessage(msg)
            .setNegativeButton(R.string.update_btn_open_release) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
            }
            .setNeutralButton(android.R.string.cancel, null)

        if (info.downloadUrl != null && info.assetName != null) {
            b.setPositiveButton(R.string.update_btn_download) { _, _ ->
                val fn = info.assetName.ifBlank { "VishnuCast-${info.versionName}.apk" }
                ApkDownloader.downloadAndInstall(this, info.downloadUrl, fn)
            }
        }

        b.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }


    private fun handleUpdateIntent(i: Intent) {
        if (i.action == UpdateProtocol.ACTION_DOWNLOAD_UPDATE) {
            val url  = i.getStringExtra(UpdateProtocol.EXTRA_UPDATE_URL)
            val name = i.getStringExtra(UpdateProtocol.EXTRA_UPDATE_NAME) ?: "VishnuCast-update.apk"
            if (!url.isNullOrBlank()) {
                ApkDownloader.downloadAndInstall(this, url, name)
            }
            // чтобы не повторялось при ротации/ре-запуске
            i.action = null
        }
    }


}
