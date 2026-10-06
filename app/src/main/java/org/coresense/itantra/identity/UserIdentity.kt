package org.coresense.itantra.identity

data class UserIdentity(
    val userId: String,
    val name: String,
    val role: Role,
    val deviceId: String,
    val departmentId: String? = null
)
