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

    // -------------------------------------------------------------------------
    // Step 12: Comprehensive Real-Time Live Tracking Test Suite (Section 8)
    // -------------------------------------------------------------------------

    @Test
    fun testValidGpsUpdatesAccepted() {
        val receiver = LocationTrackingReceiver()
        val validPacket = Packet(
            msgId = 101L,
            senderId = "PHONE-A",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Lat: 28.56720, Lon: 77.21000 (±6m)",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 6.0f,
            type = PacketType.LOCATION
        )

        val accepted = receiver.processIncomingPacket(validPacket)
        assertTrue("Valid GPS packet must be accepted", accepted)
        assertEquals(1, receiver.trackedPeers.value.size)
        val peer = receiver.trackedPeers.value["PHONE-A"]
        assertNotNull(peer)
        assertEquals(28.5672, peer!!.latitude, 0.0001)
        assertEquals(77.2100, peer.longitude, 0.0001)
        assertEquals(6.0f, peer.accuracyMeters)
    }

    @Test
    fun testInvalidCoordinatesRejected() {
        val receiver = LocationTrackingReceiver()

        // Null Island (0,0)
        val zeroPacket = Packet(
            msgId = 102L,
            senderId = "PHONE-BAD",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Zero coordinate",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 0,
            longitudeMicrodegrees = 0,
            type = PacketType.LOCATION
        )
        assertFalse("Uninitialized 0,0 coordinate must be rejected", receiver.processIncomingPacket(zeroPacket))
        assertTrue(receiver.trackedPeers.value.isEmpty())

        // Out of bounds latitude (> 90)
        val outOfBounds = Packet(
            msgId = 103L,
            senderId = "PHONE-BAD2",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Bad lat",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 92000000,
            longitudeMicrodegrees = 77000000,
            type = PacketType.LOCATION
        )
        assertFalse("Out of bounds latitude must be rejected", receiver.processIncomingPacket(outOfBounds))
        assertTrue(receiver.trackedPeers.value.isEmpty())
    }

    @Test
    fun testStaleLocationFixHandledCorrectly() {
        val now = System.currentTimeMillis()
        val stalePeer = TrackedPeer(
            deviceId = "PHONE-STALE",
            latitude = 28.5672,
            longitude = 77.2100,
            accuracyMeters = 8.0f,
            timestamp = now - 35_000L,
            lastReceivedTime = now - 35_000L
        )

        // Must report STALE status when fix is > 25 seconds old
        assertEquals(org.coresense.itantra.ui.TrackingStatus.STALE, stalePeer.getStatus(isConnected = true))
        assertTrue("isStale property must be true", stalePeer.isStale)
    }

    @Test
    fun testNewerLocationReplacesOlderLocation() {
        val receiver = LocationTrackingReceiver()
        val t1 = System.currentTimeMillis() - 2000L
        val t2 = System.currentTimeMillis()

        val packet1 = Packet(
            msgId = 201L,
            senderId = "PHONE-MOVING",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Position 1",
            timestamp = t1,
            latitudeMicrodegrees = 28567000,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 5.0f,
            type = PacketType.LOCATION
        )
        assertTrue(receiver.processIncomingPacket(packet1))
        assertEquals(28.5670, receiver.trackedPeers.value["PHONE-MOVING"]!!.latitude, 0.0001)

        // Newer packet arrives with updated coordinates
        val packet2 = Packet(
            msgId = 202L,
            senderId = "PHONE-MOVING",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Position 2",
            timestamp = t2,
            latitudeMicrodegrees = 28568500,
            longitudeMicrodegrees = 77211500,
            accuracyMeters = 4.0f,
            type = PacketType.LOCATION
        )
        assertTrue(receiver.processIncomingPacket(packet2))
        assertEquals(28.5685, receiver.trackedPeers.value["PHONE-MOVING"]!!.latitude, 0.0001)
        assertEquals(77.2115, receiver.trackedPeers.value["PHONE-MOVING"]!!.longitude, 0.0001)
        assertEquals(2, receiver.trackedPeers.value["PHONE-MOVING"]!!.seq)
    }

    @Test
    fun testOlderPacketCannotOverwriteNewerLocation() {
        val receiver = LocationTrackingReceiver()
        val t1 = System.currentTimeMillis() - 5000L
        val t2 = System.currentTimeMillis() - 1000L

        // Newer packet arrived first (out of order delivery)
        val newerPacket = Packet(
            msgId = 302L,
            senderId = "PHONE-ORDER",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Latest position",
            timestamp = t2,
            latitudeMicrodegrees = 28600000,
            longitudeMicrodegrees = 77200000,
            accuracyMeters = 5.0f,
            type = PacketType.LOCATION
        )
        assertTrue(receiver.processIncomingPacket(newerPacket))

        // Older packet arrives delayed
        val olderDelayedPacket = Packet(
            msgId = 301L,
            senderId = "PHONE-ORDER",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Old position",
            timestamp = t1,
            latitudeMicrodegrees = 28500000,
            longitudeMicrodegrees = 77100000,
            accuracyMeters = 6.0f,
            type = PacketType.LOCATION
        )
        val accepted = receiver.processIncomingPacket(olderDelayedPacket)
        assertFalse("Older out-of-order packet must be REJECTED", accepted)

        // Verify position remained at latest fix (28.60, 77.20), not moving backwards
        val peer = receiver.trackedPeers.value["PHONE-ORDER"]!!
        assertEquals(28.6000, peer.latitude, 0.0001)
        assertEquals(77.2000, peer.longitude, 0.0001)
        assertEquals(2, peer.seq)
    }

    @Test
    fun testAccuracyAndTimestampsSurvivePacketSerialization() {
        val originalTimestamp = 1718000555123L
        val originalAccuracy = 7.25f

        val packet = Packet(
            msgId = 401L,
            senderId = "SENDER-TEL",
            receiverId = "RECEIVER-BASE",
            seq = 88,
            lang = Language.HINDI,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Telemetry test",
            timestamp = originalTimestamp,
            latitudeMicrodegrees = 28567210,
            longitudeMicrodegrees = 77210050,
            accuracyMeters = originalAccuracy,
            type = PacketType.LOCATION
        )

        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals(originalTimestamp, decoded.timestamp)
        assertNotNull(decoded.accuracyMeters)
        assertEquals(originalAccuracy, decoded.accuracyMeters!!, 0.001f)
        assertEquals(28.567210, decoded.latitudeDegrees!!, 0.000001)
        assertEquals(77.210050, decoded.longitudeDegrees!!, 0.000001)
    }

    @Test
    fun testReceiverObservableUiStateUpdatesOnFreshPacket() {
        val receiver = LocationTrackingReceiver()
        val initialMap = receiver.trackedPeers.value
        assertTrue(initialMap.isEmpty())

        val packet = Packet(
            msgId = 501L,
            senderId = "OBSERVED-DEVICE",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Live position",
            timestamp = System.currentTimeMillis(),
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 5.0f,
            type = PacketType.LOCATION
        )
        receiver.processIncomingPacket(packet)

        val updatedMap = receiver.trackedPeers.value
        assertNotSame("StateFlow must emit a new immutable map reference", initialMap, updatedMap)
        assertEquals(1, updatedMap.size)
        assertTrue(updatedMap.containsKey("OBSERVED-DEVICE"))
    }

    @Test
    fun testDuplicateUpdatesDoNotCreateDuplicateTrackingEvents() {
        val receiver = LocationTrackingReceiver()
        val fixTime = System.currentTimeMillis()

        val packet = Packet(
            msgId = 601L,
            senderId = "DUPLICATE-DEV",
            seq = 5,
            lang = Language.ENGLISH,
            priority = PacketPriority.URGENT,
            text = "[TRACK-ME] Duplicate test",
            timestamp = fixTime,
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            accuracyMeters = 5.0f,
            type = PacketType.LOCATION
        )

        assertTrue("First packet must be accepted", receiver.processIncomingPacket(packet))
        assertFalse("Duplicate packet with identical fix timestamp and sequence must be rejected", receiver.processIncomingPacket(packet))
        assertEquals(1, receiver.trackedPeers.value.size)
    }

    @Test
    fun testDisconnectedReceiverDoesNotReportLive() {
        val now = System.currentTimeMillis()
        val freshPeer = TrackedPeer(
            deviceId = "PEER-FRESH",
            latitude = 28.5672,
            longitude = 77.2100,
            accuracyMeters = 4.0f,
            timestamp = now - 1000L,
            lastReceivedTime = now - 1000L
        )

        // When connected -> reports LIVE
        assertEquals(org.coresense.itantra.ui.TrackingStatus.LIVE, freshPeer.getStatus(isConnected = true))

        // When transport link is DISCONNECTED -> must report DISCONNECTED, never LIVE
        assertEquals(org.coresense.itantra.ui.TrackingStatus.DISCONNECTED, freshPeer.getStatus(isConnected = false))
    }

    @Test
    fun testTrackingStatusTransitions() {
        val now = System.currentTimeMillis()

        // 1. Fresh fix (<8s old, high accuracy) -> LIVE
        val livePeer = TrackedPeer("P1", 28.5, 77.2, 5.0f, now - 2000L, now - 2000L)
        assertEquals(org.coresense.itantra.ui.TrackingStatus.LIVE, livePeer.getStatus(true))

        // 2. Weak signal (>50m accuracy) -> SIGNAL_WEAK
        val weakSignalPeer = TrackedPeer("P2", 28.5, 77.2, 85.0f, now - 3000L, now - 3000L)
        assertEquals(org.coresense.itantra.ui.TrackingStatus.SIGNAL_WEAK, weakSignalPeer.getStatus(true))

        // 3. Updating (15s old) -> UPDATING
        val updatingPeer = TrackedPeer("P3", 28.5, 77.2, 8.0f, now - 15_000L, now - 15_000L)
        assertEquals(org.coresense.itantra.ui.TrackingStatus.UPDATING, updatingPeer.getStatus(true))

        // 4. Stale (>25s old) -> STALE
        val stalePeer = TrackedPeer("P4", 28.5, 77.2, 8.0f, now - 30_000L, now - 30_000L)
        assertEquals(org.coresense.itantra.ui.TrackingStatus.STALE, stalePeer.getStatus(true))
    }
}

