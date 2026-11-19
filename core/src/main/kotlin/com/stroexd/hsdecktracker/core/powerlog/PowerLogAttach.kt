package com.stroexd.hsdecktracker.core.powerlog

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

private const val DEFAULT_ATTACH_SCAN_BLOCK_SIZE = 64 * 1024
private const val DEFAULT_MAX_ATTACH_SCAN_BYTES = 32L * 1024 * 1024
private const val MAX_ATTACH_LINE_BYTES = 128 * 1024
private const val GAME_STATE_POWER_PREFIX = "GameState.DebugPrintPower() - "

private enum class AttachLineKind { NONE, MATCH_START, MATCH_END }

/**
 * Finds the byte offset of the latest active match's `CREATE_GAME` line when attaching to an
 * existing `Power.log`, or returns [size] if no unfinished match is in progress.
 */
fun resolvePowerLogAttachOffset(
    channel: SeekableByteChannel,
    size: Long,
    blockSize: Int = DEFAULT_ATTACH_SCAN_BLOCK_SIZE,
    maxScanBytes: Long = DEFAULT_MAX_ATTACH_SCAN_BYTES,
): Long {
    if (size <= 0L || blockSize <= 0) return 0L
    val block = ByteArray(blockSize)
    var end = size
    var carry = ByteArray(0)
    var sawMatchEnd = false
    var scanned = 0L
    while (end > 0L && scanned < maxScanBytes) {
        val start = (end - blockSize).coerceAtLeast(0L)
        val count = (end - start).toInt()
        channel.position(start)
        val buffer = ByteBuffer.wrap(block, 0, count)
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // Read the complete backward scan window.
        }
        val readCount = buffer.position()
        if (readCount <= 0) break

        val combined = ByteArray(readCount + carry.size)
        System.arraycopy(block, 0, combined, 0, readCount)
        System.arraycopy(carry, 0, combined, readCount, carry.size)

        val firstNewline = if (start > 0L) indexOfNewline(combined) else -1
        if (start > 0L && firstNewline < 0) {
            carry = if (combined.size > MAX_ATTACH_LINE_BYTES) ByteArray(0) else combined
            end = start
            scanned += readCount
            continue
        }

        val stopAt = if (firstNewline >= 0) firstNewline + 1 else 0
        var lineEnd = combined.size
        while (lineEnd > stopAt) {
            val prevNewline = lastIndexOfNewline(combined, lineEnd - 2)
            val lineStart = if (prevNewline >= stopAt) prevNewline + 1 else stopAt
            val line = String(combined, lineStart, lineEnd - lineStart, Charsets.UTF_8)
            when (classifyAttachLine(line)) {
                AttachLineKind.MATCH_END -> sawMatchEnd = true
                AttachLineKind.MATCH_START -> return if (sawMatchEnd) size else start + lineStart
                AttachLineKind.NONE -> Unit
            }
            lineEnd = lineStart
        }

        carry = if (start > 0L) combined.copyOfRange(0, firstNewline + 1) else ByteArray(0)
        end = start
        scanned += readCount
    }
    return size
}

private fun classifyAttachLine(line: String): AttachLineKind {
    val index = line.indexOf(GAME_STATE_POWER_PREFIX)
    if (index < 0) return AttachLineKind.NONE
    val message = line.substring(index + GAME_STATE_POWER_PREFIX.length).trim()
    if (message == "CREATE_GAME") return AttachLineKind.MATCH_START
    if (message == "RESET_GAME") return AttachLineKind.MATCH_END
    if (message.startsWith("TAG_CHANGE ")) {
        val isCompleteState = message.contains("Entity=GameEntity ") &&
            (message.contains("tag=STATE value=COMPLETE") || message.contains("tag=19 value=3"))
        val isTerminalPlaystate = message.contains("tag=PLAYSTATE value=WON") ||
            message.contains("tag=PLAYSTATE value=LOST") ||
            message.contains("tag=PLAYSTATE value=TIED") ||
            message.contains("tag=17 value=4") ||
            message.contains("tag=17 value=5") ||
            message.contains("tag=17 value=6")
        if (isCompleteState || isTerminalPlaystate) return AttachLineKind.MATCH_END
    }
    return AttachLineKind.NONE
}

private fun indexOfNewline(bytes: ByteArray): Int {
    for (i in bytes.indices) {
        if (bytes[i] == '\n'.code.toByte()) return i
    }
    return -1
}

private fun lastIndexOfNewline(bytes: ByteArray, fromIndex: Int): Int {
    var i = fromIndex.coerceAtMost(bytes.lastIndex)
    while (i >= 0) {
        if (bytes[i] == '\n'.code.toByte()) return i
        i--
    }
    return -1
}
