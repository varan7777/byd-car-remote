package ua.zaika.carremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Notifier
import ua.zaika.carremote.telegram.TelegramBot

/**
 * Keeps the ADB and Telegram links alive while the head unit is on.
 *
 * No wake lock on purpose: holding one could stop DiLink from sleeping and drain the
 * 12V battery of a parked car. Keep an eye on the 12V line in /status while testing.
 */
class CarRemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var adb: AdbShell

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Car Remote", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Запуск…"))

        adb = AdbShell(this)
        val car = Autoservice(adb)

        val token = BuildConfig.TELEGRAM_BOT_TOKEN
        val owner = BuildConfig.OWNER_CHAT_ID
        if (token.isBlank()) {
            updateStatus("Telegram: немає TELEGRAM_BOT_TOKEN у secrets.properties")
            return
        }
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val router = CommandRouter(car, owner, prefs)
        val bot = TelegramBot(token, router::handle)
        // skipBacklog (inside run) still drops stale commands; the greeting is sent by the
        // reachability loop instead, so it also fires when the car wakes from sleep.
        scope.launch { bot.run(::updateStatus) {} }

        // DiLink does not deliver boot broadcasts to us and freezes/kills the app on ignition
        // off. So instead of relying on autostart, we watch the car link: every time it comes
        // back (cold start OR wake from sleep) we re-greet the owner. Connecting right away
        // also makes the "Allow USB debugging?" prompt appear while someone is in the car.
        scope.launch {
            var wasReachable = false
            while (isActive) {
                val ok = car.isReachable()
                adbStatus = if (ok) "ADB: підключено" else "ADB: немає (${adb.lastError}). Дозволь ключ на екрані"
                if (ok && !wasReachable) {
                    val greeted = owner == 0L ||
                        runCatching { bot.send(owner, router.menu("🚗 Авто онлайн")) }.isSuccess
                    if (greeted) wasReachable = true // don't greet again until the next sleep
                } else if (!ok) {
                    wasReachable = false
                }
                delay(if (ok) 60_000 else 10_000)
            }
        }

        // Push charging / low-battery alerts to the owner on state changes.
        if (owner != 0L) {
            val notifier = Notifier(car) { router.lowSoc() }
            scope.launch {
                while (isActive) {
                    if (car.isReachable()) {
                        for (msg in notifier.poll()) runCatching { bot.send(owner, msg) }
                    }
                    delay(30_000)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        adb.close()
        telegramStatus = "Сервіс зупинено"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(text: String) {
        if (text == telegramStatus) return
        telegramStatus = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Car Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "car_remote"
        private const val NOTIFICATION_ID = 1

        // Read by the status screen.
        @Volatile var adbStatus = "ADB: ще не перевірено"
            private set
        @Volatile var telegramStatus = "Сервіс не запущено"
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarRemoteService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarRemoteService::class.java))
        }
    }
}
