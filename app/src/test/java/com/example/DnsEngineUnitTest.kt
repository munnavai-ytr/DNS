package com.example

import com.example.dns.DnsWireCodec
import com.example.dns.DomainFilterEngine
import com.example.dns.IpUdpPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsEngineUnitTest {

    @Test
    fun `parent domain matching and whitelist override work correctly`() {
        val engine = DomainFilterEngine()
        engine.updateDynamicRules(
            customBlockedDomains = listOf("blocked-site.com"),
            whitelistedDomains = listOf("safe.blocked-site.com"),
        )

        assertTrue(engine.isBlocked("blocked-site.com"))
        assertTrue(engine.isBlocked("cdn.blocked-site.com"))
        assertFalse(engine.isBlocked("safe.blocked-site.com"))
        assertFalse(engine.isBlocked("img.safe.blocked-site.com"))
        assertFalse(engine.isBlocked("wikipedia.org"))
    }

    @Test
    fun `hosts file format and plain domain lines parse cleanly`() {
        assertEquals("example.com", DomainFilterEngine.parseBlocklistLine("0.0.0.0 example.com # comment"))
        assertEquals("example.org", DomainFilterEngine.parseBlocklistLine("127.0.0.1   EXAMPLE.ORG."))
        assertEquals("blocktest.dnsshield.test", DomainFilterEngine.parseBlocklistLine("blocktest.dnsshield.test"))
        assertEquals(null, DomainFilterEngine.parseBlocklistLine("# pure comment"))
    }

    @Test
    fun `dns A and AAAA queries produce zero-IP responses wrapped in valid IP UDP`() {
        val dnsQuery = buildDnsQueryPacket("blocktest.dnsshield.test", DnsWireCodec.QTYPE_A)
        val parsedQuery = DnsWireCodec.parseQuery(dnsQuery, dnsQuery.size)
        assertNotNull(parsedQuery)
        assertEquals("blocktest.dnsshield.test", parsedQuery!!.domain)
        assertEquals(DnsWireCodec.QTYPE_A, parsedQuery.qtype)

        val blockedDns = DnsWireCodec.buildBlockedResponse(dnsQuery, parsedQuery)
        assertEquals(0x80.toByte(), blockedDns[2] andMask 0x80.toByte()) // QR=1
        assertEquals(1, blockedDns[7].toInt() and 0xFF) // ANCOUNT=1
        // Last 4 bytes must be 0.0.0.0
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            blockedDns.copyOfRange(blockedDns.size - 4, blockedDns.size),
        )

        // Wrap in IPv4 UDP packet -> parse -> build response
        val ipPacket = wrapIpv4Udp(dnsQuery)
        val parsedUdp = IpUdpPacketCodec.parseUdpDnsPacket(ipPacket, ipPacket.size)
        assertNotNull(parsedUdp)
        val responseIpPacket = IpUdpPacketCodec.buildUdpResponsePacket(parsedUdp!!, blockedDns)
        assertEquals(20 + 8 + blockedDns.size, responseIpPacket.size)
        // Dest port in response should match original src port (12345)
        val respDstPort =
            ((responseIpPacket[22].toInt() and 0xFF) shl 8) or
                (responseIpPacket[23].toInt() and 0xFF)
        assertEquals(12345, respDstPort)
    }

    @Test
    fun `dns AAAA query returns 16 zero bytes for IPv6 unspecified`() {
        val dnsQuery = buildDnsQueryPacket("blocktest.dnsshield.test", DnsWireCodec.QTYPE_AAAA)
        val parsedQuery = DnsWireCodec.parseQuery(dnsQuery, dnsQuery.size)!!
        val blockedDns = DnsWireCodec.buildBlockedResponse(dnsQuery, parsedQuery)
        assertEquals(1, blockedDns[7].toInt() and 0xFF)
        assertArrayEquals(
            ByteArray(16),
            blockedDns.copyOfRange(blockedDns.size - 16, blockedDns.size),
        )
    }

    private infix fun Byte.andMask(other: Byte): Byte = (this.toInt() and other.toInt()).toByte()

    private fun buildDnsQueryPacket(domain: String, qtype: Int): ByteArray {
        val labels = domain.split('.')
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            qnameBytes.add(label.length.toByte())
            for (ch in label) {
                qnameBytes.add(ch.code.toByte())
            }
        }
        qnameBytes.add(0)

        val packet = ByteArray(12 + qnameBytes.size + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[2] = 0x01 // RD=1
        packet[5] = 0x01 // QDCOUNT=1
        System.arraycopy(qnameBytes.toByteArray(), 0, packet, 12, qnameBytes.size)
        val typeOffset = 12 + qnameBytes.size
        packet[typeOffset] = ((qtype ushr 8) and 0xFF).toByte()
        packet[typeOffset + 1] = (qtype and 0xFF).toByte()
        packet[typeOffset + 3] = 0x01 // QCLASS=IN
        return packet
    }

    private fun wrapIpv4Udp(payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        out[0] = 0x45
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[8] = 64
        out[9] = 17 // UDP
        // src 10.111.222.1 -> dst 10.111.222.2
        out[12] = 10; out[13] = 111; out[14] = 222.toByte(); out[15] = 1
        out[16] = 10; out[17] = 111; out[18] = 222.toByte(); out[19] = 2
        // srcPort 12345 (0x3039), dstPort 53 (0x0035)
        out[20] = 0x30; out[21] = 0x39
        out[22] = 0x00; out[23] = 0x35
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }
}
