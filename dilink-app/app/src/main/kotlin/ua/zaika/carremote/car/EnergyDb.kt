package ua.zaika.carremote.car

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One drive from the car's own energy log (EC_database.EnergyConsumption). */
data class EcTrip(
    val startMs: Long,
    val endMs: Long,
    val km: Double,
    val kwh: Double,
    val fuel: Double,
    val durationS: Long,
) {
    val per100: Double? get() = if (km >= 0.5) kwh / km * 100 else null
}

/**
 * Reads the head unit's built-in trip/energy journal — the same SQLite the stock energy screen
 * and BYDMate use. It sits on shared storage (`/storage/emulated/0/energydata/EC_database.db`),
 * so with READ_EXTERNAL_STORAGE (+ legacy storage) the app can read every past drive, not just
 * the ones it recorded itself. Copied to cache before opening to avoid fighting the writer.
 */
object EnergyDb {

    private val CANDIDATES = listOf(
        "/storage/emulated/0/energydata/EC_database.db",
        "/sdcard/energydata/EC_database.db",
    )

    fun available(): Boolean = CANDIDATES.any { File(it).canRead() }

    /** Every recorded session (not just real drives), newest first — nothing filtered or
     *  capped, so totals reflect all energy used (including idle/precondition sessions). */
    fun trips(context: Context): List<EcTrip> {
        val src = CANDIDATES.map(::File).firstOrNull { it.canRead() } ?: return emptyList()
        val copy = File(context.cacheDir, "EC_database.db")
        return try {
            src.copyTo(copy, overwrite = true)
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val out = ArrayList<EcTrip>()
                db.rawQuery(
                    "SELECT start_timestamp,end_timestamp,trip,electricity,fuel,duration FROM EnergyConsumption " +
                        "WHERE is_deleted=0 ORDER BY start_timestamp DESC",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            EcTrip(
                                startMs = c.getLong(0) * 1000L,
                                endMs = c.getLong(1) * 1000L,
                                km = c.getDouble(2),
                                kwh = c.getDouble(3),
                                fuel = c.getDouble(4),
                                durationS = c.getLong(5),
                            ),
                        )
                    }
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
