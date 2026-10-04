package org.coresense.itantra.reliability

sealed class DeliveryState {
    object Queued : DeliveryState()
    object Sending : DeliveryState()
    data class SentAwaitingAck(val sentTimestamp: Long) : DeliveryState()
    data class Delivered(val rttMs: Long) : DeliveryState()
    data class Failed(val reason: String) : DeliveryState()

    val name: String
        get() = when (this) {
            is Queued -> "QUEUED"
            is Sending -> "SENDING"
            is SentAwaitingAck -> "SENT"
            is Delivered -> "DELIVERED"
            is Failed -> "FAILED"
        }
}
