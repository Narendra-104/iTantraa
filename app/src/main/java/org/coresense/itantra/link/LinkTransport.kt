package org.coresense.itantra.link

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Discovering : ConnectionState()
    data class Connecting(val peer: PeerDevice) : ConnectionState()
    data class Connected(val peer: PeerDevice) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

interface LinkTransport {
    val transportType: TransportType
    val connectionState: StateFlow<ConnectionState>
    val discoveredPeers: StateFlow<List<PeerDevice>>
    val incomingPackets: SharedFlow<ByteArray>

    fun startDiscovery(): Result<Unit>
    fun stopDiscovery()
    fun connect(peer: PeerDevice): Result<Unit>
    fun disconnect()
    suspend fun send(data: ByteArray): Boolean
    fun close()
}
