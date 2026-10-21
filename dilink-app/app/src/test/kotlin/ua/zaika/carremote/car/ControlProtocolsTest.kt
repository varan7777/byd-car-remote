package ua.zaika.carremote.car

import org.junit.Assert.*
import org.junit.Test
import ua.zaika.carremote.air.Geo

class ControlProtocolsTest {
    @Test fun invalidInitialGpsFixCannotSeedLocalThreatDistances() {
        assertTrue(Geo.validCoordinates(50.0, 30.0))
        assertFalse(Geo.validCoordinates(0.0, 0.0))
        assertFalse(Geo.validCoordinates(30.0, 115.0))
        assertFalse(Geo.validCoordinates(Double.NaN, 30.0))
        assertFalse(Geo.validCoordinates(50.0, Double.POSITIVE_INFINITY))
    }
    @Test fun binderReplyDistinguishesReadValueFromWriteStatus() {
        assertEquals(83, BinderReply.parse("Result: Parcel(00000000 00000053 '....S...')")?.value)
        assertTrue(BinderReply.parse("Result: Parcel(00000001)")!!.writeAccepted)
        assertTrue(BinderReply.parse("Result: Parcel(00000000 00000001)")!!.writeAccepted)
        assertFalse(BinderReply.parse("Result: Parcel(ffffd8e5 00000000)")!!.writeAccepted)
        assertFalse(BinderReply.parse("Result: Parcel(00000000 ffffd8e5)")!!.writeAccepted)
        assertNull(BinderReply.parse("service: autoservice not found"))
        assertNull(BinderReply.parse("SecurityException: permission denied"))
    }

    private fun decision(gear: Int? = 4, speed: Float? = 30f, turn: Int? = 2,
        age: Long = 0, doors: List<Int?> = emptyList(), onDoor: Boolean = false) =
        CameraDecision.choose(true, onDoor, 15, gear, speed, turn, doors, age)

    @Test fun turnMasksAndThresholdAreRespected() {
        assertEquals(CameraSide.LEFT, decision(turn = 2))
        assertEquals(CameraSide.RIGHT, decision(turn = 4))
        for (mask in listOf(null, 0, 1, 6, 9)) assertNull(decision(turn = mask))
        assertNull(decision(speed = 14.9f))
        assertEquals(CameraSide.LEFT, decision(speed = 15f))
        assertNull(CameraDecision.choose(false, false, 15, 4, 30f, 2, emptyList(), 0))
    }

    @Test fun reverseUnknownAndStaleTelemetryAlwaysHideCameras() {
        for (gear in listOf(null, 0, 2, 5)) assertNull(decision(gear = gear))
        for (speed in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertNull(decision(speed = speed))
        assertNull(decision(age = 751))
        assertNull(decision(age = -1))
        assertNull(decision(gear = 1, speed = 0f))
    }

    @Test fun doorPreviewNeedsParkAndOneUnambiguousSide() {
        val left = listOf(1, 0, 0, 0)
        assertEquals(CameraSide.LEFT, decision(gear = 1, speed = 0f, doors = left, onDoor = true))
        assertEquals(CameraSide.RIGHT, decision(gear = 1, speed = 0f, doors = listOf(0, 0, 0, 1), onDoor = true))
        assertNull(decision(gear = 2, speed = 0f, doors = left, onDoor = true))
        assertNull(decision(gear = 1, speed = 1f, doors = left, onDoor = true))
        assertNull(decision(gear = 1, speed = 0f, doors = listOf(1, 1, 0, 0), onDoor = true))
        assertNull(decision(gear = 1, speed = 0f, doors = listOf(1, null, 0, 0), onDoor = true))
        assertNull(decision(gear = 1, speed = 0f, doors = left))
    }

    @Test fun contradictoryPrimarySeatChannelUsesBodyworkWithoutCachingUnprovenSuccess() {
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        var cached = false
        val seats = SeatHeating({ _, _ -> 0 }, { dev, fid, value ->
            writes += Triple(dev, fid, value); Autoservice.WriteResult(true, true, 1)
        }, {}, { cached }, { cached = true })
        val reply = seats.set(true, 3)
        assertEquals(listOf(
            Triple(1000, Fids.SEAT_HEAT_DRIVER_SWITCH, 1),
            Triple(1000, Fids.SEAT_HEAT_DRIVER_LEVEL, 3),
            Triple(1001, Fids.SEAT_HEAT_DRIVER_FALLBACK, 4),
        ), writes)
        assertFalse(reply.startsWith("✅"))
        assertFalse(cached)
        writes.clear()
        seats.set(true, 0)
        assertEquals(listOf(Triple(1000, Fids.SEAT_HEAT_DRIVER_SWITCH, 2),
            Triple(1001, Fids.SEAT_HEAT_DRIVER_FALLBACK, 1)), writes)
    }

    @Test fun lostConnectionNeverTriggersSeatFallback() {
        var count = 0
        val seats = SeatHeating({ _, _ -> null }, { _, _, _ ->
            count++; Autoservice.WriteResult(false, false, null)
        }, {})
        assertTrue(seats.set(false, 1).startsWith("⚠️"))
        assertEquals(1, count)
    }

    @Test fun verifiedPrimarySeatDoesNotSendAlternativeCommand() {
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        val seats = SeatHeating({ _, fid -> if (fid == Fids.SEAT_HEAT_DRIVER_STATUS) 1 else 3 },
            { dev, fid, value -> writes += Triple(dev, fid, value); Autoservice.WriteResult(true, true, 1) }, {})
        assertTrue(seats.set(true, 3).startsWith("✅"))
        assertEquals(2, writes.size)
    }

    @Test fun rejectedWritesCannotClaimSeatSuccess() {
        var count = 0
        val seats = SeatHeating({ _, _ -> 0 }, { _, _, _ ->
            count++; Autoservice.WriteResult(false, false, -10011)
        }, {})
        assertTrue(seats.set(false, 1).startsWith("⚠️"))
        assertEquals(2, count)
    }

    @Test fun unsupportedPassengerOffStateIsNotClaimedAsConfirmation() {
        val seats = SeatHeating({ _, _ -> 0 }, { _, _, _ -> Autoservice.WriteResult(true, true, 1) }, {})
        assertFalse(seats.set(false, 0).startsWith("✅"))
    }
}
