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
            LocationTrackingService.start(context)
        }
    }
}
