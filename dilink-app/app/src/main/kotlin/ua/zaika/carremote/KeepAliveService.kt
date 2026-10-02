package ua.zaika.carremote

import android.service.notification.NotificationListenerService

/**
 * Autostart anchor. DiLink force-stops the app on ignition-off and never delivers
 * BOOT_COMPLETED to it, so nothing would bring it back. But Android automatically rebinds
 * an *enabled* NotificationListenerService after it is killed or the system restarts — the
 * same mechanism BYDMate uses. We don't read any notifications; we only need the system to
 * keep this service alive, and each time it binds we (re)start the remote service.
 *
 * Requires a one-time "notification access" grant for Car Remote (Settings, or
 * `cmd notification allow_listener`).
 */
class KeepAliveService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        CarRemoteService.start(this)
    }

    override fun onListenerConnected() {
        CarRemoteService.start(this)
    }
}
