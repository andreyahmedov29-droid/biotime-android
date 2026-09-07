package com.biotime.employee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * Фоновый keepalive входа (сессии платформенного шлюза Black Hole).
 *
 * Вход в BIOTIME — это cookie сессии шлюза (`session` на домене приложения).
 * Шлюз считает сессию живой, пока к нему идут проксируемые запросы с этой кукой.
 *
 * Когда приложение сворачивают, система через ~10 минут прибивает фоновый процесс
 * вместе с WebView. Если WebView пересоздаётся без сохранившейся на диск куки —
 * шлюз требует вход заново. Обычный фоновый запрос этого не лечит: процесс всё
 * равно умирает и кука, висящая только в памяти WebView, теряется.
 *
 * Решение — FOREGROUND-сервис: он удерживает процесс приложения ЖИВЫМ в фоне
 * (Android не убивает процесс с активным foreground-сервисом), поэтому WebView и
 * его cookie-стор не разрушаются. Каждые [INTERVAL_MS] сервис дополнительно шлёт
 * GET на корень приложения с текущей кукой WebView — так шлюз продлевает сессию,
 * и кука не успевает истечь ни за короткий простой (~10 минут), ни за длинный.
 *
 * В отличие от трекера геолокации работает для ВСЕХ сотрудников (склад, офис,
 * водители) и НЕ зависит от активного рабочего дня. Уведомление неприметное
 * (IMPORTANCE_MIN) — не мешает работе.
 */
class SessionKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Ссылка на текущий цикл пингования. Гарантирует, что повторные onStartCommand
    // (после каждого входного вызова start()) не плодят параллельные циклы: перед
    // запуском нового отменяем предыдущий. Несколько циклов = дубли запросов и
    // лишняя нагрузка на сессию.
    private var keepAliveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startKeepAliveLoop()
        // START_STICKY: если система всё же убьёт сервис — пересоздаст и продолжит.
        return START_STICKY
    }

    @Synchronized
    private fun startKeepAliveLoop() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                pingOnce()
                delay(INTERVAL_MS)
            }
        }
    }

    // GET к приложению с кукой WebView — шлюз проксирует и «продлевает» сессию
    // (тот же приём, что трекер геолокации использует для координат).
    private fun pingOnce() {
        try {
            val url = URL(MainActivity.APP_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val cookie = CookieManager.getInstance().getCookie(url.toString())
                if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            } catch (_: Exception) {
                // куки — вспомогательное; сбой не должен ронять keepalive
            }
            conn.responseCode // 200 — ок; 401/403 — сессия реально истекла
            conn.disconnect()
        } catch (_: Exception) {
            // сеть недоступна (без интернета) — следующая итерация догонит
        }
    }

    private fun buildNotification(): Notification {
        createChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_notif_title))
            .setContentText(getString(R.string.keepalive_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(R.string.keepalive_channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "biotime_keepalive"
        private const val NOTIF_ID = 2
        private const val INTERVAL_MS = 3 * 60 * 1000L // раз в 3 минуты

        /** Запускает фоновый keepalive (для всех сотрудников, после загрузки). */
        fun start(context: Context) {
            val intent = Intent(context, SessionKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Останавливает keepalive (при полном закрытии приложения). */
        fun stop(context: Context) {
            context.stopService(Intent(context, SessionKeepAliveService::class.java))
        }
    }
}
