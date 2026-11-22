package com.alfeela.chess3d

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Local Wi-Fi transport. Rooms are discovered by UDP broadcast (45455),
 * then the actual chess session uses TCP (45454). No IP entry is required.
 */
class LanConnection(
    context: Context,
    private val onState: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    private val appContext = context.applicationContext

    /** Keep local-game sockets on Wi-Fi even when Android prefers mobile data
     * because the hotspot has no internet access. */
    @Suppress("DEPRECATION")
    private fun activeWifiNetwork(): Network? {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Prefer Android's active Wi-Fi network. allNetworks ordering is
            // unspecified and can return a secondary or stale Wi-Fi interface.
            val active = manager.activeNetwork
            if (active != null &&
                manager.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            ) return active

            manager.allNetworks
                .filter { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                .maxByOrNull { network ->
                    val caps = manager.getNetworkCapabilities(network)
                    (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == true) 1 else 0) +
                    (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) 1 else 0)
                }
        } catch (_: Exception) { null }
    }

    private fun bindToWifi(socket: DatagramSocket) {
        runCatching { activeWifiNetwork()?.bindSocket(socket) }
    }

    private fun bindToWifi(socket: Socket) {
        runCatching { activeWifiNetwork()?.bindSocket(socket) }
    }

    companion object {
        const val PORT = 45454
        private const val DISCOVERY_PORT = 45455
        private const val DISCOVERY_REQUEST = "3DCHESS_DISCOVER_V1"
        private const val DISCOVERY_RESPONSE = "3DCHESS_ROOM_V1"
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var discoverySocket: DatagramSocket? = null

    fun host(bindAddress: String? = null) {
        close()
        thread(name = "chess-lan-host") {
            var listener: ServerSocket? = null
            try {
                listener = ServerSocket().apply {
                    reuseAddress = true
                    // Bind the game listener to the phone's actual Wi-Fi/Hotspot
                    // address so the accepted TCP socket stays on that LAN route.
                    val wifiAddress = bindAddress?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { java.net.InetAddress.getByName(it) }
                    if (wifiAddress != null) bind(InetSocketAddress(wifiAddress, PORT))
                    else bind(InetSocketAddress(PORT))
                }
                server = listener
                startDiscoveryResponder()
                onState("Waiting for opponent on port $PORT")
                val peer = listener.accept()
                runCatching { listener.close() }
                if (server === listener) server = null
                attach(peer)
                onState("Opponent connected")
                readLoop(peer)
            } catch (e: Exception) {
                runCatching { listener?.close() }
                if (server === listener) server = null
                if (!Thread.currentThread().isInterrupted) {
                    onState("Host error: ${e.message ?: "connection failed"}")
                }
            }
        }
    }

    private fun startDiscoveryResponder() {
        thread(name = "chess-lan-discovery-host") {
            var udp: DatagramSocket? = null
            try {
                val responder = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }
                udp = responder
                bindToWifi(responder)
                discoverySocket = responder
                val buffer = ByteArray(128)
                while (!responder.isClosed) {
                    try {
                        val request = DatagramPacket(buffer, buffer.size)
                        responder.receive(request)
                        val message = String(request.data, request.offset, request.length, Charsets.UTF_8)
                        if (message == DISCOVERY_REQUEST) {
                            val responseBytes = DISCOVERY_RESPONSE.toByteArray(Charsets.UTF_8)
                            responder.send(DatagramPacket(responseBytes, responseBytes.size, request.address, request.port))
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Check whether the room has been closed.
                    }
                }
            } catch (_: Exception) {
                // Discovery is best-effort; the TCP listener remains available.
            } finally {
                if (discoverySocket === udp) discoverySocket = null
                runCatching { udp?.close() }
            }
        }
    }

    /** Broadcasts a room search on the currently connected local network. */
    fun discoverAndJoin() {
        close()
        thread(name = "chess-lan-discovery-client") {
            var udp: DatagramSocket? = null
            try {
                val discovery = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 850
                }
                udp = discovery
                bindToWifi(discovery)
                discoverySocket = discovery
                val requestBytes = DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
                val broadcasts = discoverBroadcastAddresses()
                val startedAt = System.currentTimeMillis()
                onState("Searching for nearby rooms")
                var hostAddress: String? = null

                while (!discovery.isClosed && System.currentTimeMillis() - startedAt < 20_000 && hostAddress == null) {
                    broadcasts.forEach { address ->
                        runCatching {
                            discovery.send(DatagramPacket(requestBytes, requestBytes.size, address, DISCOVERY_PORT))
                        }
                    }
                    try {
                        val buffer = ByteArray(128)
                        val reply = DatagramPacket(buffer, buffer.size)
                        discovery.receive(reply)
                        val message = String(reply.data, reply.offset, reply.length, Charsets.UTF_8)
                        if (message == DISCOVERY_RESPONSE) hostAddress = reply.address.hostAddress
                    } catch (_: java.net.SocketTimeoutException) {
                        // Repeat discovery until a room is found or the search expires.
                    }
                }

                if (hostAddress == null) {
                    onState("Discovery timeout: no room found")
                    return@thread
                }

                runCatching { discovery.close() }
                if (discoverySocket === discovery) discoverySocket = null
                onState("Found room at $hostAddress • connecting")
                val wifi = activeWifiNetwork()
                val peer = if (wifi != null) wifi.socketFactory.createSocket() else Socket()
                try {
                    peer.tcpNoDelay = true
                    peer.keepAlive = true
                    // Pin this TCP session to Wi-Fi, not Android's default route.
                    peer.connect(InetSocketAddress(hostAddress, PORT), 10_000)
                    attach(peer)
                    onState("Connected to $hostAddress")
                    readLoop(peer)
                } catch (e: Exception) {
                    runCatching { peer.close() }
                    onState("Join error: ${e.message ?: "connection failed"}")
                }
            } catch (e: Exception) {
                onState("Join error: ${e.message ?: "Wi-Fi discovery failed"}")
            } finally {
                if (discoverySocket === udp) discoverySocket = null
                runCatching { udp?.close() }
            }
        }
    }

    private fun discoverBroadcastAddresses(): List<java.net.InetAddress> {
        val result = linkedSetOf<java.net.InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .forEach { network ->
                    network.interfaceAddresses.forEach { info ->
                        val broadcast = info.broadcast
                        if (broadcast != null && info.address is Inet4Address) result.add(broadcast)
                    }
                }
        } catch (_: Exception) {
            // Fall back to the limited broadcast address below.
        }
        runCatching { result.add(java.net.InetAddress.getByName("255.255.255.255")) }
        return result.toList()
    }

    fun join(hostIp: String) {
        close()
        thread(name = "chess-lan-join") {
            val wifi = activeWifiNetwork()
            val peer = if (wifi != null) wifi.socketFactory.createSocket() else Socket()
            try {
                peer.tcpNoDelay = true
                peer.keepAlive = true
                peer.connect(InetSocketAddress(hostIp.trim(), PORT), 10_000)
                attach(peer)
                onState("Connected to $hostIp")
                readLoop(peer)
            } catch (e: Exception) {
                runCatching { peer.close() }
                onState("Join error: ${e.message ?: "connection failed"}")
            }
        }
    }

    @Synchronized fun send(message: String) {
        try {
            val activeWriter = writer ?: run {
                onState("Send failed: no active Wi-Fi connection")
                return
            }
            activeWriter.write(message.replace("\n", " "))
            activeWriter.newLine()
            activeWriter.flush()
        } catch (e: Exception) {
            onState("Send failed: ${e.message ?: "network error"}")
            close()
        }
    }

    @Synchronized private fun attach(peer: Socket) {
        peer.tcpNoDelay = true
        peer.keepAlive = true
        socket = peer
        writer = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
    }

    private fun readLoop(peer: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.length <= 256) onMessage(line)
            }
            onState("Connection closed by opponent")
        } catch (e: Exception) {
            if (!peer.isClosed) onState("Connection lost: ${e.message ?: "network error"}")
        } finally {
            synchronized(this) {
                if (socket === peer) close() else runCatching { peer.close() }
            }
        }
    }

    @Synchronized fun close() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
        runCatching { discoverySocket?.close() }
        socket = null
        server = null
        discoverySocket = null
        writer = null
    }
}
