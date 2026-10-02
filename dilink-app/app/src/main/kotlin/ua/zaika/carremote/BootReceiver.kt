package ua.zaika.carremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the service whenever the head unit comes up, so the car goes online without
 * touching the screen. DiLink rarely reboots: on ignition off it force-stops apps
 * ("quickboot") and on ignition on it sends QUICKBOOT_POWERON instead of BOOT_COMPLETED.
 * The manifest filter limits which actions arrive here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CarRemoteService.start(context)
    }
}
