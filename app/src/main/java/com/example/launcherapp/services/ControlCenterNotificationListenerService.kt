package com.example.launcherapp.services

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Exists right now only so Control Center's "Listen Notification" permission has a real service
 * to enable in system Settings - not yet used to actually show notifications anywhere (that's
 * part of the not-yet-built Control Center overlay panel).
 */
class ControlCenterNotificationListenerService : NotificationListenerService() {

    companion object {
        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
