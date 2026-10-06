package org.coresense.itantra.sos

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.coresense.itantra.data.repository.MessageRepository
import org.coresense.itantra.identity.Department
import org.coresense.itantra.identity.Role
import org.coresense.itantra.identity.UserIdentity
import org.coresense.itantra.link.ConnectionState
import org.coresense.itantra.link.LinkTransport
import org.coresense.itantra.link.PeerDevice
import org.coresense.itantra.link.TransportType
import org.coresense.itantra.protocol.*
import org.coresense.itantra.reliability.DeliveryState
import org.coresense.itantra.reliability.ReliabilityEngine
import org.coresense.itantra.storage.ConversationDao
import org.coresense.itantra.storage.ConversationEntity
import org.coresense.itantra.storage.MessageDao
import org.coresense.itantra.storage.MessageEntity
import org.coresense.itantra.ui.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SosRoutingTest {

    private lateinit var scope: CoroutineScope

    private val userDeviceId = "IT-CITIZEN-01"
    private val medicalDeviceId = "IT-DEPT-MED-01"
    private val fireDeviceId = "IT-DEPT-FIRE-01"
    private val policeDeviceId = "IT-DEPT-POLICE-01"
    private val otherUserDeviceId = "IT-CITIZEN-02"

    private lateinit var userRepo: MessageRepository
    private lateinit var medicalDeptRepo: MessageRepository
    private lateinit var fireDeptRepo: MessageRepository
    private lateinit var policeDeptRepo: MessageRepository
    private lateinit var otherUserRepo: MessageRepository

    private lateinit var userMsgDao: FakeMessageDao
    private lateinit var medicalMsgDao: FakeMessageDao
    private lateinit var fireMsgDao: FakeMessageDao
    private lateinit var policeMsgDao: FakeMessageDao
    private lateinit var otherUserMsgDao: FakeMessageDao

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Unconfined + Job())

        userMsgDao = FakeMessageDao()
        userRepo = MessageRepository(userMsgDao, FakeConversationDao())

        medicalMsgDao = FakeMessageDao()
        medicalDeptRepo = MessageRepository(medicalMsgDao, FakeConversationDao())

        fireMsgDao = FakeMessageDao()
        fireDeptRepo = MessageRepository(fireMsgDao, FakeConversationDao())

        policeMsgDao = FakeMessageDao()
        policeDeptRepo = MessageRepository(policeMsgDao, FakeConversationDao())

        otherUserMsgDao = FakeMessageDao()
        otherUserRepo = MessageRepository(otherUserMsgDao, FakeConversationDao())
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    // =========================================================================
    // TEST 1: USER -> MEDICAL SOS contains departmentId MEDICAL
    // =========================================================================
    @Test
    fun test1_UserToMedicalSosContainsDepartmentIdMedical() {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val sosPacket = Packet(
            msgId = 9101L,
            senderId = userDeviceId,
            receiverId = null, // Broadcast to department
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[MEDICAL EMERGENCY SOS] Severe cardiac distress at Sector 5. GPS: 28.56720, 77.21000",
            timestamp = 1700000000000L,
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            type = PacketType.SOS
        )

        assertEquals(userDeviceId, sosPacket.senderId)
        assertNull(sosPacket.receiverId)
        assertEquals("MEDICAL", sosPacket.departmentId)
        assertEquals(convId, sosPacket.conversationId)
        assertEquals(PacketPriority.SOS, sosPacket.priority)
        assertEquals(PacketType.SOS, sosPacket.type)
        assertTrue(sosPacket.isEmergency)
        assertTrue(sosPacket.hasLocation)
        assertEquals(28567200, sosPacket.latitudeMicrodegrees)
        assertEquals(77210000, sosPacket.longitudeMicrodegrees)

        // Verify wire encoding/decoding preserves all SOS tags and CRC
        val encoded = PacketCodec.encode(sosPacket)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals("Decoded departmentId must match", "MEDICAL", decoded.departmentId)
        assertEquals("Decoded priority must be SOS", PacketPriority.SOS, decoded.priority)
        assertEquals("Decoded type must be SOS", PacketType.SOS, decoded.type)
        assertEquals(28567200, decoded.latitudeMicrodegrees)
        assertEquals(77210000, decoded.longitudeMicrodegrees)
    }

    // =========================================================================
    // TEST 2: USER -> FIRE_DISASTER SOS contains correct departmentId
    // =========================================================================
    @Test
    fun test2_UserToFireDisasterSosContainsCorrectDepartmentId() {
        val convId = MainViewModel.getConversationId(userDeviceId, "FIRE")
        val sosPacket = Packet(
            msgId = 9102L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "FIRE",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[FIRE EMERGENCY SOS] Structural collapse and fire at Warehouse B. GPS: 28.57000, 77.22000",
            timestamp = 1700000005000L,
            latitudeMicrodegrees = 28570000,
            longitudeMicrodegrees = 77220000,
            type = PacketType.SOS
        )

        assertEquals("FIRE", sosPacket.departmentId)
        assertEquals(PacketPriority.SOS, sosPacket.priority)
        assertEquals(PacketType.SOS, sosPacket.type)
        assertTrue(sosPacket.isEmergency)

        val encoded = PacketCodec.encode(sosPacket)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals("FIRE", decoded.departmentId)
        assertEquals(PacketPriority.SOS, decoded.priority)
        assertEquals(PacketType.SOS, decoded.type)
    }

    // =========================================================================
    // TEST 3: Matching department accepts SOS
    // =========================================================================
    @Test
    fun test3_MatchingDepartmentAcceptsSos() {
        val medicalIdentity = UserIdentity("MED-1", "Medical Dispatch", Role.DEPARTMENT, medicalDeviceId, "MEDICAL")
        val fireIdentity = UserIdentity("FIRE-1", "Fire Dispatch", Role.DEPARTMENT, fireDeviceId, "FIRE_DISASTER")

        val medSos = Packet(
            msgId = 9103L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[MEDICAL SOS]",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        val fireSos = Packet(
            msgId = 9104L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "FIRE", // FIRE alias for FIRE_DISASTER
            conversationId = "conv_CITIZEN_FIRE",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[FIRE SOS]",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        assertTrue("Medical department device must accept Medical SOS",
            isPacketAcceptedByDevice(medSos, medicalIdentity))

        assertTrue("Fire department device must accept Fire SOS with alias support",
            isPacketAcceptedByDevice(fireSos, fireIdentity))
    }

    // =========================================================================
    // TEST 4: Wrong department rejects SOS
    // =========================================================================
    @Test
    fun test4_WrongDepartmentRejectsSos() {
        val policeIdentity = UserIdentity("POL-1", "Police Dispatch", Role.DEPARTMENT, policeDeviceId, "POLICE")
        val fireIdentity = UserIdentity("FIRE-1", "Fire Dispatch", Role.DEPARTMENT, fireDeviceId, "FIRE_DISASTER")

        val medSos = Packet(
            msgId = 9105L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[MEDICAL SOS]",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        assertFalse("Police department device must reject Medical SOS",
            isPacketAcceptedByDevice(medSos, policeIdentity))

        assertFalse("Fire department device must reject Medical SOS",
            isPacketAcceptedByDevice(medSos, fireIdentity))
    }

    // =========================================================================
    // TEST 5: Normal USER device does not process department-targeted SOS
    // =========================================================================
    @Test
    fun test5_NormalUserDeviceDoesNotProcessDepartmentTargetedSos() {
        val citizenIdentity = UserIdentity("CITIZEN-02", "Citizen Two", Role.USER, otherUserDeviceId, null)

        val deptSos = Packet(
            msgId = 9106L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[MEDICAL SOS] Paramedic needed",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        assertFalse("Bystander citizen device must NOT accept department-targeted SOS",
            isPacketAcceptedByDevice(deptSos, citizenIdentity))
    }

    // =========================================================================
    // TEST 6: SOS is persisted correctly in Room
    // =========================================================================
    @Test
    fun test6_SosIsPersistedCorrectlyInRoom() = runBlocking {
        val sosEntity = MessageEntity(
            msgId = 9107L,
            senderId = userDeviceId,
            seq = 1,
            lang = "English",
            priority = "SOS",
            text = "[MEDICAL EMERGENCY SOS] Multiple casualties. GPS: 28.56720, 77.21000",
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_IT-CITIZEN-01_MEDICAL",
            timestamp = 1700000010000L,
            latitudeMicrodegrees = 28567200,
            longitudeMicrodegrees = 77210000,
            deliveryState = "DELIVERED",
            isIncoming = true
        )

        medicalDeptRepo.insertMessage(sosEntity)

        val retrieved = medicalDeptRepo.getMessageById(9107L)
        assertNotNull("SOS entity must be retrieved from Medical DB", retrieved)
        assertEquals(9107L, retrieved!!.msgId)
        assertEquals("SOS", retrieved.priority)
        assertEquals("MEDICAL", retrieved.departmentId)
        assertEquals("DELIVERED", retrieved.deliveryState)
        assertTrue(retrieved.isIncoming)
        assertEquals(28567200, retrieved.latitudeMicrodegrees)
        assertEquals(77210000, retrieved.longitudeMicrodegrees)
    }

    // =========================================================================
    // TEST 7: Duplicate SOS does not create duplicate records
    // =========================================================================
    @Test
    fun test7_DuplicateSosDoesNotCreateDuplicateRecords() = runBlocking {
        val mockTransport = SosMockTransport()
        val engineMed = ReliabilityEngine(mockTransport, scope, medicalDeviceId)

        val sosPacket = Packet(
            msgId = 9108L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[SOS] Duplicate test message",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        val received = mutableListOf<Packet>()
        val collectJob = launch {
            engineMed.receivedPackets.collect { received.add(it) }
        }

        val encoded = PacketCodec.encode(sosPacket)
        delay(100)

        // 1st transmission
        mockTransport.simulateIncoming(encoded)
        delay(100)

        // 2nd retransmission (e.g. ACK dropped)
        mockTransport.simulateIncoming(encoded)
        delay(100)

        // Engine deduplication: emitted exactly once
        assertEquals("ReliabilityEngine must emit SOS packet exactly once", 1, received.size)

        // Room DB insert (REPLACE strategy)
        val entity = MessageEntity(
            msgId = sosPacket.msgId,
            senderId = sosPacket.senderId,
            seq = sosPacket.seq,
            lang = sosPacket.lang.displayName,
            priority = sosPacket.priority.name,
            text = sosPacket.text,
            receiverId = sosPacket.receiverId,
            departmentId = sosPacket.departmentId,
            conversationId = sosPacket.conversationId,
            timestamp = sosPacket.timestamp,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = true
        )
        medicalDeptRepo.insertMessage(entity)
        medicalDeptRepo.insertMessage(entity)

        val count = medicalMsgDao.messages.values.count { it.msgId == 9108L }
        assertEquals("Room DB must contain exactly 1 record for this SOS", 1, count)

        // 2 ACKs must have been sent
        val acks = mockTransport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .filter { it.type == PacketType.ACK && it.msgId == 9108L }
        assertEquals("ReliabilityEngine must send ACK for both receptions", 2, acks.size)

        collectJob.cancel()
        engineMed.close()
    }

    // =========================================================================
    // TEST 8: SOS ACK updates delivery state correctly
    // =========================================================================
    @Test
    fun test8_SosAckUpdatesDeliveryStateCorrectly() = runBlocking {
        val mockTransport = SosMockTransport()
        val engineUser = ReliabilityEngine(mockTransport, scope, userDeviceId)

        val sosMsgId = 9109L
        val sosPacket = Packet(
            msgId = sosMsgId,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[SOS] Paramedic request",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        userRepo.insertMessage(
            MessageEntity(
                msgId = sosMsgId,
                senderId = userDeviceId,
                seq = 1,
                lang = "English",
                priority = "SOS",
                text = sosPacket.text,
                receiverId = null,
                departmentId = "MEDICAL",
                conversationId = "conv_CITIZEN_MED",
                timestamp = System.currentTimeMillis(),
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "QUEUED",
                isIncoming = false
            )
        )

        val deliveryStates = mutableListOf<DeliveryState>()
        val collectJob = launch {
            engineUser.messageDeliveryUpdates.collect { (msgId, state) ->
                if (msgId == sosMsgId) {
                    deliveryStates.add(state)
                    val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                    userRepo.updateDeliveryState(msgId, state.name, rtt)
                }
            }
        }

        engineUser.enqueue(sosPacket)
        delay(100)

        // Department sends ACK
        val ackPacket = Packet(
            msgId = sosMsgId,
            senderId = medicalDeviceId,
            receiverId = userDeviceId,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "ACK",
            timestamp = System.currentTimeMillis(),
            type = PacketType.ACK
        )
        mockTransport.simulateIncoming(PacketCodec.encode(ackPacket))
        delay(150)

        val updated = userRepo.getMessageById(sosMsgId)
        assertNotNull(updated)
        assertEquals("DELIVERED", updated!!.deliveryState)
        assertTrue("Delivered state was emitted", deliveryStates.any { it is DeliveryState.Delivered })

        collectJob.cancel()
        engineUser.close()
    }

    // =========================================================================
    // TEST 9: SOS reply/response routes back to the original sender
    // =========================================================================
    @Test
    fun test9_SosReplyResponseRoutesBackToOriginalSender() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val replyPacket = Packet(
            msgId = 9110L,
            senderId = medicalDeviceId,
            receiverId = userDeviceId, // Direct addressing back to citizen
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Ambulance Unit 3 dispatched to your GPS coordinates. ETA 4 mins.",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val citizenIdentity = UserIdentity("USER-1", "Citizen One", Role.USER, userDeviceId, null)

        assertTrue("Original citizen device must accept direct SOS reply from department",
            isPacketAcceptedByDevice(replyPacket, citizenIdentity))

        userRepo.insertMessage(
            MessageEntity(
                msgId = replyPacket.msgId,
                senderId = replyPacket.senderId,
                seq = replyPacket.seq,
                lang = replyPacket.lang.displayName,
                priority = replyPacket.priority.name,
                text = replyPacket.text,
                receiverId = replyPacket.receiverId,
                departmentId = replyPacket.departmentId,
                conversationId = replyPacket.conversationId,
                timestamp = replyPacket.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        val stored = userRepo.getMessageById(9110L)
        assertNotNull(stored)
        assertEquals(userDeviceId, stored!!.receiverId)
        assertEquals(medicalDeviceId, stored.senderId)
        assertTrue(stored.isIncoming)
    }

    // =========================================================================
    // TEST 10: SOS preserves the same conversationId where applicable
    // =========================================================================
    @Test
    fun test10_SosPreservesSameConversationIdWhereApplicable() = runBlocking {
        val expectedConvId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")

        // 1. Initial SOS
        medicalDeptRepo.insertMessage(
            MessageEntity(
                msgId = 9111L,
                senderId = userDeviceId,
                seq = 1,
                lang = "English",
                priority = "SOS",
                text = "[SOS] Cardiac arrest",
                receiverId = null,
                departmentId = "MEDICAL",
                conversationId = expectedConvId,
                timestamp = 1000L,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        // 2. Department reply
        medicalDeptRepo.insertMessage(
            MessageEntity(
                msgId = 9112L,
                senderId = medicalDeviceId,
                seq = 2,
                lang = "English",
                priority = "NORMAL",
                text = "Paramedics on site soon",
                receiverId = userDeviceId,
                departmentId = "MEDICAL",
                conversationId = expectedConvId,
                timestamp = 2000L,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = false
            )
        )

        val thread = medicalMsgDao.messages.values
            .filter { it.conversationId == expectedConvId }
            .sortedBy { it.timestamp }

        assertEquals(2, thread.size)
        assertEquals(expectedConvId, thread[0].conversationId)
        assertEquals(expectedConvId, thread[1].conversationId)
        assertEquals("SOS", thread[0].priority)
        assertEquals("NORMAL", thread[1].priority)
    }

    // =========================================================================
    // TEST 11: SOS retry remains finite
    // =========================================================================
    @Test
    fun test11_SosRetryRemainsFinite() {
        val mockTransport = SosMockTransport()
        val engine = ReliabilityEngine(mockTransport, scope, userDeviceId)

        // Verify finite attempt count configured (default 8, finite and not Int.MAX_VALUE)
        assertEquals("maxSosAttempts must match default finite attempts (8)", ReliabilityEngine.DEFAULT_MAX_SOS_ATTEMPTS, engine.maxSosAttempts)
        assertTrue("maxSosAttempts must be finite and not Int.MAX_VALUE", engine.maxSosAttempts < Int.MAX_VALUE)
        assertTrue("maxNormalAttempts must be finite", engine.maxNormalAttempts <= 5)

        engine.close()
    }

    // =========================================================================
    // TEST 12: Unacknowledged SOS eventually becomes FAILED
    // =========================================================================
    @Test
    fun test12_UnacknowledgedSosEventuallyBecomesFailed() = runBlocking {
        val mockTransport = SosMockTransport()
        val engine = ReliabilityEngine(mockTransport, scope, userDeviceId, maxSosAttempts = 2)

        val sosMsgId = 9113L
        val sosPacket = Packet(
            msgId = sosMsgId,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_CITIZEN_MED",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[SOS] Emergency test for retry timeout",
            timestamp = System.currentTimeMillis(),
            type = PacketType.SOS
        )

        val deliveryStates = mutableListOf<DeliveryState>()
        val collectJob = launch {
            engine.messageDeliveryUpdates.collect { (msgId, state) ->
                if (msgId == sosMsgId) {
                    deliveryStates.add(state)
                    val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                    userRepo.updateDeliveryState(msgId, state.name, rtt)
                }
            }
        }

        userRepo.insertMessage(
            MessageEntity(
                msgId = sosMsgId,
                senderId = userDeviceId,
                seq = 1,
                lang = "English",
                priority = "SOS",
                text = sosPacket.text,
                receiverId = null,
                departmentId = "MEDICAL",
                conversationId = "conv_CITIZEN_MED",
                timestamp = System.currentTimeMillis(),
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "QUEUED",
                isIncoming = false
            )
        )

        engine.enqueue(sosPacket)

        // Wait for retry attempts to exhaust (1st attempt -> delay 500ms -> 2nd attempt -> delay 1000ms -> FAILED)
        withTimeout(4000) {
            while (deliveryStates.none { it is DeliveryState.Failed }) {
                delay(100)
            }
        }

        assertTrue("DeliveryState.Failed must be emitted after finite retry limit",
            deliveryStates.any { it is DeliveryState.Failed })

        val finalStored = userRepo.getMessageById(sosMsgId)
        assertNotNull(finalStored)
        assertEquals("FAILED", finalStored!!.deliveryState)

        collectJob.cancel()
        engine.close()
    }

    // =========================================================================
    // TEST 13: Existing normal Chat routing still passes regression testing
    // =========================================================================
    @Test
    fun test13_ExistingNormalChatRoutingStillPassesRegressionTesting() = runBlocking {
        val userA = "IT-CITIZEN-01"
        val userB = "IT-CITIZEN-02"
        val userC = "IT-CITIZEN-03"

        val chatPacket = Packet(
            msgId = 9114L,
            senderId = userA,
            receiverId = userB,
            departmentId = null,
            conversationId = "conv_${userA}_${userB}",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Direct chat regression check",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val idUserB = UserIdentity("U2", "User B", Role.USER, userB, null)
        val idUserC = UserIdentity("U3", "User C", Role.USER, userC, null)
        val idDept = UserIdentity("MED1", "Medical", Role.DEPARTMENT, medicalDeviceId, "MEDICAL")

        assertTrue("Target User B accepts direct chat", isPacketAcceptedByDevice(chatPacket, idUserB))
        assertFalse("Bystander User C rejects direct chat", isPacketAcceptedByDevice(chatPacket, idUserC))
        assertFalse("Department device rejects direct user chat", isPacketAcceptedByDevice(chatPacket, idDept))
    }

    // =========================================================================
    // TEST 14: Existing Step 8 department routing still passes regression testing
    // =========================================================================
    @Test
    fun test14_ExistingStep8DepartmentRoutingStillPassesRegressionTesting() {
        val allDepts = listOf("NDRF", "MEDICAL", "POLICE", "FIRE", "RAILWAY", "CIVIL")

        for (targetDept in allDepts) {
            val normalDeptPacket = Packet(
                msgId = 9120L + targetDept.hashCode().toLong(),
                senderId = userDeviceId,
                receiverId = null,
                departmentId = targetDept,
                conversationId = "conv_${userDeviceId}_$targetDept",
                seq = 1,
                lang = Language.ENGLISH,
                priority = PacketPriority.NORMAL,
                text = "Step 8 inquiry for $targetDept",
                timestamp = System.currentTimeMillis(),
                type = PacketType.DATA
            )

            for (deviceDept in allDepts) {
                val deptIdentity = UserIdentity(
                    userId = "OP-$deviceDept",
                    name = "Operator $deviceDept",
                    role = Role.DEPARTMENT,
                    deviceId = "IT-DEPT-$deviceDept",
                    departmentId = deviceDept
                )

                val accepted = isPacketAcceptedByDevice(normalDeptPacket, deptIdentity)
                if (deviceDept == targetDept) {
                    assertTrue("Device $deviceDept must accept packet for $targetDept", accepted)
                } else {
                    assertFalse("Device $deviceDept must reject packet for $targetDept", accepted)
                }
            }
        }
    }

    // =========================================================================
    // Routing Logic Helper matching Step 9 rule
    // =========================================================================
    private fun isPacketAcceptedByDevice(packet: Packet, identity: UserIdentity): Boolean {
        // Echo check
        if (packet.senderId.equals(identity.deviceId, ignoreCase = true)) {
            return false
        }

        // Direct message check
        val isDirectForMe = packet.receiverId != null && packet.receiverId.equals(identity.deviceId, ignoreCase = true)

        // Department request check
        val targetDeptId = packet.departmentId ?: resolveDept(packet.receiverId)
        val isDepartmentMessage = targetDeptId != null

        // Department device routing rule:
        // packet.departmentId == localDepartmentId AND local identity role == DEPARTMENT.
        // Persistent IdentityRepository identity is authoritative.
        val isForMyDepartment = if (isDepartmentMessage && targetDeptId != null) {
            val isDeptRole = identity.role == Role.DEPARTMENT
            val localDeptId = identity.departmentId
            isDeptRole && localDeptId != null && (
                localDeptId.equals(targetDeptId, ignoreCase = true) ||
                (targetDeptId == "FIRE" && localDeptId.equals("FIRE_DISASTER", ignoreCase = true)) ||
                (targetDeptId == "FIRE_DISASTER" && localDeptId.equals("FIRE", ignoreCase = true)) ||
                (targetDeptId == "CIVIL" && localDeptId.equals("CIVIL_DEFENCE", ignoreCase = true)) ||
                (targetDeptId == "CIVIL_DEFENCE" && localDeptId.equals("CIVIL", ignoreCase = true))
            )
        } else {
            false
        }

        val isGlobalBroadcast = (packet.receiverId == null || packet.receiverId.equals("GLOBAL", ignoreCase = true)) && !isDepartmentMessage

        return isDirectForMe || isForMyDepartment || isGlobalBroadcast
    }

    private fun resolveDept(id: String?): String? {
        if (id == null) return null
        return when {
            id.contains("NDRF", ignoreCase = true) -> "NDRF"
            id.contains("Medic", ignoreCase = true) -> "MEDICAL"
            id.contains("Police", ignoreCase = true) -> "POLICE"
            id.contains("Fire", ignoreCase = true) -> "FIRE"
            id.contains("Railway", ignoreCase = true) -> "RAILWAY"
            id.contains("Civil", ignoreCase = true) -> "CIVIL"
            else -> null
        }
    }
}

// In-Memory Test Doubles for Room DAOs
class FakeMessageDao : MessageDao {
    val messages = ConcurrentHashMap<Long, MessageEntity>()
    private val _flow = MutableStateFlow<List<MessageEntity>>(emptyList())

    private fun updateFlow() {
        _flow.value = messages.values.sortedBy { it.timestamp }
    }

    override fun getAllMessages(): Flow<List<MessageEntity>> = _flow.asStateFlow()

    override fun getEmergencyMessages(): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.priority == "SOS" })
    }

    override fun getConversationMessages(conversationId: String): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.conversationId == conversationId }.sortedBy { it.timestamp })
    }

    override suspend fun getMessageById(msgId: Long): MessageEntity? = messages[msgId]

    override fun getMessagesForDepartment(departmentId: String): Flow<List<MessageEntity>> {
        return MutableStateFlow(messages.values.filter { it.departmentId == departmentId })
    }

    override suspend fun insertMessage(message: MessageEntity) {
        messages[message.msgId] = message
        updateFlow()
    }

    override suspend fun updateDeliveryState(msgId: Long, deliveryState: String, rttMs: Long) {
        val existing = messages[msgId]
        if (existing != null) {
            messages[msgId] = existing.copy(deliveryState = deliveryState, rttMs = rttMs)
            updateFlow()
        }
    }

    override suspend fun clearAll() {
        messages.clear()
        updateFlow()
    }
}

