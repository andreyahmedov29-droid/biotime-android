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
     * @return true, если установщик запущен; false — если нужно разрешение на
     *         установку из неизвестных источников (настройки уже открыты) или ошибка.
     */
    fun promptInstall(context: Context, apkFile: File): Boolean {
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
                    context.startActivity(settings)
                    return false
                }
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            // на устройстве нет установщика / др. ошибка — пропускаем
            return false
        }
    }
}
