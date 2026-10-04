package org.coresense.itantra.emergency

import org.junit.Assert.*
import org.junit.Test

class HaversineTest {

    @Test
    fun testDistanceBetweenKnownPoints() {
        // AIIMS Delhi (28.5672, 77.2100) to Connaught Place (28.6315, 77.2167)
        // Known straight line distance is ~7.2 km
        val distance = Haversine.distanceMeters(28.5672, 77.2100, 28.6315, 77.2167)
        assertTrue("Distance should be around 7.2 km (got $distance m)", distance in 7000.0..7500.0)
    }

    @Test
    fun testBearingNorth() {
        // Going directly North
        val bearing = Haversine.bearingDegrees(28.0, 77.0, 29.0, 77.0)
        assertEquals(0.0, bearing, 0.5)
        assertEquals("N", Haversine.bearingToCardinal(bearing))
    }

    @Test
    fun testBearingEast() {
        // Going directly East along equator
        val bearing = Haversine.bearingDegrees(0.0, 77.0, 0.0, 78.0)
        assertEquals(90.0, bearing, 0.5)
        assertEquals("E", Haversine.bearingToCardinal(bearing))
    }

    @Test
    fun testZeroDistanceSamePoint() {
        val distance = Haversine.distanceMeters(28.5672, 77.2100, 28.5672, 77.2100)
        assertEquals(0.0, distance, 0.001)
    }
}
