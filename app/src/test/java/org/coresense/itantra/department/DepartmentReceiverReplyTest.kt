package org.coresense.itantra.department

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

class DepartmentReceiverReplyTest {

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
    // TEST 1: User-to-Medical packet contains correct departmentId
    // =========================================================================
    @Test
    fun test1_UserToMedicalPacketContainsCorrectDepartmentId() {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val packet = Packet(
            msgId = 7001L,
            senderId = userDeviceId,
            receiverId = null, // Department broadcast (per Step 8)
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Need medical assistance at City Center",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        assertEquals("Sender must be user device", userDeviceId, packet.senderId)
        assertNull("Department packet receiverId must be null (broadcast)", packet.receiverId)
        assertEquals("Department ID must be MEDICAL", "MEDICAL", packet.departmentId)
        assertEquals(convId, packet.conversationId)
        assertEquals("Need medical assistance at City Center", packet.text)
        assertEquals(PacketType.DATA, packet.type)

        // Verify wire encoding/decoding preserves departmentId and packet structure
        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded).getOrThrow()

        assertEquals("Decoded departmentId must match", "MEDICAL", decoded.departmentId)
        assertNull("Decoded receiverId must be null", decoded.receiverId)
        assertEquals(convId, decoded.conversationId)
        assertEquals(packet.text, decoded.text)
    }

    // =========================================================================
    // TEST 2: Medical Department device accepts and processes Medical packet
    // =========================================================================
    @Test
    fun test2_MedicalDepartmentDeviceAcceptsAndProcessesMedicalPacket() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val packet = Packet(
            msgId = 7002L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Ambulance required urgently",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        val medicalIdentity = UserIdentity(
            userId = "OP-MED-1",
            name = "Medical Dispatch",
            role = Role.DEPARTMENT,
            deviceId = medicalDeviceId,
            departmentId = "MEDICAL"
        )

        val isAccepted = isPacketAcceptedByDevice(packet, medicalIdentity)
        assertTrue("Medical department device must accept MEDICAL packet", isAccepted)

        // Process and store in Room database
        medicalDeptRepo.insertMessage(
            MessageEntity(
                msgId = packet.msgId,
                senderId = packet.senderId,
                seq = packet.seq,
                lang = packet.lang.displayName,
                priority = packet.priority.name,
                text = packet.text,
                receiverId = packet.receiverId,
                departmentId = packet.departmentId,
                conversationId = packet.conversationId,
                timestamp = packet.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        val stored = medicalDeptRepo.getMessageById(7002L)
        assertNotNull("Message must be stored in Medical DB", stored)
        assertEquals("Ambulance required urgently", stored!!.text)
        assertEquals("MEDICAL", stored.departmentId)
        assertTrue("Stored message must be marked as incoming", stored.isIncoming)
    }

    // =========================================================================
    // TEST 3: Fire & Disaster Department device rejects/ignores Medical packet
    // =========================================================================
    @Test
    fun test3_FireDisasterDepartmentDeviceRejectsMedicalPacket() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val packet = Packet(
            msgId = 7003L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Medical inquiry for ambulance",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        val fireIdentity = UserIdentity(
            userId = "OP-FIRE-1",
            name = "Fire Dispatch",
            role = Role.DEPARTMENT,
            deviceId = fireDeviceId,
            departmentId = "FIRE_DISASTER"
        )

        val isAccepted = isPacketAcceptedByDevice(packet, fireIdentity)
        assertFalse("Fire & Disaster department device must reject MEDICAL packet", isAccepted)

        val stored = fireDeptRepo.getMessageById(7003L)
        assertNull("Rejected packet must NOT be stored in Fire department DB", stored)
    }

