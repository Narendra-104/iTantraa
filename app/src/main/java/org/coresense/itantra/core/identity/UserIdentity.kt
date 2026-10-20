package org.coresense.itantra.core.identity

data class UserIdentity(
    val userId: String,
    val name: String,
    val role: Role,
    val deviceId: String,
    val departmentId: String? = null
)