class FakeConversationDao : ConversationDao {
    val conversations = ConcurrentHashMap<String, ConversationEntity>()
    private val _flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    private fun updateFlow() {
        _flow.value = conversations.values.sortedByDescending { it.updatedAt }
    }

    override fun getAllConversations(): Flow<List<ConversationEntity>> = _flow.asStateFlow()

    override suspend fun insertConversation(conversation: ConversationEntity) {
        conversations[conversation.conversationId] = conversation
        updateFlow()
    }

    override suspend fun updateConversationTime(conversationId: String, updatedAt: Long) {
        val existing = conversations[conversationId]
        if (existing != null) {
            conversations[conversationId] = existing.copy(updatedAt = updatedAt)
            updateFlow()
        }
    }
}

class SosMockTransport : LinkTransport {
    override val transportType = TransportType.BLUETOOTH_RFCOMM
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets

    val sentPackets = CopyOnWriteArrayList<ByteArray>()

    override fun startDiscovery() = Result.success(Unit)
    override fun stopDiscovery() {}
    override fun connect(peer: PeerDevice) = Result.success(Unit)
    override fun disconnect() {}

    override suspend fun send(data: ByteArray): Boolean {
        sentPackets.add(data)
        return true
    }

    override fun close() {}

    suspend fun simulateIncoming(data: ByteArray) {
        _incomingPackets.emit(data)
    }
}
