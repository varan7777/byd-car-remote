package ua.zaika.carremote.car

import android.content.Context
import android.util.Log

/**
 * Side/rear camera automations, in the spirit of BYDMate's "blind-spot camera on the turn signal".
 * Two independent triggers, both OFF by default and set from Settings:
 *   • turn signal on              → show the camera view (side / blind-spot)
 *   • a door opens (while parked)  → show the camera view, so you see traffic before stepping out
 *
 * Detection is edge-based (fires on the transition, then lingers), so it does not re-trigger every
 * poll. The DISPLAY depends on two addresses we can only capture on an awake car — see
 * [Fids.TURN_LEFT] / [Fids.CAMERA_VIEW_CTRL]. Until those are filled in this stays safe:
 *   • turn detection is skipped entirely ([turnReady] is false),
 *   • door detection still runs (the door fids are known) so the probe can confirm it in logcat,
 *   • [show] writes/launches nothing it isn't configured for — at worst it logs the event.
 *
 * The camera package can also be set at runtime via [pkgOverride] (Settings → KEY_CAM_PKG), so the
 * launch target can be filled in without a rebuild once we discover it.
 */
class CameraWatch(
    private val context: Context,
    private val car: Autoservice,
) {
    enum class Side { LEFT, RIGHT, REAR }

    /** Toggled from the service each tick off SharedPreferences. */
    @Volatile var onTurn = false
    @Volatile var onDoor = false
    /** Optional camera package to launch when there is no autoservice command for the view. */
    @Volatile var pkgOverride: String? = null

    private var showingUntil = 0L
    private var lastSide: Side? = null
    private var doorWasOpen = false

    /** True once a turn-signal address is known; lets the service skip turn polling otherwise. */
    val turnReady get() = Fids.isSet(Fids.TURN_LEFT) || Fids.isSet(Fids.TURN_RIGHT)

    /** One poll. Cheap when idle: at most two reads for turns, four for doors, and only while
     *  the matching feature is enabled. */
    fun poll() {
        val now = System.currentTimeMillis()

        if (onTurn && turnReady) {
            val left = Fids.isSet(Fids.TURN_LEFT) && car.getInt(Fids.TURN_DEV, Fids.TURN_LEFT) == 1
            val right = Fids.isSet(Fids.TURN_RIGHT) && car.getInt(Fids.TURN_DEV, Fids.TURN_RIGHT) == 1
            val side = when { left -> Side.LEFT; right -> Side.RIGHT; else -> null }
            if (side != null) { show(side); showingUntil = now + LINGER_MS }
        }

        if (onDoor) {
            val open = Fids.DOORS.any { car.getInt(Fids.DEV_BODYWORK, it) == 1 }
            if (open && !doorWasOpen) { show(Side.REAR); showingUntil = now + LINGER_MS }
            doorWasOpen = open
        }

        if (showingUntil in 1..now) { hide(); showingUntil = 0 }
    }

    private fun show(side: Side) {
        if (side == lastSide && showingUntil > now()) return // already showing this view
        lastSide = side
        Log.i(TAG, "camera trigger: $side")
        when {
            Fids.isSet(Fids.CAMERA_VIEW_CTRL) -> car.write(Fids.CAMERA_DEV, Fids.CAMERA_VIEW_CTRL, 1)
            else -> launchPackage()
        }
    }

    private fun hide() {
        lastSide = null
        if (Fids.isSet(Fids.CAMERA_VIEW_CTRL)) car.write(Fids.CAMERA_DEV, Fids.CAMERA_VIEW_CTRL, 0)
        // A launched package can't be dismissed cleanly from here; the driver/car returns from it.
    }

    private fun launchPackage() {
        val pkg = pkgOverride?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
            else Log.w(TAG, "camera package not found: $pkg")
        }.onFailure { Log.w(TAG, "launch camera failed", it) }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "CameraWatch"
        const val LINGER_MS = 2_500L // keep the view up this long after the trigger clears
    }
}
