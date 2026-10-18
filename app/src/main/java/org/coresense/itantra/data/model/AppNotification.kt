package org.coresense.itantra.data.model

import java.util.UUID

enum class NotificationCategory {
    SOS,
    CHAT,
    LINK,
    CALL,
    SYSTEM
}

data class AppNotification(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val category: NotificationCategory,
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: String? = null,
    val targetTab: String? = null
)
