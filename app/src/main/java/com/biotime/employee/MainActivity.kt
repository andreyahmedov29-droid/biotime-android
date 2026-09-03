package com.biotime.employee

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.biotime.employee.tracking.LocationTrackingService

/**
 * WebView-обёртка вокруг BIOTIME: открывает приложение во весь экран и
 * обеспечивает фоновый трекинг геолокации водителя.
 *
 * Адрес приложения — тот же, что открывает iOS-обёртка. Замените APP_URL,
 * если домен приложения изменится.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        configureWebView()
        loadApp()

        requestPermissionsIfNeeded()
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
        }

        // Мост, через который веб-код передаёт активный routeId нативному трекеру.
        // Вызов из JS (добавьте в app.js): AndroidBridge.setRouteId("route-id")
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
    }

    private fun loadApp() {
        webView.loadUrl(APP_URL)
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

    // JS-мост: веб-код зовёт AndroidBridge.setRouteId(...), когда водитель
    // в активном маршруте. Трекер подставит routeId в отправку координат.
    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun setRouteId(routeId: String) {
            LocationTrackingService.setActiveRouteId(this@MainActivity, routeId)
        }
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
