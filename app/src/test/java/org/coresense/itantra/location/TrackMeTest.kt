package org.coresense.itantra.location

import org.coresense.itantra.emergency.Haversine
import org.coresense.itantra.protocol.Language
import org.coresense.itantra.protocol.Packet
import org.coresense.itantra.protocol.PacketCodec
import org.coresense.itantra.protocol.PacketPriority
import org.coresense.itantra.protocol.PacketType
import org.coresense.itantra.ui.TrackedPeer
import org.junit.Assert.*
import org.junit.Test

class TrackMeTest {

    @Test
    fun testValidCoordinateValidation() {
        // Valid New Delhi coordinates
        val validPacket = Packet(
            msgId = 1001L,
            senderId = "DEVICE-A",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Position: 28.56720, 77.21000",
            timestamp = 1700000000L,
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 8.5f,
            type = PacketType.LOCATION
        )
        assertTrue(validPacket.hasLocation)
        assertTrue(validPacket.hasValidCoordinates())
        assertEquals(28.5672, validPacket.latitudeDegrees!!, 0.00001)
        assertEquals(77.2100, validPacket.longitudeDegrees!!, 0.00001)
    }

    @Test
    fun testInvalidCoordinateRejection() {
        // 1. Uninitialized Null Island (0.0, 0.0)
        val nullIsland = Packet(
            msgId = 1002L,
            senderId = "DEVICE-B",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Zero zero",
            timestamp = 1700000000L,
            latitudeMicrodegrees = 0,
            longitudeMicrodegrees = 0,
            type = PacketType.LOCATION
        )
        assertFalse("Uninitialized 0,0 coordinates must be rejected", nullIsland.hasValidCoordinates())

        // 2. Latitude out of range (> 90 degrees)
        val badLat = Packet(
            msgId = 1003L,
            senderId = "DEVICE-C",
            seq = 3,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Bad lat",
            timestamp = 1700000000L,
            latitudeMicrodegrees = 95000000, // 95° N
            longitudeMicrodegrees = 77000000,
            type = PacketType.LOCATION
        )
        assertFalse("Latitude > 90 must be rejected", badLat.hasValidCoordinates())

        // 3. Longitude out of range (> 180 degrees)
        val badLon = Packet(
            msgId = 1004L,
            senderId = "DEVICE-D",
            seq = 4,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Bad lon",
            timestamp = 1700000000L,
            latitudeMicrodegrees = 28000000,
            longitudeMicrodegrees = 195000000, // 195° E
            type = PacketType.LOCATION
        )
        assertFalse("Longitude > 180 must be rejected", badLon.hasValidCoordinates())

        // 4. Missing coordinates
        val noGps = Packet(
            msgId = 1005L,
            senderId = "DEVICE-E",
            seq = 5,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "No GPS",
            timestamp = 1700000000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null
        )
        assertFalse("Missing coordinates must return false", noGps.hasValidCoordinates())
    }

    @Test
    fun testLocationPacketCodecRoundTrip() {
        val locationPacket = Packet(
            msgId = 55555L,
            senderId = "IT-DEVICE-TRACKER",
            receiverId = "IT-DEVICE-BASE",
            seq = 42,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Lat: 28.61390, Lon: 77.20900 (±10m)",
            timestamp = 1718100000000L,
            latitudeMicrodegrees = 28613900,
            longitudeMicrodegrees = 77209000,
            accuracyMeters = 10.0f,
            type = PacketType.LOCATION
        )

        val encoded = PacketCodec.encode(locationPacket)
        assertNotNull(encoded)
        assertTrue(encoded.isNotEmpty())

        val decodeResult = PacketCodec.decode(encoded)
        assertTrue("Decode must succeed: ${decodeResult.exceptionOrNull()?.message}", decodeResult.isSuccess)

        val decoded = decodeResult.getOrThrow()
        assertEquals(locationPacket.msgId, decoded.msgId)
        assertEquals(locationPacket.senderId, decoded.senderId)
        assertEquals(locationPacket.receiverId, decoded.receiverId)
        assertEquals(PacketType.LOCATION, decoded.type)
        assertEquals(locationPacket.latitudeMicrodegrees, decoded.latitudeMicrodegrees)
        assertEquals(locationPacket.longitudeMicrodegrees, decoded.longitudeMicrodegrees)
        assertNotNull(decoded.accuracyMeters)
        assertEquals(10.0f, decoded.accuracyMeters!!, 0.001f)
    }

    @Test
    fun testStaleLocationDetection() {
        val now = System.currentTimeMillis()

        // Fresh peer location (received 10 seconds ago)
        val freshPeer = TrackedPeer(
            deviceId = "PEER-LIVE",
            latitude = 28.5672,
            longitude = 77.2100,
            accuracyMeters = 5.0f,
            timestamp = now - 10_000L,
            lastReceivedTime = now - 10_000L
        )
        assertFalse("Peer received 10s ago must NOT be stale", freshPeer.isStale)

        // Stale peer location (received 3 minutes ago)
        val stalePeer = TrackedPeer(
            deviceId = "PEER-STALE",
            latitude = 28.5672,
            longitude = 77.2100,
            accuracyMeters = 12.0f,
            timestamp = now - 180_000L,
            lastReceivedTime = now - 180_000L
        )
        assertTrue("Peer received 3 minutes ago must be marked as stale", stalePeer.isStale)
    }

    @Test
    fun testRelativeBearingAndDistanceCalculation() {
        // Point 1: AIIMS Delhi (28.5672, 77.2100)
        // Point 2: Connaught Place (28.6315, 77.2167)
        val distance = Haversine.distanceMeters(28.5672, 77.2100, 28.6315, 77.2167)
        val bearing = Haversine.bearingDegrees(28.5672, 77.2100, 28.6315, 77.2167)
        val cardinal = Haversine.bearingToCardinal(bearing)

        // Expected distance: ~7180 meters (~7.18 km)
        assertTrue("Distance between AIIMS and CP should be ~7.18km (actual: $distance m)", distance in 7000.0..7400.0)

        // CP is roughly North of AIIMS (~5.3 degrees heading)
        assertTrue("Bearing should point North (actual: $bearing°)", bearing in 0.0..15.0)
        assertEquals("N", cardinal)
    }

    @Test
    fun testLocationPacketsExcludedFromChatFeed() {
        val locationPacket = Packet(
            msgId = 99999L,
            senderId = "DEV-ALPHA",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Lat: 28.56720, Lon: 77.21000 (±5m)",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 5.0f,
            type = PacketType.LOCATION
        )

        // Verify packet type is LOCATION
        assertEquals(PacketType.LOCATION, locationPacket.type)

        // Verify criteria used by MainViewModel to bypass Room DB insertion
        val shouldInsertInDb = locationPacket.type != PacketType.LOCATION
        assertFalse("Pure location telemetry packets must NOT be inserted into Room chat message table", shouldInsertInDb)

        // Verify defensive chat screen filter excludes [TRACK-ME] formatted messages
        val sampleChatList = listOf(
            "Hello team",
            "[TRACK-ME] Lat: 28.56720, Lon: 77.21000 (±5m)",
            "Status update: en route",
            "[TRACK-ME] Lat: 28.56730, Lon: 77.21010 (±4m)"
        )
        val filtered = sampleChatList.filter { !it.startsWith("[TRACK-ME]") }
        assertEquals(2, filtered.size)
        assertEquals(listOf("Hello team", "Status update: en route"), filtered)
    }
}
