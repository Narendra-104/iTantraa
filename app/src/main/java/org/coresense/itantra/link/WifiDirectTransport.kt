package org.coresense.itantra.link

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct (P2P) implementation of LinkTransport.
 * Negotiates Group Owner, establishes TCP socket over P2P link.
 */
class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : LinkTransport, WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    override val transportType = TransportType.WIFI_DIRECT

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = p2pManager?.initialize(context, Looper.getMainLooper(), null)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    private var serverJob: Job? = null
    private var receiverJob: Job? = null
    private var isReceiverRegistered = false

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    p2pManager?.requestPeers(channel, this@WifiDirectTransport)
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        p2pManager?.requestConnectionInfo(channel, this@WifiDirectTransport)
                    } else {
                        disconnect()
                    }
                }
            }
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        val list = peers?.deviceList ?: return
        val mapped = list.map { dev ->
            PeerDevice(
                id = dev.deviceAddress,
                name = dev.deviceName.ifBlank { "Wi-Fi Direct Peer" },
                address = dev.deviceAddress,
                transportType = TransportType.WIFI_DIRECT,
                isGroupOwner = dev.isGroupOwner
            )
        }
        _discoveredPeers.value = mapped
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return

        val isGroupOwner = info.isGroupOwner
        val groupOwnerAddress = info.groupOwnerAddress?.hostAddress ?: return

        scope.launch(Dispatchers.IO) {
            try {
                if (isGroupOwner) {
                    serverSocket?.close()
                    val srv = ServerSocket(PORT)
                    serverSocket = srv
                    val sock = srv.accept()
                    val peer = PeerDevice("p2p_client", "P2P Client", sock.inetAddress.hostAddress ?: "", TransportType.WIFI_DIRECT)
                    handleConnectedSocket(sock, peer)
                } else {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(groupOwnerAddress, PORT), 8000)
                    val peer = PeerDevice("p2p_host", "P2P Group Owner", groupOwnerAddress, TransportType.WIFI_DIRECT, isGroupOwner = true)
                    handleConnectedSocket(sock, peer)
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Failed("P2P Socket failed: ${e.message}")
            }
        }
    }

    private fun handleConnectedSocket(socket: Socket, peer: PeerDevice) {
        clientSocket?.close()
        clientSocket = socket
        dataOutputStream = DataOutputStream(socket.getOutputStream())
        _connectionState.value = ConnectionState.Connected(peer)

        receiverJob?.cancel()
        receiverJob = scope.launch(Dispatchers.IO) {
            val dis = DataInputStream(socket.getInputStream())
            try {
                while (isActive && !socket.isClosed) {
                    val length = dis.readShort().toInt() and 0xFFFF
                    if (length in 1..8192) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        _incomingPackets.emit(buffer)
                    }
                }
            } catch (e: Exception) {
                if (isActive) _connectionState.value = ConnectionState.Disconnected
            } finally {
                disconnect()
            }
        }
    }

    private fun hasPermissions(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): Result<Unit> {
        val manager = p2pManager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not supported"))
        val chan = channel ?: return Result.failure(IllegalStateException("Wi-Fi Direct Channel null"))
        if (!hasPermissions()) return Result.failure(SecurityException("Wi-Fi Direct permissions not granted"))

        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            context.registerReceiver(wifiP2pReceiver, filter)
            isReceiverRegistered = true
        }

        manager.discoverPeers(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _connectionState.value = ConnectionState.Discovering
            }
            override fun onFailure(reasonCode: Int) {
                _connectionState.value = ConnectionState.Failed("Discovery error code: $reasonCode")
            }
        })
        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        val chan = channel ?: return
        p2pManager?.stopPeerDiscovery(chan, null)
        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(peer: PeerDevice): Result<Unit> {
        val manager = p2pManager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not available"))
        val chan = channel ?: return Result.failure(IllegalStateException("Wi-Fi Direct Channel null"))
        if (!hasPermissions()) return Result.failure(SecurityException("Wi-Fi Direct permissions not granted"))

        _connectionState.value = ConnectionState.Connecting(peer)

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
        }

        manager.connect(chan, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _connectionState.value = ConnectionState.Failed("P2P Connect failed: $reason")
            }
        })
        return Result.success(Unit)
    }

    override suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val stream = dataOutputStream ?: return@withContext false
        val sock = clientSocket ?: return@withContext false
        if (sock.isClosed) return@withContext false

        return@withContext try {
            synchronized(this@WifiDirectTransport) {
                stream.writeShort(data.size)
                stream.write(data)
                stream.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        receiverJob?.cancel()
        receiverJob = null
        try {
            dataOutputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (ignored: Exception) {
        } finally {
            dataOutputStream = null
            clientSocket = null
            serverSocket = null
        }

        channel?.let { chan ->
            p2pManager?.removeGroup(chan, null)
        }

        if (_connectionState.value !is ConnectionState.Disconnected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun close() {
        disconnect()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(wifiP2pReceiver)
            } catch (ignored: Exception) {
            }
            isReceiverRegistered = false
        }
    }

    companion object {
        const val PORT = 8988
    }
}
