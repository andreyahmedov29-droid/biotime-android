package com.biotime.employee.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Когда GPS был выключен и снова включён, перезапускаем фоновый трекер,
 * чтобы метка водителя на карте не «зависла» и продолжила двигаться.
 */
class GpsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION) {
            LocationTrackingService.start(context)
        }
    }
}
