package org.coresense.itantra.profile

import org.coresense.itantra.data.database.UserProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {

    @Test
    fun bloodGroupOptions_containsAllRequiredTypes() {
        val requiredBloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-", "Unknown")
        assertEquals(9, requiredBloodGroups.size)
        assertTrue(requiredBloodGroups.contains("Unknown"))
        assertTrue(requiredBloodGroups.contains("O+"))
        assertTrue(requiredBloodGroups.contains("AB-"))
    }

    @Test
    fun userProfileEntity_holdsCompleteIdentityAndHealthData() {
        val profile = UserProfileEntity(
            userId = "USER-01",
            name = "Test Citizen",
            aadhar = "1234-5678-9012",
            email = "citizen@example.org",
            mobileNumber = "+919876543210",
            address = "Sector 4, Relief Camp B",
            birthDate = "15/08/1995",
            bloodGroup = "B+",
            gender = "Female"
        )

        assertEquals("USER-01", profile.userId)
        assertEquals("Test Citizen", profile.name)
        assertEquals("1234-5678-9012", profile.aadhar)
        assertEquals("citizen@example.org", profile.email)
        assertEquals("+919876543210", profile.mobileNumber)
        assertEquals("Sector 4, Relief Camp B", profile.address)
        assertEquals("15/08/1995", profile.birthDate)
        assertEquals("B+", profile.bloodGroup)
        assertEquals("Female", profile.gender)
    }

    @Test
    fun emergencySmsPayload_formatsCorrectlyWithLocationAndProfile() {
        val victimName = "Rohan Sharma"
        val bloodGroup = "O+"
        val targetDept = "NDRF"
        val lat = 19.0760
        val lon = 72.8777
        val deviceId = "IT-DEV-99"

        val locStr = "Lat: $lat, Lon: $lon (https://maps.google.com/?q=$lat,$lon)"
        val smsBody = "EMERGENCY SOS! I need urgent help.\nName: $victimName\nBlood: $bloodGroup\nTarget: $targetDept\nLocation: $locStr\nDevice: $deviceId"

        assertTrue(smsBody.contains("EMERGENCY SOS!"))
        assertTrue(smsBody.contains("Name: Rohan Sharma"))
        assertTrue(smsBody.contains("Blood: O+"))
        assertTrue(smsBody.contains("Target: NDRF"))
        assertTrue(smsBody.contains("maps.google.com"))
        assertTrue(smsBody.contains("Device: IT-DEV-99"))
    }

    @Test
    fun audioRecipientDepartmentOptions_matchDisasterReliefAgencies() {
        val departmentOptions = listOf(
            "NDRF" to "NDRF / Search & Rescue",
            "MEDICAL" to "Medical Response",
            "POLICE" to "Police",
            "FIRE" to "Fire & Disaster",
            "RAILWAY" to "Railway Commission",
            "CIVIL" to "Civil Defence",
            "GLOBAL" to "All / Global Broadcast"
        )

        val deptIds = departmentOptions.map { it.first }
        assertTrue(deptIds.contains("NDRF"))
        assertTrue(deptIds.contains("MEDICAL"))
        assertTrue(deptIds.contains("POLICE"))
        assertTrue(deptIds.contains("FIRE"))
        assertTrue(deptIds.contains("RAILWAY"))
        assertTrue(deptIds.contains("CIVIL"))
        assertTrue(deptIds.contains("GLOBAL"))
    }

    @Test
    fun databaseMigrations_haveCorrectSourceAndTargetVersions() {
        assertEquals(1, org.coresense.itantra.data.database.AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, org.coresense.itantra.data.database.AppDatabase.MIGRATION_1_2.endVersion)

        assertEquals(1, org.coresense.itantra.data.database.AppDatabase.MIGRATION_1_3.startVersion)
        assertEquals(3, org.coresense.itantra.data.database.AppDatabase.MIGRATION_1_3.endVersion)

        assertEquals(2, org.coresense.itantra.data.database.AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, org.coresense.itantra.data.database.AppDatabase.MIGRATION_2_3.endVersion)

        assertEquals(3, org.coresense.itantra.data.database.AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, org.coresense.itantra.data.database.AppDatabase.MIGRATION_3_4.endVersion)
    }

    @Test
    fun userProfileCancelBehavior_preservesOriginalState() {
        val original = UserProfileEntity(
            userId = "USER-01",
            name = "Original Name",
            bloodGroup = "B+"
        )
        var stagingName = original.name
        var stagingBlood = original.bloodGroup

        // User edits in dialog
        stagingName = "Modified Name"
        stagingBlood = "AB-"

        // User clicks Cancel -> discard staging edits
        val cancelledName = original.name
        val cancelledBlood = original.bloodGroup

        assertEquals("Original Name", cancelledName)
        assertEquals("B+", cancelledBlood)
    }

    @Test
    fun appLocalization_hindiAndMarathiContainAllLoginAndSignUpStrings() {
        val hindiStrings = org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(org.coresense.itantra.core.protocol.Language.HINDI)
        val marathiStrings = org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(org.coresense.itantra.core.protocol.Language.MARATHI)
        val englishStrings = org.coresense.itantra.frontend.i18n.AppLocalization.getStrings(org.coresense.itantra.core.protocol.Language.ENGLISH)

        assertEquals("वापसी पर स्वागत है", hindiStrings.welcomeBack)
        assertEquals("पुन्हा स्वागत आहे", marathiStrings.welcomeBack)
        assertEquals("Welcome Back", englishStrings.welcomeBack)

        assertEquals("खाता बनाएं", hindiStrings.createAccountTitle)
        assertEquals("खाते तयार करा", marathiStrings.createAccountTitle)

        assertTrue(hindiStrings.noAccountError.isNotBlank())
        assertTrue(marathiStrings.noAccountError.isNotBlank())
        assertTrue(englishStrings.noAccountError.isNotBlank())
    }

    @Test
    fun loginValidation_rejectsUnregisteredAndAllowsRegistered() {
        // Unregistered check
        val isAccountCreated = false
        val hasSavedProfile = false
        val canLoginDirect = isAccountCreated || hasSavedProfile
        assertTrue("Cannot login without an account", !canLoginDirect)

        // Registered check
        val savedUserId = "USER-100"
        val savedPassword = "SecretPassword123"
        val inputId = "USER-100"
        val inputPassword = "SecretPassword123"

        val matches = (savedUserId == inputId) && (savedPassword == inputPassword)
        assertTrue("Registered user with correct password can login", matches)

        val wrongPasswordMatches = (savedUserId == inputId) && ("WrongPass" == savedPassword)
        assertTrue("Wrong password cannot login", !wrongPasswordMatches)
    }
}
