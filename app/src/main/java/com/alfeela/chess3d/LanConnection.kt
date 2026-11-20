package com.alfeela.chess3d

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Line-based TCP transport for two devices on the same Wi-Fi LAN.
 * The host listens on port 45454; the joining device enters the host LAN IP.
 */
class LanConnection(
    private val onState: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object { const val PORT = 45454 }

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var writer: BufferedWriter? = null

    fun host() {
        close()
        thread(name = "chess-lan-host") {
            var listener: ServerSocket? = null
            try {
                listener = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                server = listener
                onState("Waiting for opponent on port $PORT")
                val peer = listener.accept()
                // The listening socket is no longer needed after the single peer joins.
                runCatching { listener.close() }
                if (server === listener) server = null
                attach(peer)
                onState("Opponent connected")
                readLoop(peer)
            } catch (e: Exception) {
                runCatching { listener?.close() }
                if (server === listener) server = null
                onState("Host error: ${e.message ?: "connection failed"}")
            }
        }
    }

    fun join(hostIp: String) {
        close()
        thread(name = "chess-lan-join") {
            val peer = Socket()
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
            // An old reader must never tear down a newer connection.
            synchronized(this) {
                if (socket === peer) {
                    close()
                } else {
                    runCatching { peer.close() }
                }
            }
        }
    }

    @Synchronized fun close() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
        writer = null
    }
}
