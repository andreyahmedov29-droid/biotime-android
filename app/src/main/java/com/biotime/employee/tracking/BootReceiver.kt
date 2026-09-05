package com.biotime.employee.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Перезапускает фоновый трекинг после перезагрузки устройства,
 * чтобы координаты продолжали идти без повторного открытия приложения.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Перезапускаем трекер после перезагрузки ТОЛЬКО если рабочий день
            // ещё активен (начат и не завершён). Вне рабочего дня геолокацию
            // не запрашиваем.
            if (LocationTrackingService.isWorkActive(context)) {
                LocationTrackingService.start(context)
            }
        }
    }
}