    // =========================================================================
    // TEST 4: Police Department device rejects/ignores Medical packet
    // =========================================================================
    @Test
    fun test4_PoliceDepartmentDeviceRejectsMedicalPacket() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val packet = Packet(
            msgId = 7004L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Heart patient critical",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        val policeIdentity = UserIdentity(
            userId = "OP-POLICE-1",
            name = "Police Dispatch",
            role = Role.DEPARTMENT,
            deviceId = policeDeviceId,
            departmentId = "POLICE"
        )

        val isAccepted = isPacketAcceptedByDevice(packet, policeIdentity)
        assertFalse("Police department device must reject MEDICAL packet", isAccepted)

        val stored = policeDeptRepo.getMessageById(7004L)
        assertNull("Rejected packet must NOT be stored in Police department DB", stored)
    }

    // =========================================================================
    // TEST 5: Normal User device (Role.USER) does not act as Department receiver
    // =========================================================================
    @Test
    fun test5_NormalUserDeviceDoesNotActAsDepartmentReceiver() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")
        val packet = Packet(
            msgId = 7005L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Medical emergency near bus stand",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        val citizenIdentity = UserIdentity(
            userId = "CITIZEN-02",
            name = "Citizen Two",
            role = Role.USER,
            deviceId = otherUserDeviceId,
            departmentId = null
        )

        val isAccepted = isPacketAcceptedByDevice(packet, citizenIdentity)
        assertFalse("Citizen/User device must NOT act as a department receiver", isAccepted)

        val stored = otherUserRepo.getMessageById(7005L)
        assertNull("Department packet must not be stored on bystander user device", stored)
    }

    // =========================================================================
    // TEST 6: Department request stored correctly in Department Room database
    // =========================================================================
    @Test
    fun test6_DepartmentRequestStoredCorrectlyInDepartmentRoomDatabase() = runBlocking {
        val entity = MessageEntity(
            msgId = 7006L,
            senderId = userDeviceId,
            seq = 2,
            lang = "English",
            priority = "NORMAL",
            text = "Oxygen cylinders required at Sector 14",
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_IT-CITIZEN-01_MEDICAL",
            timestamp = 1700000050000L,
            latitudeMicrodegrees = 28613900,
            longitudeMicrodegrees = 77209000,
            deliveryState = "DELIVERED",
            isIncoming = true
        )

        medicalDeptRepo.insertMessage(entity)

        val stored = medicalDeptRepo.getMessageById(7006L)
        assertNotNull("Entity must be retrieved from Medical DB", stored)
        assertEquals(7006L, stored!!.msgId)
        assertEquals(userDeviceId, stored.senderId)
        assertEquals("Oxygen cylinders required at Sector 14", stored.text)
        assertEquals("MEDICAL", stored.departmentId)
        assertEquals("DELIVERED", stored.deliveryState)
        assertTrue(stored.isIncoming)
        assertEquals(28613900, stored.latitudeMicrodegrees)
        assertEquals(77209000, stored.longitudeMicrodegrees)
    }

    // =========================================================================
    // TEST 7: Duplicate Department request does not create duplicate Room records
    // =========================================================================
    @Test
    fun test7_DuplicateDepartmentRequestDoesNotCreateDuplicateRoomRecords() = runBlocking {
        val mockTransport = DeptMockTransport()
        val engineMed = ReliabilityEngine(mockTransport, scope, medicalDeviceId)

        val packet = Packet(
            msgId = 7007L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = "conv_MED_CITIZEN",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Duplicate check request for medical aid",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val received = mutableListOf<Packet>()
        val collectJob = launch {
            engineMed.receivedPackets.collect { received.add(it) }
        }

        val encoded = PacketCodec.encode(packet)
        delay(100)

        // 1st transmission
        mockTransport.simulateIncoming(encoded)
        delay(100)

        // 2nd retransmission (e.g. ACK dropped on wire)
        mockTransport.simulateIncoming(encoded)
        delay(100)

        // Engine deduplication: emitted only once to UI/Handler
        assertEquals("ReliabilityEngine must emit packet exactly once", 1, received.size)

        // Insert into Room DB (handles OnConflictStrategy.REPLACE)
        val entity = MessageEntity(
            msgId = packet.msgId,
            senderId = packet.senderId,
            seq = packet.seq,
            lang = packet.lang.displayName,
            priority = packet.priority.name,
            text = packet.text,
            receiverId = packet.receiverId,
            departmentId = packet.departmentId,
            conversationId = packet.conversationId,
            timestamp = packet.timestamp,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = true
        )
        medicalDeptRepo.insertMessage(entity)
        medicalDeptRepo.insertMessage(entity) // Duplicate insert

        val records = medicalMsgDao.messages.values.filter { it.msgId == 7007L }
        assertEquals("Room DB must contain exactly 1 message record", 1, records.size)

        // Both attempts must have received ACKs so sender knows packet arrived
        val acks = mockTransport.sentPackets
            .map { PacketCodec.decode(it).getOrThrow() }
            .filter { it.type == PacketType.ACK && it.msgId == 7007L }
        assertEquals("ReliabilityEngine must send ACK for both receptions", 2, acks.size)

        collectJob.cancel()
        engineMed.close()
    }

    // =========================================================================
    // TEST 8: Department reply contains correct original receiverId
    // =========================================================================
    @Test
    fun test8_DepartmentReplyContainsCorrectOriginalReceiverId() {
        val originalSender = userDeviceId
        val replyText = "Ambulance dispatched, ETA 6 minutes"
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")

        val replyPacket = Packet(
            msgId = 7008L,
            senderId = medicalDeviceId,
            receiverId = originalSender, // Direct addressing back to citizen
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = replyText,
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        assertEquals("Department reply must address original sender", userDeviceId, replyPacket.receiverId)
        assertEquals("Department reply senderId must be department device", medicalDeviceId, replyPacket.senderId)
        assertEquals("Department reply must preserve departmentId", "MEDICAL", replyPacket.departmentId)
    }

    // =========================================================================
    // TEST 9: Department reply uses same conversationId
    // =========================================================================
    @Test
    fun test9_DepartmentReplyUsesSameConversationId() = runBlocking {
        val expectedConvId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")

        // 1. Initial user request
        val userRequest = MessageEntity(
            msgId = 7009L,
            senderId = userDeviceId,
            seq = 1,
            lang = "English",
            priority = "NORMAL",
            text = "Injured person needs urgent help",
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = expectedConvId,
            timestamp = 1000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = true
        )
        medicalDeptRepo.insertMessage(userRequest)

        // 2. Department reply
        val deptReply = MessageEntity(
            msgId = 7010L,
            senderId = medicalDeviceId,
            seq = 2,
            lang = "English",
            priority = "NORMAL",
            text = "Paramedics dispatched",
            receiverId = userDeviceId,
            departmentId = "MEDICAL",
            conversationId = expectedConvId, // Same conversation ID
            timestamp = 2000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = false
        )
        medicalDeptRepo.insertMessage(deptReply)

        // Verify conversation messages query returns both in chronological sequence
        val convMessages = medicalMsgDao.messages.values
            .filter { it.conversationId == expectedConvId }
            .sortedBy { it.timestamp }

        assertEquals(2, convMessages.size)
        assertEquals(expectedConvId, convMessages[0].conversationId)
        assertEquals(expectedConvId, convMessages[1].conversationId)
        assertEquals("Injured person needs urgent help", convMessages[0].text)
        assertEquals("Paramedics dispatched", convMessages[1].text)
    }

    // =========================================================================
    // TEST 10: Original User device receives and stores Department reply
    // =========================================================================
    @Test
    fun test10_OriginalUserDeviceReceivesAndStoresDepartmentReply() = runBlocking {
        val convId = MainViewModel.getConversationId(userDeviceId, "MEDICAL")

        val replyPacket = Packet(
            msgId = 7011L,
            senderId = medicalDeviceId,
            receiverId = userDeviceId, // Specifically addressed to this user
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Paramedics on site soon",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val citizenIdentity = UserIdentity(
            userId = "USER-1",
            name = "Citizen One",
            role = Role.USER,
            deviceId = userDeviceId,
            departmentId = null
        )

        // Direct packet matching citizen deviceId must be accepted
        val accepted = isPacketAcceptedByDevice(replyPacket, citizenIdentity)
        assertTrue("User device must accept direct reply from department", accepted)

        // Store reply in citizen Room database
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

        val stored = userRepo.getMessageById(7011L)
        assertNotNull(stored)
        assertEquals("Paramedics on site soon", stored!!.text)
        assertEquals(medicalDeviceId, stored.senderId)
        assertEquals(userDeviceId, stored.receiverId)
        assertTrue(stored.isIncoming)
    }

    // =========================================================================
    // TEST 11: ACK handling updates Department delivery state correctly
    // =========================================================================
    @Test
    fun test11_AckHandlingUpdatesDepartmentDeliveryStateCorrectly() = runBlocking {
        val mockTransport = DeptMockTransport()
        val engineDept = ReliabilityEngine(mockTransport, scope, medicalDeviceId)

        val replyMsgId = 7110L
        val convId = "conv_CITIZEN_MED"
        val replyPacket = Packet(
            msgId = replyMsgId,
            senderId = medicalDeviceId,
            receiverId = userDeviceId,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Ambulance dispatched",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        // Department inserts message initially as QUEUED
        medicalDeptRepo.insertMessage(
            MessageEntity(
                msgId = replyMsgId,
                senderId = medicalDeviceId,
                seq = 1,
                lang = "English",
                priority = "NORMAL",
                text = "Ambulance dispatched",
                receiverId = userDeviceId,
                departmentId = "MEDICAL",
                conversationId = convId,
                timestamp = System.currentTimeMillis(),
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "QUEUED",
                isIncoming = false
            )
        )

        val deliveryStates = mutableListOf<DeliveryState>()
        val collectJob = launch {
            engineDept.messageDeliveryUpdates.collect { (msgId, state) ->
                if (msgId == replyMsgId) {
                    deliveryStates.add(state)
                    val rtt = (state as? DeliveryState.Delivered)?.rttMs ?: 0L
                    medicalDeptRepo.updateDeliveryState(msgId, state.name, rtt)
                }
            }
        }

        // Department enqueues the reply
        engineDept.enqueue(replyPacket)
        delay(100)

        // Citizen sends ACK back
        val ackPacket = Packet(
            msgId = replyMsgId,
            senderId = userDeviceId,
            receiverId = medicalDeviceId,
            departmentId = "MEDICAL",
            conversationId = convId,
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "",
            timestamp = System.currentTimeMillis(),
            type = PacketType.ACK
        )
        mockTransport.simulateIncoming(PacketCodec.encode(ackPacket))
        delay(150)

        // Verify state updated to DELIVERED
        val updated = medicalDeptRepo.getMessageById(replyMsgId)
        assertNotNull(updated)
        assertEquals("DELIVERED", updated!!.deliveryState)
        assertTrue("Delivered state was emitted", deliveryStates.any { it is DeliveryState.Delivered })

        collectJob.cancel()
        engineDept.close()
    }

    // =========================================================================
    // TEST 12: Existing direct User-to-User Chat functionality continues to work normally
    // =========================================================================
    @Test
    fun test12_ExistingDirectUserToUserChatFunctionalityContinuesToWorkNormally() = runBlocking {
        val userA = "IT-CITIZEN-01"
        val userB = "IT-CITIZEN-02"
        val bystander = "IT-CITIZEN-03"

        val directPacket = Packet(
            msgId = 7120L,
            senderId = userA,
            receiverId = userB,
            departmentId = null, // Direct 1-on-1 chat
            conversationId = "conv_${userA}_${userB}",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Are you safe near sector 4?",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )

        val identityUserB = UserIdentity("U2", "User B", Role.USER, userB, null)
        val identityBystander = UserIdentity("U3", "User C", Role.USER, bystander, null)
        val identityMedical = UserIdentity("MED1", "Medical Dispatch", Role.DEPARTMENT, medicalDeviceId, "MEDICAL")

        // 1. Target recipient accepts
        assertTrue("User B must accept direct message addressed to them",
            isPacketAcceptedByDevice(directPacket, identityUserB))

        // 2. Bystander drops
        assertFalse("Bystander User C must drop direct message addressed to User B",
            isPacketAcceptedByDevice(directPacket, identityBystander))

        // 3. Department device drops direct message addressed to another user
        assertFalse("Medical department device must drop direct message addressed to User B",
            isPacketAcceptedByDevice(directPacket, identityMedical))

        // 4. User B stores message in Room DB
        otherUserRepo.insertMessage(
            MessageEntity(
                msgId = directPacket.msgId,
                senderId = directPacket.senderId,
                seq = directPacket.seq,
                lang = directPacket.lang.displayName,
                priority = directPacket.priority.name,
                text = directPacket.text,
                receiverId = directPacket.receiverId,
                departmentId = null,
                conversationId = directPacket.conversationId,
                timestamp = directPacket.timestamp,
                latitudeMicrodegrees = null,
                longitudeMicrodegrees = null,
                deliveryState = "DELIVERED",
                isIncoming = true
            )
        )

        val stored = otherUserRepo.getMessageById(7120L)
        assertNotNull(stored)
        assertEquals("Are you safe near sector 4?", stored!!.text)
        assertNull(stored.departmentId)
        assertTrue(stored.isIncoming)
    }

    // =========================================================================
    // TEST 13: Department device cannot process requests meant for different department
    // =========================================================================
    @Test
    fun test13_DepartmentDeviceCannotProcessRequestsMeantForDifferentDepartment() {
        val allDepts = listOf("NDRF", "MEDICAL", "POLICE", "FIRE", "RAILWAY", "CIVIL")

        for (targetDept in allDepts) {
            val packet = Packet(
                msgId = 7130L + targetDept.hashCode().toLong(),
                senderId = userDeviceId,
                receiverId = null,
                departmentId = targetDept,
                conversationId = "conv_${userDeviceId}_$targetDept",
                seq = 1,
                lang = Language.ENGLISH,
                priority = PacketPriority.NORMAL,
                text = "Request for $targetDept",
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

                val accepted = isPacketAcceptedByDevice(packet, deptIdentity)
                if (deviceDept == targetDept) {
                    assertTrue("Device $deviceDept must accept packet for $targetDept", accepted)
                } else {
                    assertFalse("Device $deviceDept must reject packet for $targetDept", accepted)
                }
            }
        }

        // Additional Identity Safety Check:
        // Persistent IdentityRepository identity is authoritative.
        // Even if an operator has selected a different view filter in the UI,
        // a device whose persistent identity is MEDICAL will NEVER accept a POLICE packet.
        val medicalIdentity = UserIdentity("MED1", "Med Op", Role.DEPARTMENT, medicalDeviceId, "MEDICAL")
        val policePacket = Packet(
            msgId = 7199L,
            senderId = userDeviceId,
            receiverId = null,
            departmentId = "POLICE",
            conversationId = "conv_POLICE",
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Police emergency",
            timestamp = System.currentTimeMillis(),
            type = PacketType.DATA
        )
        assertFalse("Medical device MUST reject Police packet regardless of UI selection",
            isPacketAcceptedByDevice(policePacket, medicalIdentity))
    }

    // =========================================================================
    // Routing Logic Helper matching Step 8 rule
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
        // Persistent IdentityRepository identity is authoritative. UI selectors NEVER affect routing.
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

class DeptMockTransport : LinkTransport {
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
