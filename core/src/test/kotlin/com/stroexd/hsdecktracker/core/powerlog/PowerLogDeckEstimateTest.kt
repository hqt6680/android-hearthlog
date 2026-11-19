package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerLogDeckEstimateTest {
    private val db = CardDatabase(listOf(Card(1, "A"), Card(2, "B"), Card(3, "C")))
    private val deck = Deck(name = "Imported", heroClass = HsClass.SHAMAN, cards = mapOf(1 to 2, 2 to 1))

    @Test
    fun appliesDrawsAndKnownCardsShuffledIntoDeckInOrder() {
        val side = PowerLogSide(
            deckCount = 3,
            deckChanges = listOf(move("A"), move("C", returned = true), move("C"), move("A", returned = true)),
        )
        val remaining = estimatePowerLogDeck(deck, side, db)

        assertEquals(deck.cards, remaining.cards)
        assertFalse(remaining.hasUnresolvedChanges)
    }

    @Test
    fun retainsUncollectibleShuffledCardsByTheirLogIdAndRemovesDrawnCopies() {
        val side = PowerLogSide(
            deckCount = 8,
            deckChanges = List(6) { move("WW_372t", returned = true) } + move("WW_372t"),
        )
        val remaining = estimatePowerLogDeck(deck, side, db)

        assertEquals(deck.cards, remaining.cards)
        assertEquals(mapOf("WW_372t" to 5), remaining.uncataloguedCards)
        assertFalse(remaining.hasUnresolvedChanges)
    }

    @Test
    fun reconcilesAStartingCardMissingFromTheCollectibleDatabase() {
        val startingDeck = deck.copy(cards = deck.cards + mapOf(119815 to 1, 119816 to 1))
        val fallback = mapOf("TIME_875t" to 119815, "TIME_875t1" to 119816, "WW_372t" to 102000)
        val side = PowerLogSide(
            deckCount = 5,
            deckChanges = listOf(
                move("TIME_875t1"),
                move("TIME_875t1", returned = true),
                move("TIME_875t1"),
                move("WW_372t", returned = true),
            ),
        )

        val remaining = estimatePowerLogDeck(startingDeck, side, db, fallback::get)

        assertEquals(deck.cards + (119815 to 1), remaining.cards)
        assertEquals(mapOf("WW_372t" to 1), remaining.uncataloguedCards)
        assertFalse(remaining.hasUnresolvedChanges)
    }

    @Test
    fun retainsKnownCountsWhenAChangeCannotBeReconciled() {
        val side = PowerLogSide(deckCount = 1, deckChanges = listOf(move("A"), move("C"), move("UNKNOWN")))
        val remaining = estimatePowerLogDeck(deck, side, db)

        assertEquals(mapOf(1 to 1, 2 to 1), remaining.cards)
        assertTrue(remaining.hasUnresolvedChanges)
    }

    @Test
    fun flagsAnExactLogTotalThatDisagreesWithTheKnownList() {
        assertTrue(estimatePowerLogDeck(deck, PowerLogSide(deckCount = 2), db).hasUnresolvedChanges)
    }

    private fun move(id: String, returned: Boolean = false) =
        PowerLogDeckChange(id, if (returned) PowerLogDeckChangeKind.RETURNED else PowerLogDeckChangeKind.TAKEN, 1)
}
