package ua.zaika.carremote.air

/**
 * What the map last showed for each track, so a marker never glitches between polls.
 *
 * Every poll rebuilds the list from NEPTUN and the Air Force channel afresh; without memory a drone
 * the channel had moved ahead snaps back to NEPTUN's older fix once the post ages out, loses its
 * «jet» mark with it, and a channel-only drone vanishes the moment its post expires. Here:
 *  - a track never goes back in time: an older fix than the one shown keeps the shown one;
 *  - NEPTUN «confirming» a spot behind where the channel already saw the drone doesn't pull it back;
 *  - a jet stays a jet;
 *  - a track that drops out lingers (greyed by its age on the map, never alarming — it's stale)
 *    until it is clearly gone, instead of blinking out.
 */
class TrackMemory {
    private val held = HashMap<String, AirThreat>()

    /**
     * @param absorbed ids of channel-only tracks that a NEPTUN track took over this poll: those
     *   must not linger as a second marker of the same drone.
     * @param neptunDown NEPTUN didn't answer: keep its tracks as they were rather than drop them all.
     */
    fun apply(list: List<AirThreat>, now: Long, absorbed: Set<String> = emptySet(), neptunDown: Boolean = false): List<AirThreat> {
        val out = list.map { t -> held[t.id]?.let { hold(t, it) } ?: t }
        val live = out.mapTo(HashSet()) { it.id }
        val lingering = held.values.filter { m ->
            m.id !in live && m.id !in absorbed &&
                (m.source == "airforce" || neptunDown) && now - (m.updatedMs ?: 0) <= lingerMs(m)
        }
        held.clear()
        (out + lingering).forEach { held[it.id] = it }
        return out + lingering
    }

    /**
     * Tracks recognised as jets only after [apply], by their measured speed: remember that too, or a
     * jet whose speed estimate dips on a sparse poll turns back into a plain Shahed.
     */
    fun markJets(ids: Collection<String>) {
        for (id in ids) held[id]?.let { if (!it.jet) held[id] = it.copy(jetSeen = true) }
    }

    private fun hold(t: AirThreat, m: AirThreat): AirThreat {
        val tAt = t.updatedMs ?: 0; val mAt = m.updatedMs ?: 0
        val keepShown = tAt < mAt || behind(t, m)
        val p = if (keepShown) t.copy(
            lat = m.lat, lon = m.lon, updatedMs = m.updatedMs, uncertaintyKm = m.uncertaintyKm,
            positionQuality = m.positionQuality, heading = m.heading ?: t.heading,
        ) else t.copy(heading = t.heading ?: m.heading)
        return p.copy(
            jetSeen = t.jet || m.jet,
            airForce = t.airForce ?: m.airForce,
            speedKmh = t.speedKmh ?: m.speedKmh,
        )
    }

    /**
     * NEPTUN re-confirms a spot with a fresh time while the channel already placed the drone further
     * on: a newer fix that lies BEHIND the shown one along the course, within a few minutes of it,
     * is NEPTUN lagging, not the drone turning back.
     */
    private fun behind(t: AirThreat, m: AirThreat): Boolean {
        if (m.positionQuality != AIR_FORCE) return false
        val tAt = t.updatedMs ?: return false; val mAt = m.updatedMs ?: return false
        if (tAt - mAt > 10 * 60_000) return false
        val course = m.heading?.toDouble() ?: return false
        val km = Geo.distanceKm(m.lat, m.lon, t.lat, t.lon)
        if (km < 5) return false
        return Geo.angleDiff(Geo.bearingDeg(m.lat, m.lon, t.lat, t.lon), course) > 100
    }

    private fun lingerMs(t: AirThreat): Long =
        if (t.jet || t.type in setOf("missile", "ballistic", "mig31k", "kab")) 10 * 60_000L else 20 * 60_000L

    companion object {
        const val AIR_FORCE = "Повітряні сили"
    }
}
