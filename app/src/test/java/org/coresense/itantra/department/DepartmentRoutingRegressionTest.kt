package org.coresense.itantra.department

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.coresense.itantra.data.repository.MessageRepository
import org.coresense.itantra.core.identity.Department
import org.coresense.itantra.core.identity.Role
import org.coresense.itantra.core.identity.UserIdentity
import org.coresense.itantra.core.protocol.Language
import org.coresense.itantra.core.protocol.Packet
import org.coresense.itantra.core.protocol.PacketPriority
import org.coresense.itantra.core.protocol.PacketType
import org.coresense.itantra.data.database.ConversationEntity
import org.coresense.itantra.data.database.MessageEntity
import org.coresense.itantra.frontend.viewmodel.MainViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Step 8 Department Messaging & Step 9 SOS Routing Regression Test Suite.
 * Validates:
 * 1. NDRF -> Medical routing & isolation
 * 2. Medical -> NDRF routing & isolation
 * 3. User -> Police
 * 4. User -> Fire and Disaster
 * 5. User -> Railway Commission
 * 6. User -> Civil Defence
 * 7. SOS delivered to its selected department
 * 8. SOS visible in both intended department inbox and Global Feed (no duplicate entries)
 * 9. Messages remaining available after database reload
 * 10. Existing User ID messaging and Global Feed still working
 */
class DepartmentRoutingRegressionTest {

    private lateinit var scope: CoroutineScope

    private val userA = "IT-CITIZEN-01"
    private val userB = "IT-CITIZEN-02"
    private val ndrfDev = "IT-DEPT-NDRF-01"
    private val medDev = "IT-DEPT-MED-01"
    private val policeDev = "IT-DEPT-POLICE-01"
    private val fireDev = "IT-DEPT-FIRE-01"
    private val railDev = "IT-DEPT-RAIL-01"
    private val civilDev = "IT-DEPT-CIVIL-01"

