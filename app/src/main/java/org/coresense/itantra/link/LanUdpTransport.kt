package org.coresense.itantra.link

import android.content.Context
import android.net.wifi.WifiManager
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
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * LAN / Local Hotspot UDP broadcast transport (third optional transport).
 * Enables instant peer-to-peer testing when phones share an offline hotspot without internet.
 */
class LanUdpTransport(
    private val scope: CoroutineScope,
    private val context: Context? = null
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
    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        if (multicastLock == null && context != null) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifiManager?.createMulticastLock("iTantraLanUdpLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (ignored: Exception) {
        } finally {
            multicastLock = null
        }
    }

    override fun startDiscovery(): Result<Unit> {
        _connectionState.value = ConnectionState.Discovering
        val ok = ensureSocket()
        if (!ok) {
            return Result.failure(IllegalStateException("Failed to bind UDP socket on port $UDP_PORT"))
        }
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
        val ok = ensureSocket()
        return if (ok) {
            _connectionState.value = ConnectionState.Connected(peer)
            Result.success(Unit)
        } else {
            val err = "Failed to initialize UDP socket on port $UDP_PORT"
            _connectionState.value = ConnectionState.Failed(err)
            Result.failure(IllegalStateException(err))
        }
    }

    private fun ensureSocket(): Boolean {
        if (udpSocket != null && udpSocket?.isClosed == false) {
            return true
        }
        return try {
            acquireMulticastLock()
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_PORT))
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
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed("UDP Socket init error: ${e.message}")
            false
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && !broadcastList.contains(broadcast)) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        try {
            val globalBroadcast = InetAddress.getByName("255.255.255.255")
            if (!broadcastList.contains(globalBroadcast)) {
                broadcastList.add(globalBroadcast)
            }
        } catch (ignored: Exception) {
        }
        return broadcastList
    }

    override suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val sock = udpSocket ?: return@withContext false
        if (sock.isClosed) return@withContext false

        val targetAddr = targetPeer?.address ?: "255.255.255.255"
        var atLeastOneSent = false

        if (targetAddr != "255.255.255.255") {
            try {
                val addr = InetAddress.getByName(targetAddr)
                val packet = DatagramPacket(data, data.size, addr, UDP_PORT)
                sock.send(packet)
                atLeastOneSent = true
            } catch (ignored: Exception) {
            }
        } else {
            val addresses = getBroadcastAddresses()
            for (addr in addresses) {
                try {
                    val packet = DatagramPacket(data, data.size, addr, UDP_PORT)
                    sock.send(packet)
                    atLeastOneSent = true
                } catch (ignored: Exception) {
                }
            }
        }
        return@withContext atLeastOneSent
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
            releaseMulticastLock()
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
