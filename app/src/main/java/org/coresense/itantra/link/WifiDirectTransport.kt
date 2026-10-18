package org.coresense.itantra.link

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    companion object {
        private const val TAG = "WifiDirectTransport"
        const val PORT = 8988
    }

    override val transportType = TransportType.WIFI_DIRECT

    private val p2pManager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    private var receiverJob: Job? = null
    private var isReceiverRegistered = false
    private var isAutoConnectEnabled = false

    fun enableAutoConnect() {
        isAutoConnectEnabled = true
        val peers = _discoveredPeers.value
        if (peers.isNotEmpty() && _connectionState.value !is ConnectionState.Connected && _connectionState.value !is ConnectionState.Connecting) {
            isAutoConnectEnabled = false
            connect(peers.first())
        }
    }

    init {
        ensureChannel()
        registerReceiverIfNeeded()
    }

    @Synchronized
    private fun ensureChannel(): WifiP2pManager.Channel? {
        if (channel == null && p2pManager != null) {
            channel = p2pManager.initialize(context, Looper.getMainLooper()) {
                Log.w(TAG, "Wi-Fi Direct channel lost. Will re-initialize on demand.")
                channel = null
            }
            Log.d(TAG, "P2P Manager initialized channel: ${channel != null}")
        }
        return channel
    }

    private val wifiP2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.i(TAG, "Broadcast received: WIFI_P2P_STATE_CHANGED_ACTION: isEnabled=$isEnabled (state=$state)")
                    if (!isEnabled) {
                        _connectionState.value = ConnectionState.Failed("Wi-Fi Direct is disabled on device")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.i(TAG, "Broadcast received: WIFI_P2P_PEERS_CHANGED_ACTION -> requesting peers via requestPeers()")
                    val chan = ensureChannel()
                    if (chan == null) {
                        Log.e(TAG, "requestPeers failed: channel is null")
                        return
                    }
                    p2pManager?.requestPeers(chan, this@WifiDirectTransport)
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val chan = ensureChannel() ?: return
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    Log.i(TAG, "Broadcast received: WIFI_P2P_CONNECTION_CHANGED_ACTION: isConnected=${networkInfo?.isConnected}, state=${networkInfo?.detailedState}")
                    if (networkInfo?.isConnected == true) {
                        p2pManager?.requestConnectionInfo(chan, this@WifiDirectTransport)
                    } else if (networkInfo?.detailedState == NetworkInfo.DetailedState.DISCONNECTED ||
                        networkInfo?.detailedState == NetworkInfo.DetailedState.FAILED) {
                        if (_connectionState.value is ConnectionState.Connected) {
                            disconnect()
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    Log.d(TAG, "Broadcast received: WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: ${device?.deviceName} (${device?.deviceAddress}, status=${device?.status})")
                }
            }
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        val list = peers?.deviceList?.toList() ?: emptyList()
        Log.i(TAG, "onPeersAvailable callback: received ${list.size} peer(s) from framework")
        list.forEachIndexed { i, dev ->
            Log.i(TAG, "  Peer #$i: name='${dev.deviceName}', address='${dev.deviceAddress}', status=${dev.status}, isGroupOwner=${dev.isGroupOwner}")
        }
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
        Log.i(TAG, "Delivered ${mapped.size} peer(s) to discoveredPeers StateFlow for UI")
        if (isAutoConnectEnabled && mapped.isNotEmpty() && _connectionState.value !is ConnectionState.Connected && _connectionState.value !is ConnectionState.Connecting) {
            isAutoConnectEnabled = false
            Log.i(TAG, "Auto-connecting to first discovered Wi-Fi Direct peer: ${mapped.first().name} (${mapped.first().address})")
            connect(mapped.first())
        }
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            Log.d(TAG, "onConnectionInfoAvailable: groupFormed is false")
            return
        }

        // Avoid re-negotiating if already connected
        if (_connectionState.value is ConnectionState.Connected) {
            Log.d(TAG, "Already connected, ignoring duplicate onConnectionInfoAvailable")
            return
        }

        val isGroupOwner = info.isGroupOwner
        val groupOwnerAddress = info.groupOwnerAddress?.hostAddress ?: return
        Log.i(TAG, "P2P Group Formed! isGroupOwner=$isGroupOwner, groupOwnerAddress=$groupOwnerAddress")

        scope.launch(Dispatchers.IO) {
            try {
                if (isGroupOwner) {
                    try {
                        serverSocket?.close()
                    } catch (ignored: Exception) {}
                    val srv = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PORT))
                    }
                    serverSocket = srv
                    Log.i(TAG, "Group Owner listening on port $PORT, awaiting client...")
                    val sock = srv.accept()
                    Log.i(TAG, "Accepted client connection from ${sock.inetAddress.hostAddress}")
                    val peer = PeerDevice("p2p_client", "P2P Client", sock.inetAddress.hostAddress ?: "", TransportType.WIFI_DIRECT)
                    handleConnectedSocket(sock, peer)
                } else {
                    Log.i(TAG, "P2P Client connecting to Group Owner at $groupOwnerAddress:$PORT...")
                    var connected = false
                    var sock: Socket? = null
                    var attempts = 0
                    while (!connected && attempts < 10 && isActive) {
                        try {
                            attempts++
                            val s = Socket()
                            s.connect(InetSocketAddress(groupOwnerAddress, PORT), 4000)
                            sock = s
                            connected = true
                        } catch (e: Exception) {
                            Log.w(TAG, "Client connect attempt $attempts failed (${e.message}), retrying in 500ms...")
                            delay(500)
                        }
                    }

                    if (sock != null && connected) {
                        Log.i(TAG, "P2P Client connected to Group Owner successfully!")
                        val peer = PeerDevice("p2p_host", "P2P Group Owner", groupOwnerAddress, TransportType.WIFI_DIRECT, isGroupOwner = true)
                        handleConnectedSocket(sock, peer)
                    } else {
                        throw IllegalStateException("Failed to connect to Group Owner at $groupOwnerAddress after $attempts attempts")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "P2P Socket establishment failed: ${e.message}", e)
                _connectionState.value = ConnectionState.Failed("P2P Socket failed: ${e.message}")
            }
        }
    }

    private fun handleConnectedSocket(socket: Socket, peer: PeerDevice) {
        try {
            clientSocket?.close()
        } catch (ignored: Exception) {}
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
                Log.d(TAG, "P2P socket read loop ended: ${e.message}")
                if (isActive) _connectionState.value = ConnectionState.Disconnected
            } finally {
                disconnect()
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearby = ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "hasPermissions: NEARBY_WIFI_DEVICES=$nearby, FINE_LOCATION=$fineLocation, COARSE_LOCATION=$coarseLocation")
            return nearby || fineLocation
        }
        Log.d(TAG, "hasPermissions: FINE_LOCATION=$fineLocation, COARSE_LOCATION=$coarseLocation")
        return fineLocation || coarseLocation
    }

    private fun registerReceiverIfNeeded() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                context,
                wifiP2pReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
            Log.d(TAG, "Registered Wi-Fi P2P broadcast receiver (RECEIVER_EXPORTED)")
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): Result<Unit> {
        Log.i(TAG, "startDiscovery() triggered")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isWifiOn = wifiManager?.isWifiEnabled == true
        Log.d(TAG, "Wi-Fi hardware enabled: $isWifiOn")
        if (!isWifiOn) {
            val msg = "Wi-Fi is turned off. Please turn on Wi-Fi."
            Log.e(TAG, msg)
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        Log.d(TAG, "Location provider enabled: $gpsEnabled")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !gpsEnabled) {
            val msg = "Location must be turned ON for Wi-Fi Direct discovery."
            Log.e(TAG, msg)
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val hasPerms = hasPermissions()
        Log.d(TAG, "Permissions check result: $hasPerms")
        if (!hasPerms) {
            val msg = "Required Wi-Fi Direct permissions not granted."
            Log.e(TAG, msg)
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(SecurityException(msg))
        }

        registerReceiverIfNeeded()

        val manager = p2pManager
        if (manager == null) {
            val msg = "Wi-Fi Direct not supported on this device"
            Log.e(TAG, msg)
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val chan = ensureChannel()
        if (chan == null) {
            val msg = "Wi-Fi Direct channel unavailable"
            Log.e(TAG, msg)
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }
        Log.d(TAG, "P2P channel validated successfully: $chan")

        // Stop prior discovery first to clear BUSY state, then start discovery cleanly
        manager.stopPeerDiscovery(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery succeeded before scan, initiating discoverPeers()")
                initiateDiscoverPeers(manager, chan)
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery failed ($reason) before scan, initiating discoverPeers() anyway")
                initiateDiscoverPeers(manager, chan)
            }
        })

        return Result.success(Unit)
    }

    @SuppressLint("MissingPermission")
    private fun initiateDiscoverPeers(manager: WifiP2pManager, chan: WifiP2pManager.Channel) {
        manager.discoverPeers(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "discoverPeers() reported SUCCESS: peer discovery active")
                _connectionState.value = ConnectionState.Discovering
                manager.requestPeers(chan, this@WifiDirectTransport)
            }

            override fun onFailure(reasonCode: Int) {
                val msg = when (reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct busy. Please retry in a few seconds."
                    WifiP2pManager.ERROR -> "Discovery failed (code 0). Ensure Wi-Fi and Location are ON."
                    else -> "Discovery error code: $reasonCode"
                }
                Log.e(TAG, "discoverPeers() reported FAILURE: $msg (reasonCode=$reasonCode)")
                _connectionState.value = ConnectionState.Failed(msg)
            }
        })
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        Log.i(TAG, "stopDiscovery() called")
        val chan = ensureChannel() ?: return
        p2pManager?.stopPeerDiscovery(chan, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "stopPeerDiscovery succeeded")
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "stopPeerDiscovery failed ($reason)")
            }
        })
        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(peer: PeerDevice): Result<Unit> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager?.isWifiEnabled == false) {
            val msg = "Wi-Fi is turned off. Please turn on Wi-Fi."
            _connectionState.value = ConnectionState.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val manager = p2pManager ?: return Result.failure(IllegalStateException("Wi-Fi Direct not available"))
        val chan = ensureChannel() ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel unavailable"))
        if (!hasPermissions()) return Result.failure(SecurityException("Wi-Fi Direct permissions not granted"))

        registerReceiverIfNeeded()
        _connectionState.value = ConnectionState.Connecting(peer)

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            wps.setup = WpsInfo.PBC
        }

        manager.connect(chan, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "P2P connect initiated successfully to ${peer.address}")
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
                    WifiP2pManager.BUSY -> "Wi-Fi Direct busy. Please retry."
                    WifiP2pManager.ERROR -> "Connect failed. Ensure Wi-Fi is ON on both devices."
                    else -> "P2P Connect failed: $reason"
                }
                Log.e(TAG, "manager.connect failed: $msg ($reason)")
                _connectionState.value = ConnectionState.Failed(msg)
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
            Log.e(TAG, "P2P send failed: ${e.message}")
            false
        }
    }

    @Synchronized
    override fun disconnect() {
        Log.i(TAG, "disconnect called")
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

        ensureChannel()?.let { chan ->
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
}

