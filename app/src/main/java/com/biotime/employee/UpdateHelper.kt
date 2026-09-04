package com.biotime.employee

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает новый APK с сервера и открывает системный установщик.
 * Возвращает true, если файл скачан и установщик запущен.
 */
object UpdateHelper {

    private const val AUTHORITY = "com.biotime.employee.fileprovider"
    private const val FILE_DIR = "update"

    /** Результат скачивания: файл, если успешно, иначе понятная причина ошибки. */
    data class DownloadResult(val file: File?, val error: String?)

    /**
     * Итог попытки открыть установщик. Вместо сырого Boolean возвращаем и причину,
     * чтобы приложение могло показать водителю конкретный шаг, а не молча закрыть
     * окно: MISSING_PERMISSION — нужно разрешение на установку из неизвестных
     * источников; NO_HANDLER — на устройстве нет установщика; ERROR — иное.
     * INSTALLED — установщик запущен (дальше действует системный экран).
     */
    enum class InstallResult { INSTALLED, MISSING_PERMISSION, NO_HANDLER, ERROR }

    /** Скачивает APK во временную папку приложения. Имя с versionCode, чтобы не кэшировать старое. */
    fun download(context: Context, apkUrl: String, versionCode: Int): DownloadResult {
        try {
            if (apkUrl.isBlank()) {
                return DownloadResult(null, "Ссылка на обновление не задана на сервере.")
            }
            val dir = File(context.filesDir, FILE_DIR).apply { mkdirs() }
            val target = File(dir, "biotime-${versionCode}.apk")
            if (target.exists()) return DownloadResult(target, null)

            val tmp = File(dir, "biotime-${versionCode}.apk.part")
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive")
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                conn.disconnect()
                return DownloadResult(null, "Сервер вернул ошибку ($responseCode) при скачивании APK.")
            }

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                    }
                }
            }
            conn.disconnect()

            val ok = tmp.exists() && tmp.length() > 0
            if (!ok) {
                return DownloadResult(null, "Файл обновления пустой или не скачался.")
            }
            val final = if (tmp.renameTo(target)) target else tmp
            return DownloadResult(final, null)
        } catch (e: Exception) {
            // Не показываем технический мусор, но даём понятное сообщение.
            val msg = e.message ?: "Неизвестная ошибка"
            return DownloadResult(null, "Не удалось скачать обновление: $msg")
        }
    }

    /**
     * Открывает системный установщик для скачанного APK.
     * @return InstallResult.INSTALLED, если установщик запущен; иначе — конкретная
     *         причина (см. enum), чтобы UI показал понятный шаг вместо молчания.
     */
    fun promptInstall(context: Context, apkFile: File): InstallResult {
        try {
            val uri = FileProvider.getUriForFile(context, AUTHORITY, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Android 8+ требует разрешение на установку неизвестных приложений.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val canInstall = context.packageManager.canRequestPackageInstalls()
                if (!canInstall) {
                    val settings = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    try {
                        context.startActivity(settings)
                    } catch (_: Exception) {
                        // на некоторых устройствах активности нет — вернём причину,
                        // пользователь включит разрешение вручную в настройках
                    }
                    return InstallResult.MISSING_PERMISSION
                }
            }
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved == null) {
                return InstallResult.NO_HANDLER
            }
            context.startActivity(intent)
            return InstallResult.INSTALLED
        } catch (e: Exception) {
            return InstallResult.ERROR
        }
    }
}
