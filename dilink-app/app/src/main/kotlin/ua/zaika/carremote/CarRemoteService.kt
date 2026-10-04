package ua.zaika.carremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.car.Notifier
import ua.zaika.carremote.car.ShutdownReport
import ua.zaika.carremote.car.TripTracker
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
    private var wakeLock: PowerManager.WakeLock? = null

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
        val bot = TelegramBot(token, prefs, router::handle)

        // Shell-uid watchdog: survives ignition-off, restarts us on wake, and sends the
        // "car switched off" message itself (the app is killed too fast to do it). It needs
        // the owner chat; without it we have no one to message anyway.
        val watchdog = if (owner != 0L) Watchdog(adb, token, owner) else null
        scope.launch {
            watchdog?.run { install(); restart() }
            // The watchdog dies with a full power-down; this anchor brings us back after one.
            KeepAliveA11yService.ensureEnabled(adb)
        }

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
                // Retry the greeting soon if it has not gone out yet (Telegram may be a few
                // seconds behind the ADB link right after a wake); idle slowly once greeted.
                delay(if (!ok) 10_000 else if (!wasReachable) 4_000 else 60_000)
            }
        }

        // Push charging / low-battery alerts to the owner on state changes.
        if (owner != 0L) {
            val notifier = Notifier(car) { router.lowSoc() }
            val trip = TripTracker()
            scope.launch {
                var ticks = 0
                while (isActive) {
                    if (car.isReachable()) {
                        for (msg in notifier.poll()) runCatching { bot.send(owner, msg) }
                        // Keep the watchdog's parting message fresh (with the current trip),
                        // and respawn the watchdog if it died.
                        CarSnapshot.read(car)?.let { snap ->
                            trip.baselineIfNeeded(snap)
                            val loc = readLocation()
                            val map = loc?.let { "\n📍 https://maps.google.com/?q=${it.first},${it.second}" } ?: ""
                            val body = ShutdownReport.body(snap) + (trip.line(snap)?.let { "\n$it" } ?: "") + map
                            runCatching { watchdog?.writeParked(body) }
                            runCatching { watchdog?.writeLoc(loc) }
                        }
                        if (ticks++ % 5 == 0) runCatching { watchdog?.ensureRunning() }
                    }
                    delay(30_000)
                }
            }
        }

        // Keep-awake ("available while parked") mode: hold a wake lock and tell the watchdog
        // to keep the unit from sleeping, with a low-battery cutoff to protect the pack.
        val pm = getSystemService(PowerManager::class.java)
        scope.launch {
            while (isActive) {
                var keep = prefs.getBoolean(KEY_KEEP_AWAKE, false)
                if (keep && car.isReachable()) {
                    val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)
                    if (soc != null && soc <= KEEP_AWAKE_MIN_SOC) {
                        keep = false
                        prefs.edit().putBoolean(KEY_KEEP_AWAKE, false).apply()
                        if (owner != 0L) runCatching {
                            bot.send(owner, "🅿️ Режим доступності вимкнено: заряд ${soc.toInt()}% (захист батареї)")
                        }
                    }
                }
                if (keep) {
                    if (wakeLock?.isHeld != true) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carremote:parked")
                            .apply { setReferenceCounted(false); acquire() }
                    }
                } else {
                    wakeLock?.let { if (it.isHeld) it.release() }
                    wakeLock = null
                }
                runCatching { watchdog?.writeKeepAwake(keep) }
                delay(15_000)
            }
        }
    }

    /** Most recent GPS fix from the head unit, or null if unavailable / no permission. */
    private fun readLocation(): Pair<Double, Double>? = try {
        val lm = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    } catch (e: Exception) {
        null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEEP_AWAKE_MIN_SOC = 15f // auto-off below this charge to protect the pack

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
