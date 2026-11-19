package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstructedDeckLogTest {
    private val definition = DeckDefinition(listOf(1066), GameFormat.WILD, (1..15).associateWith { 2 })
    private val code = DeckCode.encode(definition)
    private val log = ConstructedDeckLog()

    @Test
    fun readsMultipleCollectionDecksAndKeepsTheQueueRecordDistinct() {
        assertNull(log.accept(line("Deck Contents Received:")))
        assertNull(log.accept(line("Deck Contents Received:")))
        val saved = feedDeck("随从萨", 8903214791)
        val another = feedDeck("第二副随从萨", 8903214792)
        assertEquals(DeckLogEntry(8903214791, "随从萨", definition, 6_095_168, DeckLogKind.COLLECTION), saved)
        assertEquals("第二副随从萨", another?.name)

        assertNull(log.accept(line("Finding Game With Deck:")))
        assertEquals(DeckLogKind.QUEUED, feedDeck("随从萨", 8903214791)?.kind)
        assertNull(feedDeck("没有提交到匹配", 8903214792))
    }

    @Test
    fun rejectsPartialOrStaleRecordsAndParsesThePowerTimestamp() {
        log.accept(line("Finding Game With Deck:"))
        log.accept(line("### 旧套牌"))
        log.accept(line("# Deck ID: 1"))
        log.reset()
        assertNull(log.accept(line(code)))
        log.accept(line("Deck Contents Received:"))
        log.accept(line("### 缺少ID"))
        assertNull(log.accept(line(code)))
        assertEquals(86_399_999L, gameLogTimeOfDayMillis("D 23:59:59.9999371 GameState.DebugPrintPower() - CREATE_GAME"))
        assertNull(gameLogTimeOfDayMillis("D 26:40:26.01 GameState.DebugPrintPower() - CREATE_GAME"))
    }

    private fun feedDeck(name: String, id: Long): DeckLogEntry? {
        log.accept(line("### $name"))
        log.accept(line("# Deck ID: $id"))
        return log.accept(line(code))
    }

    private fun line(body: String) = "I 01:41:35.1688050 $body"
}
