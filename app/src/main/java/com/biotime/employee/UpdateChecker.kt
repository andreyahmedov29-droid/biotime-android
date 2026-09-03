package com.biotime.employee

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Результат проверки обновления: есть ли новая версия и как её получить.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
) {
    fun isNewerThan(currentCode: Int): Boolean = versionCode > currentCode
}

/**
 * Опрашивает сервер BIOTIME (GET /api/app/update-info) и узнаёт актуальную
 * версию APK. Вызывается в фоне (не на главном потоке) при запуске приложения.
 */
object UpdateChecker {

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(MainActivity.APP_URL.trimEnd('/') + "/api/app/update-info")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val j = JSONObject(body)
            return@withContext UpdateInfo(
                versionCode = j.optInt("versionCode", 1),
                versionName = j.optString("versionName", ""),
                apkUrl = j.optString("apkUrl", ""),
                notes = j.optString("notes", "")
            )
        } catch (_: Exception) {
            // сеть недоступна / сервер не ответил — пропускаем обновление
            null
        }
    }

    /** Текущая versionCode установленного приложения. */
    fun currentVersionCode(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: Exception) {
        0
    }
}
