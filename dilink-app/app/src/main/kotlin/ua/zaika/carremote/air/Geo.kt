package ua.zaika.carremote.air

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.Looper
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Geo helpers: great-circle distance, bearings, a compass label, and the last GPS fix. */
object Geo {

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Initial bearing in degrees (0..360) from point 1 to point 2. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Smallest absolute difference between two headings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double = abs((a - b + 540) % 360 - 180)

    /** Destination point reached from (lat,lon) going [bearingDeg] for [distKm] (great-circle). */
    fun dest(lat: Double, lon: Double, bearingDeg: Double, distKm: Double): Pair<Double, Double> {
        val r = 6371.0
        val d = distKm / r
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }

    private val COMPASS = listOf("Пн", "ПнСх", "Сх", "ПдСх", "Пд", "ПдЗх", "Зх", "ПнЗх")
    fun compass(deg: Double): String = COMPASS[((((deg % 360) + 360) % 360) / 45.0).roundToInt() % 8]

    private class Fix(val lat: Double, val lon: Double, val tMs: Long)

    // The last fix we trusted. Persisted to disk (not just memory) so that when GPS is jammed —
    // common during air raids, exactly when the map matters — we still show where the car last
    // really was after a restart, instead of a blank "no coordinates".
    @Volatile private var trusted: Fix? = null
    @Volatile private var loaded = false
    private var lastPersistMs = 0L

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val p = context.getSharedPreferences("carremote", Context.MODE_PRIVATE)
            val lat = p.getFloat("gps_lat", Float.NaN).toDouble()
            val lon = p.getFloat("gps_lon", Float.NaN).toDouble()
            if (validCoordinates(lat, lon)) trusted = Fix(lat, lon, p.getLong("gps_ts", 0L))
        }
    }

    private fun remember(context: Context, fix: Fix) {
        trusted = fix
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < 30_000) return // throttle disk writes
        lastPersistMs = now
        runCatching {
            context.getSharedPreferences("carremote", Context.MODE_PRIVATE).edit()
                .putFloat("gps_lat", fix.lat.toFloat())
                .putFloat("gps_lon", fix.lon.toFloat())
                .putLong("gps_ts", fix.tMs).apply()
        }
    }

    /** Ask for live fixes: after removing BYDMate there may be no other GPS subscriber. */
    fun startUpdates(context: Context): AutoCloseable {
        ensureLoaded(context)
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val next = Fix(location.latitude, location.longitude, location.time)
                if (validCoordinates(next.lat, next.lon) && !isJump(trusted, next)) remember(context, next)
            }
        }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if ((fine || provider != LocationManager.GPS_PROVIDER && coarse) && lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(provider, 5_000L, 5f, listener, Looper.getMainLooper())
                }
            } catch (_: SecurityException) {
                // Permission can be revoked between the check and the request.
            } catch (_: IllegalArgumentException) {
                // Vendor firmware may advertise a provider it cannot open.
            }
        }
        return AutoCloseable { runCatching { lm.removeUpdates(listener) } }
    }

    /**
     * Most recent *trusted* GPS fix from the head unit, or null if we have never had one.
     *
     * The head unit's provider sometimes returns nothing (then we keep showing the last position),
     * or a wild outlier that teleports the car far away for one reading. We reject a reading that
     *   • falls outside Ukraine's bounding box, or
     *   • implies an impossible speed from the last trusted fix (a sudden jump),
     * and keep the previous position until a sane fix comes back.
     */
    fun lastKnown(context: Context): Pair<Double, Double>? {
        ensureLoaded(context)
        val raw = rawFix(context)
        val prev = trusted
        if (raw == null) return prev?.let { it.lat to it.lon } // dropout/jam → last known

        val sane = validCoordinates(raw.lat, raw.lon) && !isJump(prev, raw)
        if (!sane) return prev?.let { it.lat to it.lon }

        remember(context, raw)
        return raw.lat to raw.lon
    }

    private fun rawFix(context: Context): Fix? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) null
                else try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null }
            }
            // A newer network-provider fix can be invalid while an older GPS fix is good.
            // Filter BEFORE picking the newest; never seed the tracker with an invalid fix.
            .filter { validCoordinates(it.latitude, it.longitude) }
            .maxByOrNull { it.time }
            ?.let { Fix(it.latitude, it.longitude, if (it.time > 0) it.time else System.currentTimeMillis()) }
    } catch (e: Exception) {
        null
    }

    internal fun validCoordinates(lat: Double, lon: Double) =
        lat.isFinite() && lon.isFinite() && lat in 43.5..53.5 && lon in 21.5..41.5

    /** A fix is a "jump" if it is far from the last trusted one yet implies an impossible speed. */
    private fun isJump(prev: Fix?, next: Fix): Boolean {
        if (prev == null) return false
        val km = distanceKm(prev.lat, prev.lon, next.lat, next.lon)
        if (km < JUMP_MIN_KM) return false
        val hours = (next.tMs - prev.tMs).coerceAtLeast(1_000L) / 3_600_000.0
        return km / hours > MAX_SPEED_KMH
    }

    private const val JUMP_MIN_KM = 10.0     // ignore small moves — only screen large leaps
    private const val MAX_SPEED_KMH = 500.0  // above this between fixes it's a bad reading, not travel
}
