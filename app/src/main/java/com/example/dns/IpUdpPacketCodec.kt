package com.example.dns

class ParsedUdpPacket(
    val ipVersion: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object IpUdpPacketCodec {
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53

    fun parseUdpDnsPacket(raw: ByteArray, length: Int): ParsedUdpPacket? {
        if (length < 28) return null
        val version = (raw[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4Udp(raw, length)
            6 -> parseIpv6Udp(raw, length)
            else -> null
        }
    }

    private fun parseIpv4Udp(raw: ByteArray, length: Int): ParsedUdpPacket? {
        val ihl = (raw[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((raw[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val ipTotalLen = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val packetLen = minOf(length, ipTotalLen)
        if (packetLen < ihl + 8) return null

        val srcPort = ((raw[ihl].toInt() and 0xFF) shl 8) or (raw[ihl + 1].toInt() and 0xFF)
        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        if (dstPort != DNS_PORT) return null

        val udpLen = ((raw[ihl + 4].toInt() and 0xFF) shl 8) or (raw[ihl + 5].toInt() and 0xFF)
        val payloadStart = ihl + 8
        val payloadEnd = minOf(packetLen, ihl + udpLen)
        if (payloadEnd <= payloadStart) return null

        return ParsedUdpPacket(
            ipVersion = 4,
            srcIp = raw.copyOfRange(12, 16),
            dstIp = raw.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            payload = raw.copyOfRange(payloadStart, payloadEnd),
        )
    }

    private fun parseIpv6Udp(raw: ByteArray, length: Int): ParsedUdpPacket? {
        val ipHeaderLen = 40
        if (length < ipHeaderLen + 8) return null
        if ((raw[6].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val payloadLength = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
        val packetLen = minOf(length, ipHeaderLen + payloadLength)
        if (packetLen < ipHeaderLen + 8) return null

        val srcPort =
            ((raw[ipHeaderLen].toInt() and 0xFF) shl 8) or (raw[ipHeaderLen + 1].toInt() and 0xFF)
        val dstPort =
            ((raw[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                (raw[ipHeaderLen + 3].toInt() and 0xFF)
        if (dstPort != DNS_PORT) return null

        val udpLen =
            ((raw[ipHeaderLen + 4].toInt() and 0xFF) shl 8) or
                (raw[ipHeaderLen + 5].toInt() and 0xFF)
        val payloadStart = ipHeaderLen + 8
        val payloadEnd = minOf(packetLen, ipHeaderLen + udpLen)
        if (payloadEnd <= payloadStart) return null

        return ParsedUdpPacket(
            ipVersion = 6,
            srcIp = raw.copyOfRange(8, 24),
            dstIp = raw.copyOfRange(24, 40),
            srcPort = srcPort,
            dstPort = dstPort,
            payload = raw.copyOfRange(payloadStart, payloadEnd),
        )
    }

    fun buildUdpResponsePacket(original: ParsedUdpPacket, dnsResponse: ByteArray): ByteArray {
        return if (original.ipVersion == 6) {
            buildIpv6UdpResponse(original, dnsResponse)
        } else {
            buildIpv4UdpResponse(original, dnsResponse)
        }
    }

    private fun buildIpv4UdpResponse(original: ParsedUdpPacket, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + dnsResponse.size
        val totalLen = ipHeaderLen + udpLen
        val out = ByteArray(totalLen)

        // IPv4 Header
        out[0] = 0x45 // Version 4, IHL 5
        out[1] = 0x00 // DSCP/ECN
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = 0x00
        out[5] = 0x00
        out[6] = 0x40 // Don't Fragment
        out[7] = 0x00
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        // Checksum at [10..11] filled below
        // Swap src <-> dst IP
        System.arraycopy(original.dstIp, 0, out, 12, 4)
        System.arraycopy(original.srcIp, 0, out, 16, 4)

        val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
        out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (swap src <-> dst port)
        writeUdpHeaderAndPayload(out, ipHeaderLen, original.dstPort, original.srcPort, udpLen, dnsResponse)

        // IPv4 UDP Checksum with pseudo-header
        val udpChecksum = computeIpv4UdpChecksum(out, ipHeaderLen, udpLen)
        out[ipHeaderLen + 6] = ((udpChecksum ushr 8) and 0xFF).toByte()
        out[ipHeaderLen + 7] = (udpChecksum and 0xFF).toByte()

        return out
    }

    private fun buildIpv6UdpResponse(original: ParsedUdpPacket, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLen = 40
        val udpLen = 8 + dnsResponse.size
        val out = ByteArray(ipHeaderLen + udpLen)

        // IPv6 Header
        out[0] = 0x60 // Version 6
        out[4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[5] = (udpLen and 0xFF).toByte()
        out[6] = PROTOCOL_UDP.toByte() // Next header
        out[7] = 64 // Hop limit
        // Swap src <-> dst IP
        System.arraycopy(original.dstIp, 0, out, 8, 16)
        System.arraycopy(original.srcIp, 0, out, 24, 16)

        // UDP Header (swap src <-> dst port)
        writeUdpHeaderAndPayload(out, ipHeaderLen, original.dstPort, original.srcPort, udpLen, dnsResponse)

        // IPv6 UDP Checksum (mandatory in IPv6)
        val udpChecksum = computeIpv6UdpChecksum(out, ipHeaderLen, udpLen)
        out[ipHeaderLen + 6] = ((udpChecksum ushr 8) and 0xFF).toByte()
        out[ipHeaderLen + 7] = (udpChecksum and 0xFF).toByte()

        return out
    }

    private fun writeUdpHeaderAndPayload(
        out: ByteArray,
        offset: Int,
        srcPort: Int,
        dstPort: Int,
        udpLen: Int,
        payload: ByteArray,
    ) {
        out[offset] = ((srcPort ushr 8) and 0xFF).toByte()
        out[offset + 1] = (srcPort and 0xFF).toByte()
        out[offset + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        out[offset + 3] = (dstPort and 0xFF).toByte()
        out[offset + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[offset + 5] = (udpLen and 0xFF).toByte()
        out[offset + 6] = 0x00
        out[offset + 7] = 0x00
        System.arraycopy(payload, 0, out, offset + 8, payload.size)
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeIpv4UdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0L
        // Pseudo-header: srcIp (4), dstIp (4), zero+proto (2), udpLen (2)
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += PROTOCOL_UDP.toLong()
        sum += udpLen.toLong()
        return finalizeUdpChecksum(sum, packet, udpOffset, udpLen)
    }

    private fun computeIpv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0L
        // Pseudo-header: srcIp (16), dstIp (16), udpLen (4), zero+nextHeader (4)
        for (i in 8 until 40 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += udpLen.toLong()
        sum += PROTOCOL_UDP.toLong()
        return finalizeUdpChecksum(sum, packet, udpOffset, udpLen)
    }

    private fun finalizeUdpChecksum(
        initialSum: Long,
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
    ): Int {
        var sum = initialSum
        var i = udpOffset
        val end = udpOffset + udpLen
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        return if (result == 0) 0xFFFF else result
    }
}
