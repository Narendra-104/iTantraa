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

    fun saveIdentity(name: String, role: Role, department: Department? = null) {
        val userId = prefs.getString(KEY_USER_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
        val deviceId = getDeviceId()
        
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_NAME, name)
            putString(KEY_ROLE, role.name)
            if (role == Role.DEPARTMENT && department != null) {
                putString(KEY_DEPARTMENT_ID, department.id)
            } else {
                remove(KEY_DEPARTMENT_ID)
            }
        }.apply()
        
        loadIdentity()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NAME = "user_name"
        private const val KEY_ROLE = "user_role"
        private const val KEY_DEPARTMENT_ID = "department_id"
    }
}
