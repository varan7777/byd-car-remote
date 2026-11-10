package ua.zaika.carremote.air

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMemoryTest {
    private val min = 60_000L
    private val t0 = 1_762_050_000_000L

    private fun track(id: String, lat: Double, lon: Double, at: Long, heading: Int? = 270, airForce: String? = null,
                      quality: String = "estimated", source: String = "neptun") = AirThreat(
        id = id, type = "uav", title = "", region = "", locality = "", lat = lat, lon = lon, heading = heading,
        confidence = "medium", sourceCount = 1, uncertaintyKm = 10.0, explanation = "", updatedMs = at,
        positionQuality = quality, airForce = airForce, source = source,
    )

    /** The night of 02.11: the channel moved a jet across Vinnytsia, then its post aged out. */
    @Test fun jetDoesNotSnapBackOrTurnIntoAShahedWhenThePostExpires() {
        val m = TrackMemory()
        // NEPTUN at Vinnytsia's eastern edge; the channel puts the jet 40 km on, west of the city, 3 min later.
        m.apply(listOf(track("n1", 49.23, 28.60, t0)), t0)
        val merged = track("n1", 49.23, 28.05, t0 + 3 * min, airForce = "реактивний", quality = TrackMemory.AIR_FORCE)
        m.apply(listOf(merged), t0 + 3 * min)
        // 9 min later the post is gone: NEPTUN alone, still its old fix.
        val after = m.apply(listOf(track("n1", 49.23, 28.60, t0)), t0 + 12 * min).single()
        assertEquals(28.05, after.lon, 1e-9)
        assertEquals(t0 + 3 * min, after.updatedMs)
        assertTrue(after.jet)
    }

    @Test fun neptunConfirmingASpotBehindTheChannelDoesNotPullTheDroneBack() {
        val m = TrackMemory()
        m.apply(listOf(track("n1", 49.23, 28.05, t0, airForce = "реактивний", quality = TrackMemory.AIR_FORCE)), t0)
        // NEPTUN re-confirms its old position (east, behind a westbound drone) with a newer time.
        val after = m.apply(listOf(track("n1", 49.23, 28.60, t0 + 2 * min)), t0 + 2 * min).single()
        assertEquals(28.05, after.lon, 1e-9)
    }

    @Test fun aNewerFixAheadMovesTheDrone() {
        val m = TrackMemory()
        m.apply(listOf(track("n1", 49.23, 28.60, t0)), t0)
        val after = m.apply(listOf(track("n1", 49.23, 28.30, t0 + 5 * min)), t0 + 5 * min).single()
        assertEquals(28.30, after.lon, 1e-9)
        assertFalse(after.jet)
    }

    @Test fun aChannelOnlyDroneLingersThenGoes() {
        val m = TrackMemory()
        val af = track("af:1", 49.0, 28.0, t0, source = "airforce", quality = TrackMemory.AIR_FORCE)
        m.apply(listOf(af), t0)
        assertEquals(listOf("af:1"), m.apply(emptyList(), t0 + 16 * min).map { it.id })
        assertTrue(m.apply(emptyList(), t0 + 21 * min).isEmpty())
    }

    @Test fun aChannelDroneTakenOverByNeptunIsNotDrawnTwice() {
        val m = TrackMemory()
        m.apply(listOf(track("af:1", 49.0, 28.0, t0, source = "airforce")), t0)
        val out = m.apply(listOf(track("n1", 49.0, 28.0, t0 + min)), t0 + min, absorbed = setOf("af:1"))
        assertEquals(listOf("n1"), out.map { it.id })
    }

    @Test fun aJetKnownOnlyByItsSpeedStaysAJet() {
        val m = TrackMemory()
        m.apply(listOf(track("n1", 49.0, 28.0, t0)), t0)
        m.markJets(listOf("n1")) // the poll measured 400 km/h
        val after = m.apply(listOf(track("n1", 49.0, 27.9, t0 + min)), t0 + min).single()
        assertTrue(after.jet)
    }

    @Test fun neptunTracksDisappearWithNeptunButStayWhenItIsDown() {
        val m = TrackMemory()
        m.apply(listOf(track("n1", 49.0, 28.0, t0)), t0)
        assertEquals(listOf("n1"), m.apply(emptyList(), t0 + min, neptunDown = true).map { it.id })
        assertTrue(m.apply(emptyList(), t0 + 2 * min).isEmpty())
    }
}
