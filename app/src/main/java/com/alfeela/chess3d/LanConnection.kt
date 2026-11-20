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
 * Small line-based TCP transport for devices on the same Wi-Fi LAN.
 * The host listens on port 45454; the joining device enters the host's LAN IP.
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
            try {
                val listener = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                server = listener
                onState("Waiting for opponent on port $PORT")
                val peer = listener.accept()
                attach(peer)
                onState("Opponent connected")
                readLoop(peer)
            } catch (e: Exception) {
                onState("Host error: ${e.message ?: "connection failed"}")
            }
        }
    }

    fun join(hostIp: String) {
        close()
        thread(name = "chess-lan-join") {
            try {
                val peer = Socket()
                peer.tcpNoDelay = true
                peer.keepAlive = true
                peer.connect(InetSocketAddress(hostIp.trim(), PORT), 10_000)
                attach(peer)
                onState("Connected to $hostIp")
                readLoop(peer)
            } catch (e: Exception) {
                onState("Join error: ${e.message ?: "connection failed"}")
            }
        }
    }

    @Synchronized fun send(message: String) {
        try {
            writer?.apply { write(message.replace("\n", " ")); newLine(); flush() }
        } catch (e: Exception) { onState("Send failed: ${e.message}") }
    }

    private fun attach(peer: Socket) {
        peer.tcpNoDelay = true
        peer.keepAlive = true
        socket = peer
        writer = BufferedWriter(OutputStreamWriter(peer.getOutputStream(), Charsets.UTF_8))
    }

    private fun readLoop(peer: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(peer.getInputStream(), Charsets.UTF_8))
            while (true) onMessage(reader.readLine() ?: break)
        } catch (e: Exception) {
            onState("Connection closed")
        } finally { close() }
    }

    @Synchronized fun close() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        socket = null; server = null; writer = null
    }
}
