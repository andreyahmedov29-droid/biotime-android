package com.biotime.employee

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.biotime.employee.tracking.LocationTrackingService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
// В zxing-android-embedded интеграционные классы сканера лежат в пакете
// com.google.zxing.integration.android (не в com.journeyapps.barcodescanner).
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import org.json.JSONObject

/**
 * WebView-обёртка вокруг BIOTIME: открывает приложение во весь экран и
 * обеспечивает фоновый трекинг геолокации водителя.
 *
 * Адрес приложения — тот же, что открывает iOS-обёртка. Замените APP_URL,
 * если домен приложения изменится.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Отложенный вызов из веба «Отсканируй QR»: имя JS-колбэка и действие
    // (load — погрузка складом, unload — выгрузка водителем). Результат сканера
    // возвращается в веб через window[callbackName]({...}).
    @Volatile private var pendingQrCallback: String? = null
    @Volatile private var pendingQrAction: String? = null

    // Запрос обычного доступа к местоположению + уведомлениям.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                // Базовые разрешения получены — стартуем фоновый трекер.
                LocationTrackingService.start(this)
                maybeAskBackgroundLocation()
                maybeAskBatteryOptimization()
            }
        }

    // Запрос доступа к камере строго для сканера QR (по нажатию «Сканировать»).
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startQrScan() else notifyQrResult(false, null, "Нет доступа к камере")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        configureWebView()
        loadApp()

        requestPermissionsIfNeeded()
        checkForUpdate()
    }

    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.setBackgroundColor(0xFF08090D.toInt())

        // Геолокация из WebView нужна, только пока приложение открыто;
        // в фоне работает нативный сервис.
        webView.settings.setGeolocationEnabled(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // Оставляем навигацию внутри WebView (не открываем системный браузер).
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
            // При загрузке основного документа шлюз платформы может вернуть
            // 401/403 (BH_LOGIN_REQUIRED) — типично при включённом VPN, когда
            // сессия не проходит через туннель. Показываем понятное сообщение
            // вместо «сырого» JSON-ответа на чёрном экране.
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse?.statusCode ?: 0
                if (request?.isForMainFrame == true && (code == 401 || code == 403)) {
                    view?.post { showLoginRequiredDialog() }
                }
            }
        }

        // Мост, через который веб-код передаёт активный routeId нативному трекеру.
        // Вызов из JS (добавьте в app.js): AndroidBridge.setRouteId("route-id")
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
    }

    private fun loadApp() {
        webView.loadUrl(APP_URL)
    }

    // Понятное сообщение вместо «чёрного экрана с JSON», когда шлюз платформы
    // не пускает приложение (BH_LOGIN_REQUIRED) — чаще всего из-за включённого VPN,
    // который меняет маршрут и «сбрасывает» авторизацию.
    private fun showLoginRequiredDialog() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Требуется вход в приложение")
            .setMessage(
                "Не удалось подтвердить вход на платформе. Частая причина — включённый VPN: " +
                "он меняет сетевой маршрут и блокирует авторизацию приложения.\n\n" +
                "Отключите VPN и нажмите «Повторить»."
            )
            .setCancelable(false)
            .setPositiveButton("Повторить") { _: DialogInterface?, _: Int ->
                webView.loadUrl(APP_URL)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isEmpty()) {
            // Разрешения уже есть — сразу стартуем трекер.
            LocationTrackingService.start(this)
            maybeAskBackgroundLocation()
            maybeAskBatteryOptimization()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Фоновый доступ (ACCESS_BACKGROUND_LOCATION) запрашивается ОТДЕЛЬНО,
    // после получения обычного — Android не разрешает оба в одном диалоге.
    private fun maybeAskBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }
    }

    private fun maybeAskBatteryOptimization() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("DEPRECATION")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (_: Exception) {
            // На некоторых устройствах активности нет — пропускаем,
            // пользователь может исключить из энергосбережения вручную.
        }
    }

    // Проверка нового APK (self-update через GitHub Releases / сервер).
    // Запускается при старте: если /api/app/update-info отдаёт версию новее
    // установленной — показываем диалог «Обновить», который скачивает и ставит APK.
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = UpdateChecker.check(this@MainActivity)
            val current = UpdateChecker.currentVersionCode(this@MainActivity)
            if (info == null || !info.isNewerThan(current) || info.apkUrl.isBlank()) return@launch

            val message = buildString {
                append("Доступна версия ${info.versionName} (сейчас ${current}).")
                if (info.notes.isNotBlank()) append("\n\n${info.notes}")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Доступно обновление")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Обновить") { _: DialogInterface?, _: Int ->
                    performUpdate(info)
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    // Скачивает и запускает установку нового APK; при любой ошибке показывает
    // понятное сообщение вместо молчаливого «ничего не произошло».
    private fun performUpdate(info: UpdateInfo) {
        lifecycleScope.launch {
            val downloading = AlertDialog.Builder(this@MainActivity)
                .setTitle("Обновление")
                .setMessage("Скачиваем новую версию…")
                .setCancelable(false)
                .show()
            val res = withContext(Dispatchers.IO) {
                UpdateHelper.download(this@MainActivity, info.apkUrl, info.versionCode)
            }
            downloading.dismiss()
            if (res.file == null) {
                showUpdateNotice("Не удалось обновить приложение", res.error ?: "Ошибка скачивания")
                return@launch
            }
            val launched = UpdateHelper.promptInstall(this@MainActivity, res.file)
            if (!launched) {
                showUpdateNotice(
                    "Разрешите установку из неизвестных источников",
                    "Включите переключатель для этого приложения в открывшихся настройках " +
                    "и запустите обновление снова."
                )
            }
        }
    }

    private fun showUpdateNotice(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("ОК", null)
            .show()
    }

    // JS-мост: веб-код зовёт AndroidBridge.setRouteId(...), когда водитель
    // в активном маршруте. Трекер подставит routeId в отправку координат.
    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun setRouteId(routeId: String) {
            LocationTrackingService.setActiveRouteId(this@MainActivity, routeId)
        }

        // Установленная версия APK (versionCode), которую веб-интерфейс сравнивает
        // с /api/app/update-info. Веб идёт через сессию шлюза, поэтому он видит
        // актуальную версию сервера, а нативный код сообщает версию устройства.
        @android.webkit.JavascriptInterface
        fun getVersionCode(): Int = try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: Exception) {
            0
        }

        // Сканирование QR-этикетки отгрузки. Веб вызывает:
        //   AndroidBridge.scanQR("myCallback", "load" | "unload")
        // Натив запрашивает камеру, открывает встроенный ZXing-сканер и возвращает
        // результат в веб через window.myCallback({ok, code, action, message}).
        @android.webkit.JavascriptInterface
        fun scanQR(callbackName: String, action: String) {
            pendingQrCallback = callbackName
            pendingQrAction = action
            requestCameraAndScan()
        }
    }

    private fun startQrScan() {
        IntentIntegrator(this)
            .setOrientationLocked(true)
            .setPrompt("Наведите камеру на QR-код этикетки")
            .setBeepEnabled(true)
            .initiateScan()
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startQrScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Результат встроенного сканера (IntentIntegrator).
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) return
        if (!result.contents.isNullOrBlank()) {
            notifyQrResult(true, result.contents, null)
        } else {
            notifyQrResult(false, null, "Сканирование отменено")
        }
    }

    private fun callJs(js: String) {
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript(js, null)
        }
    }

    private fun notifyQrResult(ok: Boolean, code: String?, message: String?) {
        val cb = pendingQrCallback ?: return
        val action = pendingQrAction ?: ""
        pendingQrCallback = null
        pendingQrAction = null
        val payload = JSONObject()
            .put("ok", ok)
            .put("code", code ?: "")
            .put("cancelled", !ok)
            .put("action", action)
            .put("message", message ?: "")
        callJs("window.$cb && window.$cb(${payload.toString()})")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Адрес BIOTIME-приложения (за шлюзом платформы — сессия водителя
        // подхватывается автоматически).
        const val APP_URL = "https://app-0cd4491f8939.vibecode.bitrix24.tech/"
    }
}