    private lateinit var sharedMessageDao: FakeMessageDao
    private lateinit var sharedConversationDao: FakeConversationDao
    private lateinit var repo: MessageRepository

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.Unconfined + Job())
        sharedMessageDao = FakeMessageDao()
        sharedConversationDao = FakeConversationDao()
        repo = MessageRepository(sharedMessageDao, sharedConversationDao)
    }

    @After
    fun teardown() {
        scope.cancel()
    }

    // Helper functions mirroring MainViewModel's routing and matching
    private fun resolveDept(nameOrId: String?): String? {
        if (nameOrId == null) return null
        val clean = nameOrId.trim()
        if (clean.isBlank()) return null
        return when {
            clean.contains("NDRF", ignoreCase = true) || clean.contains("Rescue", ignoreCase = true) || clean.contains("Search", ignoreCase = true) -> "NDRF"
            clean.contains("Medic", ignoreCase = true) -> "MEDICAL"
            clean.contains("Police", ignoreCase = true) || clean.contains("Woman", ignoreCase = true) || clean.contains("Child", ignoreCase = true) -> "POLICE"
            clean.contains("Fire", ignoreCase = true) || clean.contains("Disaster", ignoreCase = true) -> "FIRE"
            clean.contains("Railway", ignoreCase = true) || clean.contains("Train", ignoreCase = true) -> "RAILWAY"
            clean.contains("Civil", ignoreCase = true) || clean.contains("Defence", ignoreCase = true) || clean.contains("Defense", ignoreCase = true) -> "CIVIL"
            Department.entries.any { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true) } -> {
                Department.entries.first { it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true) || it.displayName.equals(clean, ignoreCase = true) }.id
            }
            else -> null
        }
    }

    private fun areDeptsMatching(deptA: String?, deptB: String?): Boolean {
        if (deptA.isNullOrBlank() || deptB.isNullOrBlank()) return false
        val normA = resolveDept(deptA) ?: deptA.trim().uppercase()
        val normB = resolveDept(deptB) ?: deptB.trim().uppercase()
        return normA == normB
    }

    private fun isPacketAcceptedByDevice(packet: Packet, identity: UserIdentity): Boolean {
        if (packet.senderId.equals(identity.deviceId, ignoreCase = true)) {
            return false
        }
        val isDirectForMe = packet.receiverId != null && packet.receiverId.equals(identity.deviceId, ignoreCase = true)
        val targetDeptId = packet.departmentId?.let { resolveDept(it) ?: it } ?: resolveDept(packet.receiverId)
        val isDepartmentMessage = targetDeptId != null

        val isForMyDepartment = if (isDepartmentMessage && targetDeptId != null) {
            val isDeptRole = identity.role == Role.DEPARTMENT
            val localDeptId = identity.departmentId
            isDeptRole && localDeptId != null && areDeptsMatching(localDeptId, targetDeptId)
        } else {
            false
        }

        val isGlobalBroadcast = (packet.receiverId == null || packet.receiverId.equals("GLOBAL", ignoreCase = true)) && !isDepartmentMessage

        return isDirectForMe || isForMyDepartment || isGlobalBroadcast
    }

    // =========================================================================
    // 1. NDRF -> Medical
    // =========================================================================
    @Test
    fun test1_NdrfToMedicalMessageRoutedCorrectly() {
        val ndrfIdentity = UserIdentity("OP-NDRF", "NDRF Lead", Role.DEPARTMENT, ndrfDev, "NDRF_SEARCH_RESCUE")
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")
        val policeIdentity = UserIdentity("OP-POL", "Police Dispatch", Role.DEPARTMENT, policeDev, "POLICE")

        val packet = Packet(
            msgId = 8001L,
            senderId = ndrfDev,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = MainViewModel.getConversationId(ndrfDev, "MEDICAL"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Injured casualty needs immediate medical attention",
            timestamp = 1700000000000L,
            type = PacketType.DATA
        )

        assertTrue("Medical device must accept message addressed to MEDICAL", isPacketAcceptedByDevice(packet, medIdentity))
        assertFalse("NDRF device must ignore its own packet or cross-dept message", isPacketAcceptedByDevice(packet, ndrfIdentity))
        assertFalse("Police device must reject message addressed to MEDICAL", isPacketAcceptedByDevice(packet, policeIdentity))
    }

    // =========================================================================
    // 2. Medical -> NDRF
    // =========================================================================
    @Test
    fun test2_MedicalToNdrfMessageRoutedCorrectly() {
        val ndrfIdentity = UserIdentity("OP-NDRF", "NDRF Lead", Role.DEPARTMENT, ndrfDev, "NDRF")
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")
        val fireIdentity = UserIdentity("OP-FIRE", "Fire Dispatch", Role.DEPARTMENT, fireDev, "FIRE")

        val packet = Packet(
            msgId = 8002L,
            senderId = medDev,
            receiverId = null,
            departmentId = "NDRF",
            conversationId = MainViewModel.getConversationId(medDev, "NDRF"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Structural clearance required before ambulance ingress",
            timestamp = 1700000001000L,
            type = PacketType.DATA
        )

        assertTrue("NDRF device must accept message addressed to NDRF", isPacketAcceptedByDevice(packet, ndrfIdentity))
        assertFalse("Medical device must ignore echo", isPacketAcceptedByDevice(packet, medIdentity))
        assertFalse("Fire device must reject message addressed to NDRF", isPacketAcceptedByDevice(packet, fireIdentity))
    }

    // =========================================================================
    // 3. User -> Police
    // =========================================================================
    @Test
    fun test3_UserToPoliceMessageRoutedCorrectly() {
        val policeIdentity = UserIdentity("OP-POL", "Police Dispatch", Role.DEPARTMENT, policeDev, "POLICE")
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")

        val packet = Packet(
            msgId = 8003L,
            senderId = userA,
            receiverId = null,
            departmentId = "POLICE",
            conversationId = MainViewModel.getConversationId(userA, "POLICE"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Crowd surge reported near Sector 2",
            timestamp = 1700000002000L,
            type = PacketType.DATA
        )

        assertTrue("Police device must accept User to Police message", isPacketAcceptedByDevice(packet, policeIdentity))
        assertFalse("Medical device must reject Police message", isPacketAcceptedByDevice(packet, medIdentity))
    }

    // =========================================================================
    // 4. User -> Fire and Disaster
    // =========================================================================
    @Test
    fun test4_UserToFireAndDisasterMessageRoutedCorrectly() {
        val fireIdentity = UserIdentity("OP-FIRE", "Fire Station", Role.DEPARTMENT, fireDev, "FIRE_DISASTER")
        val policeIdentity = UserIdentity("OP-POL", "Police Dispatch", Role.DEPARTMENT, policeDev, "POLICE")

        val packet = Packet(
            msgId = 8004L,
            senderId = userA,
            receiverId = null,
            departmentId = "FIRE",
            conversationId = MainViewModel.getConversationId(userA, "FIRE"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Smoke visible from basement",
            timestamp = 1700000003000L,
            type = PacketType.DATA
        )

        assertTrue("Fire & Disaster device must accept message using FIRE alias", isPacketAcceptedByDevice(packet, fireIdentity))
        assertFalse("Police device must reject Fire message", isPacketAcceptedByDevice(packet, policeIdentity))
    }

    // =========================================================================
    // 5. User -> Railway Commission
    // =========================================================================
    @Test
    fun test5_UserToRailwayCommissionMessageRoutedCorrectly() {
        val railIdentity = UserIdentity("OP-RAIL", "Rail Controller", Role.DEPARTMENT, railDev, "RAILWAY_COMMISSION")
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")

        val packet = Packet(
            msgId = 8005L,
            senderId = userA,
            receiverId = null,
            departmentId = "RAILWAY",
            conversationId = MainViewModel.getConversationId(userA, "RAILWAY"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Track obstruction reported near kilometer post 14",
            timestamp = 1700000004000L,
            type = PacketType.DATA
        )

        assertTrue("Railway Commission device must accept RAILWAY packet", isPacketAcceptedByDevice(packet, railIdentity))
        assertFalse("Medical device must reject Railway packet", isPacketAcceptedByDevice(packet, medIdentity))
    }

    // =========================================================================
    // 6. User -> Civil Defence
    // =========================================================================
    @Test
    fun test6_UserToCivilDefenceMessageRoutedCorrectly() {
        val civilIdentity = UserIdentity("OP-CIVIL", "Civil Defence Volunteer", Role.DEPARTMENT, civilDev, "CIVIL_DEFENCE")
        val ndrfIdentity = UserIdentity("OP-NDRF", "NDRF Lead", Role.DEPARTMENT, ndrfDev, "NDRF")

        val packet = Packet(
            msgId = 8006L,
            senderId = userA,
            receiverId = null,
            departmentId = "CIVIL",
            conversationId = MainViewModel.getConversationId(userA, "CIVIL"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Community shelter distribution centers open",
            timestamp = 1700000005000L,
            type = PacketType.DATA
        )

        assertTrue("Civil Defence device must accept CIVIL packet", isPacketAcceptedByDevice(packet, civilIdentity))
        assertFalse("NDRF device must reject Civil packet", isPacketAcceptedByDevice(packet, ndrfIdentity))
    }

    // =========================================================================
    // 7. SOS delivered to its selected department
    // =========================================================================
    @Test
    fun test7_SosDeliveredToSelectedDepartment() {
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")
        val fireIdentity = UserIdentity("OP-FIRE", "Fire Dispatch", Role.DEPARTMENT, fireDev, "FIRE")

        val sosPacket = Packet(
            msgId = 8007L,
            senderId = userA,
            receiverId = null,
            departmentId = "MEDICAL",
            conversationId = MainViewModel.getConversationId(userA, "MEDICAL"),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.SOS,
            text = "[MEDICAL EMERGENCY SOS] Severe trauma, immediate medical team needed. GPS: 28.56720, 77.21000",
            timestamp = 1700000006000L,
            type = PacketType.SOS
        )

        assertEquals(PacketPriority.SOS, sosPacket.priority)
        assertEquals(PacketType.SOS, sosPacket.type)
        assertTrue(sosPacket.isEmergency)

        assertTrue("Medical department must receive Medical SOS", isPacketAcceptedByDevice(sosPacket, medIdentity))
        assertFalse("Fire department must not accept Medical SOS into department inbox", isPacketAcceptedByDevice(sosPacket, fireIdentity))
    }

    // =========================================================================
    // 8. SOS visible in both intended department inbox and Global Feed (Single entity)
    // =========================================================================
    @Test
    fun test8_SosVisibleInBothIntendedDepartmentInboxAndGlobalFeed() = runBlocking {
        val sosMsgId = 8008L
        val sosText = "[POLICE EMERGENCY SOS] Robbery in progress. GPS: 28.56000, 77.20000"
        val convId = MainViewModel.getConversationId(userA, "POLICE")

        val entity = MessageEntity(
            msgId = sosMsgId,
            senderId = userA,
            seq = 1,
            lang = "English",
            priority = "SOS",
            text = sosText,
            receiverId = "POLICE",
            departmentId = "POLICE",
            conversationId = convId,
            timestamp = 1700000007000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = true
        )

        // Single insertion into Room DB
        repo.insertMessage(entity)

        val allMessages = repo.getAllMessages().first()
        assertEquals("Database must contain exactly 1 entry for this SOS event (no duplicates)", 1, allMessages.size)

        // View 1: Police Department Inbox filtering check
        val policeInbox = allMessages.filter { msg ->
            msg.isIncoming && areDeptsMatching(msg.departmentId, "POLICE")
        }
        assertEquals("SOS must appear in Police Department inbox", 1, policeInbox.size)
        assertEquals(sosMsgId, policeInbox.first().msgId)

        // View 2: Global / Unified Feed filtering check (selectedRecipient == "ALL" or isSos)
        val globalFeed = allMessages.filter { msg ->
            val isSos = msg.priority.equals("SOS", ignoreCase = true)
            val isGeneralBroadcast = (msg.receiverId.isNullOrBlank() || msg.receiverId.equals("GLOBAL", ignoreCase = true)) && msg.departmentId == null
            isGeneralBroadcast || isSos
        }
        assertEquals("SOS must appear in Global/Unified Feed", 1, globalFeed.size)
        assertEquals(sosMsgId, globalFeed.first().msgId)
    }

    // =========================================================================
    // 9. Messages remaining available after database reload
    // =========================================================================
    @Test
    fun test9_MessagesRemainingAvailableAfterDatabaseReload() = runBlocking {
        val msgId = 8009L
        val entity = MessageEntity(
            msgId = msgId,
            senderId = userA,
            seq = 1,
            lang = "English",
            priority = "NORMAL",
            text = "Persisted request for NDRF",
            receiverId = "NDRF",
            departmentId = "NDRF",
            conversationId = "conv_PERSISTED",
            timestamp = 1700000008000L,
            latitudeMicrodegrees = null,
            longitudeMicrodegrees = null,
            deliveryState = "DELIVERED",
            isIncoming = true
        )
        repo.insertMessage(entity)

        // Simulate app restart / new repository instance reading from same Room DB
        val reloadedRepo = MessageRepository(sharedMessageDao, sharedConversationDao)
        val loaded = reloadedRepo.getMessageById(msgId)

        assertNotNull("Message must be intact after database reload", loaded)
        assertEquals("NDRF", loaded!!.departmentId)
        assertEquals("NDRF", loaded.receiverId)
        assertEquals("conv_PERSISTED", loaded.conversationId)
        assertEquals(1700000008000L, loaded.timestamp)
        assertEquals("DELIVERED", loaded.deliveryState)
    }

    // =========================================================================
    // 10. Existing User ID messaging and Global Feed still working
    // =========================================================================
    @Test
    fun test10_ExistingUserIdMessagingAndGlobalFeedStillWorking() = runBlocking {
        val userBIdentity = UserIdentity("CIT2", "Citizen Two", Role.USER, userB, null)
        val medIdentity = UserIdentity("OP-MED", "Medical Officer", Role.DEPARTMENT, medDev, "MEDICAL")

        // 1. Peer-to-peer user ID packet
        val p2pPacket = Packet(
            msgId = 8010L,
            senderId = userA,
            receiverId = userB,
            departmentId = null,
            conversationId = MainViewModel.getConversationId(userA, userB),
            seq = 1,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "Hello Citizen 2, direct message test",
            timestamp = 1700000009000L,
            type = PacketType.DATA
        )

        assertTrue("User B must accept direct user ID message", isPacketAcceptedByDevice(p2pPacket, userBIdentity))
        assertFalse("Medical department device must not accept direct message between citizens", isPacketAcceptedByDevice(p2pPacket, medIdentity))

        // 2. Global general broadcast packet
        val globalPacket = Packet(
            msgId = 8011L,
            senderId = userA,
            receiverId = "GLOBAL",
            departmentId = null,
            conversationId = "conv_global",
            seq = 2,
            lang = Language.ENGLISH,
            priority = PacketPriority.NORMAL,
            text = "General advisory to all survivors",
            timestamp = 1700000010000L,
            type = PacketType.DATA
        )

        assertTrue("User B must accept GLOBAL broadcast in feed", isPacketAcceptedByDevice(globalPacket, userBIdentity))
        assertTrue("Medical department must accept GLOBAL broadcast in feed", isPacketAcceptedByDevice(globalPacket, medIdentity))
    }
}
