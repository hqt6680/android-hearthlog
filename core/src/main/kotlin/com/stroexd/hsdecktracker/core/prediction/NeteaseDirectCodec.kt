package com.stroexd.hsdecktracker.core.prediction

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Codec utilities for the NetEase direct prediction endpoint. */
object NeteaseDirectCodec {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun rc4(input: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }
        val state = IntArray(256) { it }
        var j = 0
        for (i in state.indices) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
        }
        var i = 0
        j = 0
        return ByteArray(input.size) { offset ->
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            val k = state[(state[i] + state[j]) and 0xff]
            (input[offset].toInt() xor k).toByte()
        }
    }

    fun zlibCompressLevel9(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, input.size / 2))
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count > 0) out.write(buffer, 0, count) else if (deflater.needsInput()) break
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun zlibDecompress(input: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(maxOf(64, input.size * 2))
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** URL-safe Base64 preserving trailing '=' padding as required by NetEase standard_substep. */
    fun encodeBase64UrlWithPadding(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')

    fun decodeBase64Url(text: String): ByteArray {
        val normalized = text.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        val padded = if (pad == 0) normalized else normalized + "=".repeat(pad)
        return Base64.getDecoder().decode(padded)
    }

    fun encodeRequestData(jsonUtf8: ByteArray, rc4Key: ByteArray): String =
        encodeBase64UrlWithPadding(rc4(zlibCompressLevel9(jsonUtf8), rc4Key))

    fun decodeResponseData(ciphertext: String, rc4Key: ByteArray): String =
        rc4(decodeBase64Url(ciphertext), rc4Key).decodeToString()

    fun newRequestUuid(date: LocalDate = LocalDate.now(ZoneOffset.UTC)): String =
        "${DATE_FORMAT.format(date)}_${UUID.randomUUID()}"
}
