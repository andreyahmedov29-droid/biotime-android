package com.biotime.employee

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.biotime.employee.tracking.LocationTrackingService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import java.io.File
import org.json.JSONObject
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * WebView-обёртка вокруг BIOTIME: открывает приложение во весь экран и
 * обеспечивает фоновый трекинг геолокации водителя.
 *
 * Адрес приложения — тот же, что открывает iOS-обёртка. Замените APP_URL,
 * если домен приложения изменится.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Нативный озвучиватель (TextToSpeech) — запасной голос для ТСД/WebView,
    // где веб-речь (window.speechSynthesis) не имеет голосов. Веб вызывает
    // AndroidBridge.speak("Хорошо"/"Плохо"), когда его собственный TTS пуст,
    // чтобы голос работал на любом устройстве в APK. Бип остаётся ещё более
    // низким запасным каналом, если и нативный TTS не готов.
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // Сканер ТСД в режиме Broadcast (Атол Smart, Urovo и др.): Android-служба
    // сканера шлёт системное широковещание, где код лежит в extra "scannerdata".
    // Ловим нативно и передаём в веб через window.onBarcode (веб в режиме
    // "external" уже слушает его и обрабатывает код окна «Отгрузка»).
    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                if (intent.action == ATOL_SCAN_ACTION && ::webView.isInitialized) {
                    val code = intent.getStringExtra("scannerdata")
                    if (!code.isNullOrBlank()) {
                        val payload = JSONObject().put("code", code).toString()
                        callJs("window.onBarcode && window.onBarcode($payload)")
                    }
                }
            } catch (_: Exception) {
                // Скан — вспомогательное; сбой приёмника не должен ронять приложение.
            }
        }
    }

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
                // Базовые разрешения получены — стартуем фоновый трекер только
                // если рабочий день уже активен (начат и не завершён). Статус дня
                // приходит от веба через setWorkActive; вне рабочего дня трекер
                // не запускается.
                if (LocationTrackingService.isWorkActive(this)) {
                    LocationTrackingService.start(this)
                }
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
        // If the Activity was recreated (e.g. the system killed the background
        // process after "Home", or the configuration changed) - restore the WebView
        // from the saved state so the page is NOT reloaded and the platform gateway
        // does not ask for re-login. Full reload only when there is nothing to restore.
        // WebView.restoreState(Bundle) returns WebBackForwardList?, not Boolean:
        // "has something been restored" == (result is not null). Build a Boolean
        // by comparing with null, otherwise Kotlin reports a type mismatch.
        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored) {
            loadApp()
        }

        configureBackNavigation()
        requestPermissionsIfNeeded()
        initTts()
        // Обновлением управляет веб-интерфейс: он показывает диалог «Доступно
        // обновление» (ходит через сессию шлюза, потому видит актуальную версию),
        // а установку запускает через нативный мост AndroidBridge.updateApp(...).
        // Нативный checkForUpdate отключён, чтобы при старте не выскакивали два
        // одинаковых окна обновления подряд (нативное + веб-модалка).
        // checkForUpdate()
    }

    // Инициализация нативного TTS. Готовность приходит асинхронно (onInit);
    // веб проверяет её через AndroidBridge.hasTts() и, пока она false, веб
    // ограничивается бипом — чтобы ни один скан не остался без звука.
    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Ставим русский язык, если он доступен; иначе — системный.
                    val ru = Locale("ru", "RU")
                    val available = tts?.setLanguage(ru) ?: TextToSpeech.LANG_MISSING_DATA
                    if (available == TextToSpeech.LANG_MISSING_DATA ||
                        available == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                    tts?.setOnUtteranceProgressListener(null)
                    ttsReady = true
                }
            }
        } catch (_: Exception) {
            // Если TTS не инициализировался — веб останется на бипе.
            ttsReady = false
        }
    }

    // Handles the system "Back" button and the edge-swipe gesture
    // (Android 10+ "left/right"). Uses OnBackPressedCallback - it intercepts
    // "back" on ALL versions, including Android 13+ predictive back where the
    // legacy onBackPressed() is not guaranteed to be called.
    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        // There is history inside the WebView - navigate back.
                        webView.goBack()
                    } else {
                        // No history. Instead of closing the Activity (finish), move
                        // the app to the background: Activity and WebView stay alive,
                        // the page stays open on return and no re-login is needed.
                        moveTaskToBack(true)
                    }
                }
            }
        )
    }

    private fun configureWebView() {
        // Explicitly allow receiving and persisting the platform gateway session
        // cookies. They are stored on disk and survive WebView/Activity recreation,
        // so the login session is not lost when the app is minimized or recreated.
        CookieManager.getInstance().setAcceptCookie(true)
        // Принимаем и third-party сессии с SSO-доменов входа (auth2.bitrix24.net /
        // vibecode.bitrix24.tech → приложение на app-*.vibecode.bitrix24.tech).
        // Без этого куки сессии, поставленные шлюзом при входе, не доходят до
        // WebView при повторной навигации после сворачивания, и шлюз снова
        // показывает экран «Защищённый сервер / Войти через Bitrix24».
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        // Веб-камера (getUserMedia) открывается автоматически при сканировании и
        // переоткрывается между сканами — без этого флага WebView потребовал бы
        // касание по видео перед каждым запуском потока.
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.setBackgroundColor(0xFF08090D.toInt())
        // Аппаратное ускорение рендеринга WebView: переносит отрисовку страницы
        // (скролл, анимации, частые перерисовки JS) на GPU, а не грузит CPU.
        // Отключаем программный рендер, чтобы темная тема/скролл не лагали на
        // слабых устройствах. HARDWARE — значение по умолчанию в современных
        // версиях, но здесь выставляем явно для гарантии.
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        // Отключаем эффект «отскока» по краям — сокращает лишние прорисовки и
        // «прыжки» содержимого при достижении конца списка.
        webView.overScrollMode = android.view.View.OVER_SCROLL_NEVER

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

            // Разрешаем веб-странице доступ к камере (getUserMedia) — для встроенного
            // веб-сканера QR: получив видео с камеры, страница показывает его в малом
            // окне и декодирует QR, а поверх выводит клиента и живой счётчик.
            // Если WebView не поддержит getUserMedia, веб-код поймает ошибку и сам
            // откатится на нативный сканер AndroidBridge.scanQR.
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                if (request != null) {
                    val resources = request.resources ?: arrayOf()
                    if (resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        request.grant(resources)
                        return
                    }
                }
                super.onPermissionRequest(request)
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
            // Разрешения уже есть — стартуем трекер, только если день активен
            // (иначе веб сам запустит через setWorkActive при «Начать работу»).
            if (LocationTrackingService.isWorkActive(this)) {
                LocationTrackingService.start(this)
            }
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
    // понятное сообщение вместо молчаливого «ничего не произошло». Каждый шаг
    // пишется в лог-файл, чтобы можно было точно понять, где оборвалась цепочка.
    private fun performUpdate(info: UpdateInfo) {
        appendUpdateLog("Начало обновления: versionCode=${info.versionCode}, apkUrl=${info.apkUrl}")
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
                appendUpdateLog("Скачивание не удалось: ${res.error}")
                showUpdateNotice("Не удалось обновить приложение", res.error ?: "Ошибка скачивания")
                return@launch
            }
            appendUpdateLog("APK скачан: ${res.file.absolutePath} (${res.file.length()} байт)")
            when (UpdateHelper.promptInstall(this@MainActivity, res.file)) {
                UpdateHelper.InstallResult.INSTALLED -> {
                    appendUpdateLog("Системный установщик запущен")
                }
                UpdateHelper.InstallResult.MISSING_PERMISSION -> {
                    appendUpdateLog("Нет разрешения на установку из неизвестных источников")
                    showUpdateNotice(
                        "Нужно разрешение на установку",
                        "Приложение не может установить обновление само.\n\n" +
                        "Открылись настройки Android — включите там переключатель " +
                        "«Разрешить установку из этого источника» для BIOTIME, затем " +
                        "нажмите снова «Обновить».\n\n" +
                        "Если настройки не открылись: Настройки ▸ Приложения ▸ BIOTIME ▸ " +
                        "«Установка неизвестных приложений» ▸ Разрешить."
                    )
                }
                UpdateHelper.InstallResult.NO_HANDLER -> {
                    appendUpdateLog("Установщик APK на устройстве не найден")
                    showUpdateNotice(
                        "Установщик не найден",
                        "Системный установщик недоступен на этом устройстве.\n" +
                        "Скачанный APK лежит внутри приложения; установите вручную " +
                        "или сообщите администратору."
                    )
                }
                UpdateHelper.InstallResult.ERROR -> {
                    appendUpdateLog("Ошибка запуска установщика (файл/намерение)")
                    showUpdateNotice(
                        "Ошибка установки",
                        "Не удалось запустить установку. Проверьте свободную память " +
                        "телефона и наличие системного установщика, затем повторите."
                    )
                }
            }
        }
    }

    // Пишет строку в лог процесса обновления (filesDir/update/log.txt). Полезно,
    // когда водитель говорит «нажал Обновить — и ничего»: по этому файлу видно,
    // где оборвалась цепочка (скачивание vs установка vs разрешение).
    private fun appendUpdateLog(line: String) {
        try {
            val dir = File(filesDir, "update").apply { mkdirs() }
            val f = File(dir, "log.txt")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            f.appendText("[$ts] $line\n")
        } catch (_: Exception) {
            // лог — вспомогательный, его сбой не должен мешать обновлению
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

        // Статус рабочего дня водителя. Веб зовёт AndroidBridge.setWorkActive(true)
        // при «Начать работу» и setWorkActive(false) при «Завершить работу».
        // Трекер геолокации работает ТОЛЬКО пока день активен: при завершении дня
        // натив останавливает фоновую отправку координат.
        @android.webkit.JavascriptInterface
        fun setWorkActive(active: Boolean) {
            LocationTrackingService.setWorkActive(this@MainActivity, active)
        }

        // Роль «водитель» (состоит ли сотрудник в группе «Водители»). Веб зовёт
        // AndroidBridge.setDriver(...) после загрузки /api/state (me.isDriver).
        // Для не-водителей геолокация НЕ запрашивается — трекер не запускается.
        @android.webkit.JavascriptInterface
        fun setDriver(isDriver: Boolean) {
            LocationTrackingService.setDriver(this@MainActivity, isDriver)
        }

        // Нативный дубль офлайн-очереди действий водителя.
        // Веб хранит очередь в WebView localStorage (
        // biotime.offlineOps), но localStorage может быть очищен системой при
        // принудительном kill/перезагрузке телефона. Чтобы действия (прибыл,
        // сдача, перенос, сканы) пережили даже такую перезагрузку, веб
        // дублирует JSON очереди в нативный файл. При старте, если localStorage
        // пуст, веб восстанавливает очередь из нативного дубля.
        //
        // Сохраняет очередь (JSON-строка) атомарно в filesDir/offline_ops.json.
        // Вызывается из веба: AndroidBridge.offlineOpsSave(jsonString)
        @android.webkit.JavascriptInterface
        fun offlineOpsSave(json: String) {
            try {
                val dir = File(filesDir, "offline").apply { mkdirs() }
                val tmp = File(dir, "offline_ops.json.tmp")
                val dest = File(dir, "offline_ops.json")
                tmp.writeText(json)
                // Атомарный перенос: если процесс убьют на середине записи,
                // старый файл останется целым, а не обрежется на полпути.
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    // rename может не сработать на некоторых файловых системах —
                    // фолбэк на прямое перезаписывание.
                    dest.writeText(json)
                    tmp.delete()
                }
            } catch (_: Exception) {
                // Нативный дубль — вспомогательный канал; сбой не должен
                // ронять приложение и прерывать основную работу веба.
            }
        }

        // Читает дубль очереди из нативного файла. Вызывается из веба при старте,
        // если localStorage пуст. Возвращает JSON-строку или "" если файла нет.
        @android.webkit.JavascriptInterface
        fun offlineOpsLoad(): String {
            return try {
                val f = File(filesDir, "offline/offline_ops.json")
                if (f.exists()) f.readText() else ""
            } catch (_: Exception) {
                ""
            }
        }

        // Стирает дубль очереди (когда веб успешно отправил все действия).
        // Вызывается из веба: AndroidBridge.offlineOpsClear()
        @android.webkit.JavascriptInterface
        fun offlineOpsClear() {
            try {
                val f = File(filesDir, "offline/offline_ops.json")
                if (f.exists()) f.delete()
            } catch (_: Exception) {
                // вспомогательный канал
            }
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
        //   AndroidBridge.scanQR("myCallback", "load"|"unload", done, need, client)
        // Натив запрашивает камеру, открывает кастомный не-полноэкранный сканер
        // QrScanActivity (камера сверху, панель счётрчика снизу) и возвращает
        // результат в веб через window.myCallback({ok, code, action, message}).
        @android.webkit.JavascriptInterface
        fun scanQR(callbackName: String, action: String, done: Int, need: Int, client: String) {
            pendingQrCallback = callbackName
            pendingQrAction = action
            pendingQrDone = done
            pendingQrNeed = need
            pendingQrClient = client
            requestCameraAndScan()
        }

        // Запуск установки обновления из веб-интерфейса. Веб получает актуальную
        // версию с /api/app/update-info и зовёт сюда, чтобы установку выполнял
        // НАТИВНЫЙ код (системный установщик), а не WebView, который не умеет
        // ставить APK (window.open(apkUrl) в WebView «закрывает окно и молчит»).
        // Веб вызывает: AndroidBridge.updateApp(versionCode, versionName, apkUrl, notes)
        @android.webkit.JavascriptInterface
        fun updateApp(versionCode: Int, versionName: String, apkUrl: String, notes: String) {
            runOnUiThread {
                performUpdate(
                    UpdateInfo(
                        versionCode = versionCode,
                        versionName = versionName,
                        apkUrl = apkUrl,
                        notes = notes
                    )
                )
            }
        }

        // Признак «терминал сбора данных» (ТСД): веб зовёт AndroidBridge.isTCD()
        // после загрузки, чтобы включить компактный интерфейс под небольшой
        // экран и аппаратный сканер. Возвращает true только для известных
        // вендоров промышленных ТСД; обычный смартфон даёт false.
        @android.webkit.JavascriptInterface
        fun isTCD(): Boolean {
            val manufacturer = Build.MANUFACTURER.trim().lowercase()
            val model = Build.MODEL.trim().lowercase()
            val tcdKeywords = arrayOf(
                "zebra", "motorola", "honeywell", "dolphin", "urovo",
                "idata", "newland", "point mobile", "datalogic", "dblue",
                "optiicon", "chainway", "unicroun", "pax", "sunmi",
                "atol", "shtrikh"
            )
            return tcdKeywords.any { manufacturer.contains(it) || model.contains(it) }
        }

        // Готов ли нативный TTS к речи. Веб зовёт это ДО сброса на бип: если
        // веб-речи нет (getVoices() пуст на ТСД/WebView) и нативный TTS готов —
        // голос произносится нативно, а бип остаётся только когда и он не готов.
        @android.webkit.JavascriptInterface
        fun hasTts(): Boolean = ttsReady

        // Произнести фразу голосом нативного TTS. Веб зовёт только когда его
        // собственная речь недоступна и hasTts() вернул true. Натив говорит
        // «Хорошо»/«Плохо» и на ТСД без веб-голосов.
        @android.webkit.JavascriptInterface
        fun speak(text: String) {
            speakNative(text)
        }
    }

    // Нативное озвучивание фразы. Вызывается из JS-моста (speak), выполняет TTS
    // на UI-потоке (требование Android-движка). Безмолвный сбой натива не должен
    // ронять приложение.
    private fun speakNative(text: String) {
        if (!ttsReady) return
        runOnUiThread {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "biotime-scan")
            } catch (_: Exception) {
                // голос — вспомогательный канал; сбой не должен ломать сканирование
            }
        }
    }

    // Параметры текущего сеанса сканирования (для запуска QrScanActivity).
    @Volatile private var pendingQrDone: Int = 0
    @Volatile private var pendingQrNeed: Int = 0
    @Volatile private var pendingQrClient: String = ""

    private var scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == QrScanActivity.RESULT_CODE && data != null) {
            val code = data.getStringExtra(QrScanActivity.RESULT_OK_EXTRA)
            if (!code.isNullOrBlank()) {
                notifyQrResult(true, code, null)
            } else {
                notifyQrResult(false, null, "Сканирование отменено")
            }
        } else {
            notifyQrResult(false, null, "Сканирование отменено")
        }
    }

    private fun startQrScan() {
        val i = Intent(this, QrScanActivity::class.java).apply {
            putExtra(QrScanActivity.EXTRA_ACTION, pendingQrAction ?: "")
            putExtra(QrScanActivity.EXTRA_DONE, pendingQrDone)
            putExtra(QrScanActivity.EXTRA_NEED, pendingQrNeed)
            putExtra(QrScanActivity.EXTRA_CLIENT, pendingQrClient)
            putExtra(QrScanActivity.EXTRA_CALLBACK, pendingQrCallback ?: "qrScanCallback")
        }
        // Непрерывный скан: мост отправляет каждый отсканированный код в веб,
        // чтобы веб отметил место на сервере, не закрывая камеру (QrScanActivity
        // остаётся открытой, пока не отсканированы все места или не нажали «Назад»).
        QrScanActivity.webSignal = { code, act, d, n ->
            val payload = JSONObject()
                .put("ok", true)
                .put("code", code)
                .put("cancelled", false)
                .put("action", act)
                .put("done", d)
                .put("need", n)
                .put("message", "")
            val cb = pendingQrCallback ?: "qrScanCallback"
            callJs("window.$cb && window.$cb(${payload.toString()})")
        }
        scanLauncher.launch(i)
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startQrScan()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
        // Скан-сессия завершена — снимаем непрерывный мост.
        QrScanActivity.webSignal = null
    }

    // Save the WebView state (current page and its history) so that when the
    // Activity is recreated (or the background process is killed after "Home")
    // the WebView does not reload and does not require gateway re-authorization.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            webView.saveState(outState)
        } catch (_: Exception) {
            // Сохранение состояния WebView — вспомогательное, сбой не критичен.
        }
    }

    // Перед уходом в фон принудительно записываем cookies на диск. Session-cookie
    // шлюза иначе не успевает сохраниться до того, как система убьёт/пересоздаст
    // WebView при сворачивании — после возврата куки сессии нет, и шлюз снова
    // показывает экран «Защищённый сервер / Войти через Bitrix24». flush() в
    // onPause + onStop покрывает и короткое сворачивание, и переключение
    // приложения / kill процесса.
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(scannerReceiver)
        } catch (_: Exception) {
            // приёмник мог быть не зарегистрирован — это не ошибка
        }
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // куки — вспомогательное; сбой не должен ронять приложение
        }
    }

    override fun onStop() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
            // см. onPause
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Регистрируем приёмник сканера ТСД (Broadcast-режим). Приёмник
        // динамический, поэтому активно ровно столько, сколько живо окно.
        try {
            registerReceiver(scannerReceiver, IntentFilter(ATOL_SCAN_ACTION))
        } catch (_: Exception) {
            // нет прав/дубликат — пропускаем, клавиатурный путь останется рабочим
        }
        // Перезапускаем keepalive при возврате из фона: если пользователь только что
        // вошёл заново, свежая кука сессии подхватывается сразу (сервис START_STICKY
        // всё равно пережил бы фон, но после ручного входа стоп/старт не лишний).
        SessionKeepAliveService.start(this)
        // Если при возврате из фона главный документ не загружен (WebView был
        // пересоздан, а restoreState не восстановил страницу) — возвращаемся на
        // приложение. Куки сессии к этому моменту уже записаны flush() в
        // onPause/onStop, поэтому шлюз пустит без повторного входа.
        try {
            if (::webView.isInitialized) {
                val url = webView.url
                if (url == null || url.isBlank() || url == "about:blank") {
                    webView.loadUrl(APP_URL)
                }
            }
        } catch (_: Exception) {
            // восстановление — вспомогательное; сбой не критичен
        }
    }

    override fun onDestroy() {
        SessionKeepAliveService.stop(this)
        try {
            tts?.shutdown()
            tts = null
            ttsReady = false
        } catch (_: Exception) {
            // освобождение ресурса TTS — вспомогательное
        }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        // Экшен широковещания Android-службы сканера ТСД Атол (режим Broadcast).
        // Настраивается в предустановленной утилите «Scan tool» (Barcode Send Model
        // → Broadcast; Extra key = scannerdata; пароль по умолчанию 888888).
        private const val ATOL_SCAN_ACTION = "com.android.server.scannerservice.broadcast"

        // Адрес BIOTIME-приложения (за шлюзом платформы — сессия водителя
        // подхватывается автоматически).
        const val APP_URL = "https://app-2660de1a180b.vibecode.bitrix24.tech/"
    }
}
