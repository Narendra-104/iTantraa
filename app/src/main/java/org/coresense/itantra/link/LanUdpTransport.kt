package org.coresense.itantra.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * LAN / Local Hotspot UDP broadcast transport (third optional transport).
 * Enables instant peer-to-peer testing when phones share an offline hotspot without internet.
 */
class LanUdpTransport(
    private val scope: CoroutineScope
) : LinkTransport {

    override val transportType = TransportType.LAN_UDP

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var udpSocket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var targetPeer: PeerDevice? = null

    override fun startDiscovery(): Result<Unit> {
        _connectionState.value = ConnectionState.Discovering
        ensureSocket()
        val defaultHotspotPeer = PeerDevice(
            id = "hotspot_broadcast",
            name = "Local Hotspot Broadcast",
            address = "255.255.255.255",
            transportType = TransportType.LAN_UDP
        )
        _discoveredPeers.value = listOf(defaultHotspotPeer)
        return Result.success(Unit)
    }

    override fun stopDiscovery() {
        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(peer: PeerDevice): Result<Unit> {
        targetPeer = peer
        ensureSocket()
        _connectionState.value = ConnectionState.Connected(peer)
        return Result.success(Unit)
    }

    private fun ensureSocket() {
        if (udpSocket == null || udpSocket?.isClosed == true) {
            try {
                val sock = DatagramSocket(UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                udpSocket = sock

                listenJob?.cancel()
                listenJob = scope.launch(Dispatchers.IO) {
                    val buffer = ByteArray(4096)
                    while (isActive && !sock.isClosed) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            sock.receive(packet)
                            if (packet.length > 0) {
                                val data = buffer.copyOfRange(0, packet.length)
                                _incomingPackets.emit(data)
                            }
                        } catch (e: Exception) {
                            if (!sock.isClosed) break
                        }
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Failed("UDP Socket init error: ${e.message}")
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sock = udpSocket ?: return@withContext false
        if (sock.isClosed) return@withContext false

        return@withContext try {
            val addr = InetAddress.getByName(targetPeer?.address ?: "255.255.255.255")
            val packet = DatagramPacket(data, data.size, addr, UDP_PORT)
            sock.send(packet)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun disconnect() {
        listenJob?.cancel()
        listenJob = null
        try {
            udpSocket?.close()
        } catch (ignored: Exception) {
        } finally {
            udpSocket = null
            targetPeer = null
        }
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun close() {
        disconnect()
    }

    companion object {
        const val UDP_PORT = 8989
    }
}
