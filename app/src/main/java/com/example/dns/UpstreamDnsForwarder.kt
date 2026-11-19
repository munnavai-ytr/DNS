package com.example.dns

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UpstreamDnsForwarder(
    private val protectSocket: (DatagramSocket) -> Boolean,
) {
    private val primaryDns: InetAddress =
        InetAddress.getByAddress(byteArrayOf(1, 1, 1, 3)) // Cloudflare Family 1.1.1.3
    private val fallbackDns: InetAddress =
        InetAddress.getByAddress(byteArrayOf(1, 0, 0, 3)) // Cloudflare Family 1.0.0.3

    fun forward(queryBytes: ByteArray): ByteArray? {
        return queryUpstream(queryBytes, primaryDns) ?: queryUpstream(queryBytes, fallbackDns)
    }

    private fun queryUpstream(queryBytes: ByteArray, server: InetAddress): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protectSocket(socket)) {
                return null
            }
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val requestPacket = DatagramPacket(queryBytes, queryBytes.size, server, DNS_PORT)
            socket.send(requestPacket)

            val responseBuffer = ByteArray(MAX_DNS_UDP_SIZE)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            if (responsePacket.length > 0) {
                responseBuffer.copyOf(responsePacket.length)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    companion object {
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 2500
        private const val MAX_DNS_UDP_SIZE = 4096
    }
}
