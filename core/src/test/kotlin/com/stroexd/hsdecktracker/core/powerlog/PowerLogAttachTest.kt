package com.stroexd.hsdecktracker.core.powerlog

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerLogAttachTest {
    @Test
    fun returnsMatchStartWhenLatestGameIsActiveAcrossMultipleScanBlocks() {
        val finishedGame = buildString {
            append("D 15:21:22.5992740 GameState.DebugPrintPower() - CREATE_GAME\n")
            repeat(40) { append("D 15:22:00.0000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=Player1 tag=TURN value=$it\n") }
            append("D 15:25:39.8243060 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE \n")
        }
        val activeStartLine = "D 15:26:01.8504200 GameState.DebugPrintPower() - CREATE_GAME\n"
        val activeBody = buildString {
            append(activeStartLine)
            append("D 15:26:01.8504200 GameState.DebugPrintGame() - GameType=GT_RANKED\n")
            repeat(60) { append("D 15:27:00.0000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=$it\n") }
        }
        val full = (finishedGame + activeBody).toByteArray(Charsets.UTF_8)
        val expectedOffset = finishedGame.toByteArray(Charsets.UTF_8).size.toLong()

        val channel = ByteArrayChannel(full)
        val offset = resolvePowerLogAttachOffset(channel, full.size.toLong(), blockSize = 128)
        assertEquals(expectedOffset, offset)
    }

    @Test
    fun returnsEndOfFileWhenLatestGameAlreadyCompleted() {
        val log = (
            "D 15:21:22.5992740 GameState.DebugPrintPower() - CREATE_GAME\n" +
                "D 15:22:00.0000000 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=TURN value=1\n" +
                "D 15:25:39.8243060 GameState.DebugPrintPower() - TAG_CHANGE Entity=GameEntity tag=STATE value=COMPLETE \n"
            ).toByteArray(Charsets.UTF_8)

        val offset = resolvePowerLogAttachOffset(ByteArrayChannel(log), log.size.toLong(), blockSize = 64)
        assertEquals(log.size.toLong(), offset)
    }

    @Test
    fun returnsZeroWhenActiveGameStartsAtBeginningOfFile() {
        val log = (
            "D 15:21:22.5992740 GameState.DebugPrintPower() - CREATE_GAME\r\n" +
                "D 15:21:22.6000000 GameState.DebugPrintGame() - FormatType=FT_WILD\r\n"
            ).toByteArray(Charsets.UTF_8)

        val offset = resolvePowerLogAttachOffset(ByteArrayChannel(log), log.size.toLong(), blockSize = 32)
        assertEquals(0L, offset)
    }

    @Test
    fun returnsEndOfFileWhenNoCreateGamePresent() {
        val log = "D 15:20:00.0000000 PowerProcessor.EndCurrentTaskList() - m_currentTaskList=1\n"
            .toByteArray(Charsets.UTF_8)

        val offset = resolvePowerLogAttachOffset(ByteArrayChannel(log), log.size.toLong(), blockSize = 32)
        assertEquals(log.size.toLong(), offset)
    }

    private class ByteArrayChannel(private val data: ByteArray) : SeekableByteChannel {
        private var pos = 0L
        private var open = true

        override fun isOpen(): Boolean = open
        override fun close() { open = false }
        override fun position(): Long = pos
        override fun position(newPosition: Long): SeekableByteChannel = apply { pos = newPosition }
        override fun size(): Long = data.size.toLong()
        override fun read(dst: ByteBuffer): Int {
            if (pos >= data.size) return -1
            val count = minOf(dst.remaining(), (data.size - pos).toInt())
            dst.put(data, pos.toInt(), count)
            pos += count
            return count
        }

        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
    }
}
