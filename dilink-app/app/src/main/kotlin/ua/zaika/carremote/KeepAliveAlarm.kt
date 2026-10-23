package ua.zaika.carremote

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Third backstop next to the accessibility anchor and the shell watchdog: every ~90 s, while the
 * head unit is awake, make sure the remote service is running (DiLink's low-memory killer takes
 * it down when YouTube/GBox are open). The alarm is not a wake-up one, so it never keeps a
 * parked car's head unit awake; a force-stop clears it, which is what the watchdog is for.
 */
class KeepAliveAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { CarRemoteService.start(context) }
        schedule(context)
    }

    companion object {
        private const val INTERVAL_MS = 90_000L

        fun schedule(context: Context) {
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, KeepAliveAlarm::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL_MS, pi)
        }
    }
}
