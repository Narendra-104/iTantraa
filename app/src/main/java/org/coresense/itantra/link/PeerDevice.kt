package org.coresense.itantra.link

enum class TransportType(val displayName: String) {
    WIFI_DIRECT("Wi-Fi Direct (P2P)"),
    BLUETOOTH_RFCOMM("Bluetooth (RFCOMM / SPP)"),
    LAN_UDP("Local Hotspot (UDP Broadcast)")
}

data class PeerDevice(
    val id: String,
    val name: String,
    val address: String,
    val transportType: TransportType,
    val isGroupOwner: Boolean = false,
    val rssi: Int = 0
)
