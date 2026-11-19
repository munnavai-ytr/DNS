package com.example.dns

import java.util.Locale

data class DnsQuery(
    val transactionId: Int,
    val domain: String,
    val qtype: Int,
    val qclass: Int,
    val questionEndOffset: Int,
) {
    val qtypeLabel: String
        get() =
            when (qtype) {
                DnsWireCodec.QTYPE_A -> "A"
                DnsWireCodec.QTYPE_AAAA -> "AAAA"
                DnsWireCodec.QTYPE_HTTPS -> "HTTPS"
                DnsWireCodec.QTYPE_CNAME -> "CNAME"
                DnsWireCodec.QTYPE_TXT -> "TXT"
                DnsWireCodec.QTYPE_MX -> "MX"
                else -> "TYPE$qtype"
            }
}

object DnsWireCodec {
    const val QTYPE_A = 1
    const val QTYPE_CNAME = 5
    const val QTYPE_MX = 15
    const val QTYPE_TXT = 16
    const val QTYPE_AAAA = 28
    const val QTYPE_HTTPS = 65
    const val QCLASS_IN = 1

    private const val DNS_HEADER_SIZE = 12
    private const val BLOCKED_TTL_SECONDS = 60

    fun parseQuery(packet: ByteArray, length: Int): DnsQuery? {
        if (length < DNS_HEADER_SIZE + 5) return null

        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        // Must be a standard query (QR == 0)
        if ((flags and 0x8000) != 0) return null

        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val nameBuilder = StringBuilder(64)
        var pos = DNS_HEADER_SIZE
        var questionNameEnd = -1
        var jumped = false
        var jumpCount = 0

        while (pos < length) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) {
                    questionNameEnd = pos + 1
                }
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= length) return null
                val pointer = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (pointer >= length || jumpCount >= 8) return null
                if (!jumped) {
                    questionNameEnd = pos + 2
                }
                jumped = true
                jumpCount++
                pos = pointer
                continue
            }
            if ((len and 0xC0) != 0) return null
            pos++
            if (pos + len > length) return null

            if (nameBuilder.isNotEmpty()) {
                nameBuilder.append('.')
            }
            for (i in 0 until len) {
                nameBuilder.append((packet[pos + i].toInt() and 0xFF).toChar())
            }
            pos += len
        }

        if (questionNameEnd < 0 || questionNameEnd + 4 > length) return null
        val qtype =
            ((packet[questionNameEnd].toInt() and 0xFF) shl 8) or
                (packet[questionNameEnd + 1].toInt() and 0xFF)
        val qclass =
            ((packet[questionNameEnd + 2].toInt() and 0xFF) shl 8) or
                (packet[questionNameEnd + 3].toInt() and 0xFF)

        val domain = nameBuilder.toString().trimEnd('.').lowercase(Locale.ROOT)
        if (domain.isEmpty()) return null

        return DnsQuery(
            transactionId = txId,
            domain = domain,
            qtype = qtype,
            qclass = qclass,
            questionEndOffset = questionNameEnd + 4,
        )
    }

    /**
     * Builds an immediate DNS response for a blocked query:
     * - A (IPv4) -> 0.0.0.0
     * - AAAA (IPv6) -> ::
     * - Other record types -> NOERROR with 0 answers (instant NODATA)
     */
    fun buildBlockedResponse(request: ByteArray, query: DnsQuery): ByteArray {
        val questionLength = query.questionEndOffset - DNS_HEADER_SIZE
        val isA = query.qtype == QTYPE_A && query.qclass == QCLASS_IN
        val isAAAA = query.qtype == QTYPE_AAAA && query.qclass == QCLASS_IN
        val answerLength =
            when {
                isA -> 16
                isAAAA -> 28
                else -> 0
            }

        val response = ByteArray(DNS_HEADER_SIZE + questionLength + answerLength)

        // Transaction ID
        response[0] = request[0]
        response[1] = request[1]
        // Flags: QR=1, preserve Opcode and RD, set RA=1, RCODE=0 (NOERROR)
        response[2] = (0x80 or (request[2].toInt() and 0x79)).toByte()
        response[3] = 0x80.toByte()
        // QDCOUNT = 1
        response[4] = 0x00
        response[5] = 0x01
        // ANCOUNT = 1 for A/AAAA else 0
        response[6] = 0x00
        response[7] = if (answerLength > 0) 0x01 else 0x00
        // NSCOUNT = 0, ARCOUNT = 0 (already 0)

        // Copy Question section verbatim
        System.arraycopy(request, DNS_HEADER_SIZE, response, DNS_HEADER_SIZE, questionLength)

        if (answerLength > 0) {
            val offset = DNS_HEADER_SIZE + questionLength
            // Compression pointer to QNAME at offset 12 (0xC00C)
            response[offset] = 0xC0.toByte()
            response[offset + 1] = 0x0C
            // TYPE
            response[offset + 2] = ((query.qtype ushr 8) and 0xFF).toByte()
            response[offset + 3] = (query.qtype and 0xFF).toByte()
            // CLASS IN
            response[offset + 4] = 0x00
            response[offset + 5] = 0x01
            // TTL = 60s
            response[offset + 6] = ((BLOCKED_TTL_SECONDS ushr 24) and 0xFF).toByte()
            response[offset + 7] = ((BLOCKED_TTL_SECONDS ushr 16) and 0xFF).toByte()
            response[offset + 8] = ((BLOCKED_TTL_SECONDS ushr 8) and 0xFF).toByte()
            response[offset + 9] = (BLOCKED_TTL_SECONDS and 0xFF).toByte()
            // RDLENGTH + RDATA (zeros for 0.0.0.0 or ::)
            val rdLength = if (isA) 4 else 16
            response[offset + 10] = 0x00
            response[offset + 11] = rdLength.toByte()
            // Remaining RDATA bytes are already 0x00
        }

        return response
    }
}
