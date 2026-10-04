package ua.zaika.carremote

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import ua.zaika.carremote.adb.AdbShell

/**
 * Cold-boot autostart anchor. Android binds an *enabled* accessibility service early during
 * boot and re-binds it after a kill — the one mechanism that survives a full overnight
 * power-down, when the shell watchdog is gone and DiLink filters BOOT_COMPLETED. This is how
 * BYDMate comes back on this firmware. We read nothing from the screen; each bind just
 * (re)starts the remote service.
 */
class KeepAliveA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        CarRemoteService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }

    override fun onInterrupt() { /* not used */ }

    companion object {
        private const val COMPONENT = "ua.zaika.carremote/.KeepAliveA11yService"

        /** Enables this service if it isn't yet, appending to (never replacing) the services
         *  other apps rely on, e.g. BYDMate's. Idempotent — safe to run on every start. */
        fun ensureEnabled(adb: AdbShell) {
            adb.exec(
                "S=\$(settings get secure enabled_accessibility_services); " +
                    "case \"\$S\" in *ua.zaika.carremote/*) ;; " +
                    "''|null) settings put secure enabled_accessibility_services $COMPONENT ;; " +
                    "*) settings put secure enabled_accessibility_services \"\$S:$COMPONENT\" ;; esac; " +
                    "settings put secure accessibility_enabled 1",
            )
        }
    }
}