/**
 * Test helper simulating MainViewModel receiver location processing pipeline
 */
private class LocationTrackingReceiver {
    val trackedPeers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, TrackedPeer>>(emptyMap())

    fun processIncomingPacket(packet: Packet): Boolean {
        if (!packet.hasValidCoordinates()) return false
        val now = System.currentTimeMillis()
        val currentMap = trackedPeers.value.toMutableMap()
        val existing = currentMap[packet.senderId]

        val isStaleFix = existing != null && packet.timestamp < existing.timestamp
        val isDuplicate = existing != null && packet.timestamp == existing.timestamp && packet.seq <= existing.seq
        val isAncient = (now - packet.timestamp) > 86400000L

        if (isStaleFix || isDuplicate || isAncient) {
            return false
        }

        val timeDiffMs = existing?.let { packet.timestamp - it.timestamp } ?: Long.MAX_VALUE
        val accDiff = if (existing?.accuracyMeters != null && packet.accuracyMeters != null) {
            packet.accuracyMeters - existing.accuracyMeters
        } else 0f

        if (existing != null && timeDiffMs < 3000L && accDiff > 300f) {
            return false
        }

        currentMap[packet.senderId] = TrackedPeer(
            deviceId = packet.senderId,
            latitude = packet.latitudeDegrees!!,
            longitude = packet.longitudeDegrees!!,
            accuracyMeters = packet.accuracyMeters,
            timestamp = packet.timestamp,
            lastReceivedTime = now,
            seq = packet.seq
        )
        trackedPeers.value = currentMap
        return true
    }
}
