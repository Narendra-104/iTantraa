package org.coresense.itantra.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.coresense.itantra.identity.Department
import org.coresense.itantra.identity.Role
import org.coresense.itantra.identity.UserIdentity
import java.util.UUID

class IdentityRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("itantra_identity", Context.MODE_PRIVATE)

    private val _userIdentity = MutableStateFlow<UserIdentity?>(null)
    val userIdentity: StateFlow<UserIdentity?> = _userIdentity.asStateFlow()

    init {
        ensureDeviceId()
        loadIdentity()
    }

    private fun ensureDeviceId() {
        if (!prefs.contains(KEY_DEVICE_ID)) {
            val shortId = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, "IT-$shortId").apply()
        }
    }

    private fun loadIdentity() {
        val deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        if (prefs.contains(KEY_USER_ID)) {
            val userId = prefs.getString(KEY_USER_ID, "") ?: ""
            val name = prefs.getString(KEY_NAME, "") ?: ""
            val roleStr = prefs.getString(KEY_ROLE, Role.USER.name) ?: Role.USER.name
            val role = try { Role.valueOf(roleStr) } catch (e: Exception) { Role.USER }
            val deptId = prefs.getString(KEY_DEPARTMENT_ID, null)
            
            _userIdentity.value = UserIdentity(
                userId = userId,
                name = name,
                role = role,
                deviceId = deviceId,
                departmentId = deptId
            )
        }
    }

    fun getDeviceId(): String {
        ensureDeviceId()
        return prefs.getString(KEY_DEVICE_ID, "") ?: ""
    }

    fun saveIdentity(
        name: String,
        role: Role,
        department: Department? = null,
        departmentId: String? = null
    ) {
        val safeName = name.trim().filter { !it.isISOControl() }.take(64).ifBlank { "User" }
        val userId = prefs.getString(KEY_USER_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
        val deviceId = getDeviceId()
        val rawDeptId = department?.id ?: departmentId ?: if (role == Role.DEPARTMENT) prefs.getString(KEY_DEPARTMENT_ID, null) else null
        val resolvedDeptId = rawDeptId?.trim()?.filter { !it.isISOControl() }?.take(32)
        
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_NAME, safeName)
            putString(KEY_ROLE, role.name)
            if (role == Role.DEPARTMENT && !resolvedDeptId.isNullOrBlank()) {
                putString(KEY_DEPARTMENT_ID, resolvedDeptId)
            } else {
                remove(KEY_DEPARTMENT_ID)
            }
        }.apply()
        
        loadIdentity()
    }

    fun saveProfileDetails(
        aadhar: String = "",
        email: String = "",
        mobileNumber: String = "",
        address: String = "",
        birthDate: String = "",
        bloodGroup: String = "Unknown",
        gender: String = ""
    ) {
        prefs.edit().apply {
            putString(KEY_PROFILE_AADHAR, aadhar.trim())
            putString(KEY_PROFILE_EMAIL, email.trim())
            putString(KEY_PROFILE_MOBILE, mobileNumber.trim())
            putString(KEY_PROFILE_ADDRESS, address.trim())
            putString(KEY_PROFILE_BIRTH_DATE, birthDate.trim())
            putString(KEY_PROFILE_BLOOD_GROUP, bloodGroup.trim().ifBlank { "Unknown" })
            putString(KEY_PROFILE_GENDER, gender.trim())
        }.apply()
    }

    fun getProfileDetail(key: String, defaultVal: String = ""): String {
        return prefs.getString(key, defaultVal) ?: defaultVal
    }

    fun saveAccount(password: String) {
        prefs.edit().apply {
            putString(KEY_PASSWORD, password)
            putBoolean(KEY_ACCOUNT_CREATED, true)
        }.apply()
    }

    fun isAccountCreated(): Boolean {
        return prefs.getBoolean(KEY_ACCOUNT_CREATED, false)
    }

    fun getSavedPassword(): String {
        return prefs.getString(KEY_PASSWORD, "") ?: ""
    }

    fun clearIdentity() {
        _userIdentity.value = null
    }

    fun deleteAccount() {
        prefs.edit().apply {
            remove(KEY_USER_ID)
            remove(KEY_NAME)
            remove(KEY_ROLE)
            remove(KEY_DEPARTMENT_ID)
            remove(KEY_PROFILE_AADHAR)
            remove(KEY_PROFILE_EMAIL)
            remove(KEY_PROFILE_MOBILE)
            remove(KEY_PROFILE_ADDRESS)
            remove(KEY_PROFILE_BIRTH_DATE)
            remove(KEY_PROFILE_BLOOD_GROUP)
            remove(KEY_PROFILE_GENDER)
            remove(KEY_PASSWORD)
            remove(KEY_ACCOUNT_CREATED)
        }.apply()
        _userIdentity.value = null
    }

    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_NAME = "user_name"
        const val KEY_ROLE = "user_role"
        const val KEY_DEPARTMENT_ID = "department_id"

        const val KEY_PROFILE_AADHAR = "profile_aadhar"
        const val KEY_PROFILE_EMAIL = "profile_email"
        const val KEY_PROFILE_MOBILE = "profile_mobile"
        const val KEY_PROFILE_ADDRESS = "profile_address"
        const val KEY_PROFILE_BIRTH_DATE = "profile_birth_date"
        const val KEY_PROFILE_BLOOD_GROUP = "profile_blood_group"
        const val KEY_PROFILE_GENDER = "profile_gender"

        const val KEY_PASSWORD = "user_password"
        const val KEY_ACCOUNT_CREATED = "account_created"
    }
}
