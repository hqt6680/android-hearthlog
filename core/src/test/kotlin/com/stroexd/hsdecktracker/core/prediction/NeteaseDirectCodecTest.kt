package com.stroexd.hsdecktracker.core.prediction

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NeteaseDirectCodecTest {
    @Test
    fun `rc4 matches standard RFC 6229 test vector and roundtrips`() {
        val key = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val expectedKeystream = byteArrayOf(0xb2.toByte(), 0x39, 0x63, 0x05, 0xf0.toByte(), 0x3d, 0xc0.toByte(), 0x27)
        val zeros = ByteArray(expectedKeystream.size)
        assertContentEquals(expectedKeystream, NeteaseDirectCodec.rc4(zeros, key))

        val message = """{"hello":"world","n":42}""".encodeToByteArray()
        val encrypted = NeteaseDirectCodec.rc4(message, "secret-key".encodeToByteArray())
        assertContentEquals(message, NeteaseDirectCodec.rc4(encrypted, "secret-key".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> {
            NeteaseDirectCodec.rc4(message, byteArrayOf())
        }
    }

    @Test
    fun `zlib level 9 emits 78 DA header and roundtrips`() {
        val raw = """{"entities":{"game":[{"TURN":1}]},"uuid":"20261119_test"}""".repeat(4).encodeToByteArray()
        val compressed = NeteaseDirectCodec.zlibCompressLevel9(raw)
        assertTrue(compressed.size >= 6)
        assertEquals(0x78.toByte(), compressed[0])
        assertEquals(0xDA.toByte(), compressed[1])
        assertContentEquals(raw, NeteaseDirectCodec.zlibDecompress(compressed))
    }

    @Test
    fun `base64url preserves trailing padding and decodes unpadded variants`() {
        // Length 1 -> needs "==" padding in Base64, byte 0xFB tests '+'->'-', 0xFF tests '/'->'_'
        val raw = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xbe.toByte(), 0xff.toByte())
        val encoded = NeteaseDirectCodec.encodeBase64UrlWithPadding(raw)
        assertTrue(encoded.endsWith("=="))
        assertTrue('+' !in encoded && '/' !in encoded)
        assertContentEquals(raw, NeteaseDirectCodec.decodeBase64Url(encoded))
        assertContentEquals(raw, NeteaseDirectCodec.decodeBase64Url(encoded.trimEnd('=')))
    }

    @Test
    fun `encodeRequestData and decodeResponseData roundtrip cleanly`() {
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val payload = """{"type":"netreloaded","data":{"turns":[]}}""".encodeToByteArray()
        val wire = NeteaseDirectCodec.encodeRequestData(payload, key)
        val cipherBytes = NeteaseDirectCodec.decodeBase64Url(wire)
        val compressed = NeteaseDirectCodec.rc4(cipherBytes, key)
        assertContentEquals(payload, NeteaseDirectCodec.zlibDecompress(compressed))

        val responseCipher = NeteaseDirectCodec.encodeBase64UrlWithPadding(
            NeteaseDirectCodec.rc4("""{"status":true}""".encodeToByteArray(), key),
        )
        assertEquals("""{"status":true}""", NeteaseDirectCodec.decodeResponseData(responseCipher, key))
    }

    @Test
    fun `newRequestUuid prefixes UTC basic ISO date`() {
        val uuid = NeteaseDirectCodec.newRequestUuid(LocalDate.of(2026, 11, 19))
        assertTrue(uuid.startsWith("20261119_"))
        assertTrue(uuid.length > 10)
    }
}
