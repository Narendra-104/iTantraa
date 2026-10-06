package org.coresense.itantra.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth RFCOMM (SPP) implementation of LinkTransport.
 * Compatible with Android minSdk 26 up to Android 15+.
 */
class BluetoothTransport(
    private val context: Context,
    private val scope: CoroutineScope
) : LinkTransport {

    override val transportType = TransportType.BLUETOOTH_RFCOMM

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyMap<String, PeerDevice>().values.toList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private val peerMap = mutableMapOf<String, PeerDevice>()

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var dataOutputStream: DataOutputStream? = null

    private var serverJob: Job? = null
    private var receiverJob: Job? = null
    private var isReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    device?.let {
                        val name = try { it.name } catch (e: SecurityException) { null } ?: "Unknown BT Device"
                        val peer = PeerDevice(
                            id = it.address,
                            name = name,
                            address = it.address,
                            transportType = TransportType.BLUETOOTH_RFCOMM,
                            rssi = rssi
                        )
                        peerMap[it.address] = peer
                        _discoveredPeers.value = peerMap.values.toList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_connectionState.value is ConnectionState.Discovering) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }
    }

    init {
        startServerListener()
    }

    private fun hasPermissions(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        if (!hasPermissions()) return

        serverJob?.cancel()
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket?.close()
                val server = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = server

                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }

                    if (socket != null) {
                        val peer = PeerDevice(
                            id = socket.remoteDevice.address,
                            name = socket.remoteDevice.name ?: "Peer Device",
                            address = socket.remoteDevice.address,
                            transportType = TransportType.BLUETOOTH_RFCOMM
                        )
                        onSocketConnected(socket, peer)
                    }
                }
            } catch (e: Exception) {
                // Server socket closed or permission missing
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): Result<Unit> {
        val adapter = bluetoothAdapter ?: return Result.failure(IllegalStateException("Bluetooth not supported on this device"))
        if (!adapter.isEnabled) return Result.failure(IllegalStateException("Bluetooth is turned off"))
        if (!hasPermissions()) return Result.failure(SecurityException("Bluetooth permissions not granted"))

        startServerListener()

        return try {
            // Load bonded devices first
            peerMap.clear()
            adapter.bondedDevices?.forEach { dev ->
                val peer = PeerDevice(
                    id = dev.address,
                    name = dev.name ?: "Bonded Device",
                    address = dev.address,
                    transportType = TransportType.BLUETOOTH_RFCOMM
                )
                peerMap[dev.address] = peer
            }
            _discoveredPeers.value = peerMap.values.toList()

            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                context.registerReceiver(bluetoothReceiver, filter)
                isReceiverRegistered = true
            }

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            adapter.startDiscovery()
            _connectionState.value = ConnectionState.Discovering
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
            if (isReceiverRegistered) {
                context.unregisterReceiver(bluetoothReceiver)
                isReceiverRegistered = false
            }
        } catch (ignored: Exception) {
        }
        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(peer: PeerDevice): Result<Unit> {
        val adapter = bluetoothAdapter ?: return Result.failure(IllegalStateException("Bluetooth not available"))
        if (!hasPermissions()) return Result.failure(SecurityException("Bluetooth permissions not granted"))
        stopDiscovery()

        _connectionState.value = ConnectionState.Connecting(peer)

        scope.launch(Dispatchers.IO) {
            try {
                val device = adapter.getRemoteDevice(peer.address)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
                onSocketConnected(socket, peer)
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Failed("BT Connect failed: ${e.message}")
            }
        }
        return Result.success(Unit)
    }

    @Synchronized
    private fun onSocketConnected(socket: BluetoothSocket, peer: PeerDevice) {
        activeSocket?.close()
        activeSocket = socket
        dataOutputStream = DataOutputStream(socket.outputStream)
        _connectionState.value = ConnectionState.Connected(peer)

        receiverJob?.cancel()
        receiverJob = scope.launch(Dispatchers.IO) {
            val dis = DataInputStream(socket.inputStream)
            try {
                while (isActive && socket.isConnected) {
                    val length = dis.readShort().toInt() and 0xFFFF
                    if (length in 1..8192) {
                        val buffer = ByteArray(length)
                        dis.readFully(buffer)
                        _incomingPackets.emit(buffer)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            } finally {
                disconnect()
            }
        }
    }

    override suspend fun send(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val stream = dataOutputStream ?: return@withContext false
        val socket = activeSocket ?: return@withContext false
        if (!socket.isConnected) return@withContext false

        return@withContext try {
            synchronized(this@BluetoothTransport) {
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
            activeSocket?.close()
        } catch (ignored: Exception) {
        } finally {
            dataOutputStream = null
            activeSocket = null
        }
        if (_connectionState.value !is ConnectionState.Disconnected) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun close() {
        stopDiscovery()
        disconnect()
        serverJob?.cancel()
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val SERVICE_NAME = "iTantraRadio"
        // Standard SPP UUID for Bluetooth Serial Port Profile
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
