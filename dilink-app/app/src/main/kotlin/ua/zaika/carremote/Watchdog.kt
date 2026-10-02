package ua.zaika.carremote

import android.util.Base64
import ua.zaika.carremote.adb.AdbShell

/**
 * A tiny shell-uid watchdog, spawned through on-device ADB. DiLink force-stops the app on
 * ignition-off (so its own services never come back) but does not touch shell processes, so
 * this script survives sleep the way BYDMate's helper daemon does. It:
 *   - restarts CarRemoteService when the head unit is awake and the app is not running
 *     (brings the remote back after a wake, without stealing the screen);
 *   - sends the "car switched off" message itself via curl when it sees the app gone while
 *     the unit is asleep — the app can't do this in time, it is killed within ~0.2 s.
 *
 * The app keeps a fresh snapshot body in [PARKED_PATH]; the watchdog only adds the time.
 * The template uses `§` wherever the shell needs `$`, so Kotlin does not interpolate it.
 */
class Watchdog(private val adb: AdbShell, token: String, chatId: Long) {

    private val script = SCRIPT
        .replace("@TOKEN@", token)
        .replace("@CHAT@", chatId.toString())
        .replace("§", "$")

    /** Writes the script to the device (base64 to survive quoting) and makes it executable. */
    fun install() {
        val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)
        adb.exec("echo $b64 | base64 -d > $SCRIPT_PATH && chmod 700 $SCRIPT_PATH")
    }

    /** Spawns the watchdog detached, unless one is already alive. */
    fun ensureRunning() {
        adb.exec(
            "P=\$(cat $PID_PATH 2>/dev/null); kill -0 \"\$P\" 2>/dev/null || " +
                "(setsid sh $SCRIPT_PATH </dev/null >/dev/null 2>&1 &)",
        )
    }

    /** Kills any existing watchdog and spawns a fresh one — used on app start so a new app
     *  version's script replaces the old one that survived in memory. */
    fun restart() {
        adb.exec(
            "P=\$(cat $PID_PATH 2>/dev/null); [ -n \"\$P\" ] && kill \"\$P\" 2>/dev/null; " +
                "(setsid sh $SCRIPT_PATH </dev/null >/dev/null 2>&1 &)",
        )
    }

    /** Updates the cached parting-message body the watchdog will send on shutdown. */
    fun writeParked(body: String) {
        val b64 = Base64.encodeToString(body.toByteArray(), Base64.NO_WRAP)
        adb.exec("echo $b64 | base64 -d > $PARKED_PATH")
    }

    /** Tells the watchdog whether keep-awake (stay available while parked) is on. */
    fun writeKeepAwake(on: Boolean) {
        adb.exec("echo ${if (on) 1 else 0} > $KEEP_PATH")
    }

    private companion object {
        const val SCRIPT_PATH = "/data/local/tmp/cr_watch.sh"
        const val PID_PATH = "/data/local/tmp/cr_watch.pid"
        const val PARKED_PATH = "/data/local/tmp/cr_parked.txt"
        const val KEEP_PATH = "/data/local/tmp/cr_keepawake"

        val SCRIPT = """
            PKG=ua.zaika.carremote
            TOKEN='@TOKEN@'
            CHAT='@CHAT@'
            PARKED=$PARKED_PATH
            KEEP_FILE=$KEEP_PATH
            echo §§ > $PID_PATH
            prev=1
            cooldown=0
            while true; do
              up=§(pidof §PKG)
              awake=§(dumpsys power 2>/dev/null | grep -c 'mWakefulness=Awake')
              keep=§(cat §KEEP_FILE 2>/dev/null)
              now=§(date +%s)
              # Keep-awake mode: hold a kernel wake lock so the head unit does not suspend.
              if [ "§keep" = 1 ]; then echo carremote > /sys/power/wake_lock 2>/dev/null; else echo carremote > /sys/power/wake_unlock 2>/dev/null; fi
              if [ -n "§up" ]; then
                prev=1
              else
                if [ "§prev" = 1 ]; then
                  prev=0
                  sleep 2
                  # Send the parting snapshot only on a real sleep (not in keep-awake mode).
                  if [ -z "§(pidof §PKG)" ] && [ "§keep" != 1 ] && [ "§(dumpsys power 2>/dev/null | grep -c 'mWakefulness=Awake')" = 0 ] && [ -s "§PARKED" ]; then
                    MSG="🅿️ Авто заглушено о §(date +%H:%M)
            §(cat §PARKED)"
                    curl -s --max-time 15 "https://api.telegram.org/bot§TOKEN/sendMessage" --data-urlencode "chat_id=§CHAT" --data-urlencode "text=§MSG" >/dev/null 2>&1
                    cooldown=§((now + 45))
                  fi
                fi
                # Revive when the screen is awake, or any time while keep-awake is on.
                if { [ "§awake" != 0 ] || [ "§keep" = 1 ]; } && [ "§now" -ge "§cooldown" ]; then
                  am start -n §PKG/.BootstrapActivity >/dev/null 2>&1 || am start -n §PKG/.MainActivity >/dev/null 2>&1
                fi
              fi
              sleep 4
            done
        """.trimIndent()
    }
}
