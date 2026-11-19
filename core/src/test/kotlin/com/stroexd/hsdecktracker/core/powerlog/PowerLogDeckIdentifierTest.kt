package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerLogDeckIdentifierTest {
    private val db = CardDatabase(listOf(Card(1, "ONE"), Card(2, "TWO")))
    private val oneCopy = Deck(name = "One copy", heroClass = HsClass.SHAMAN, cards = mapOf(1 to 1, 2 to 1, 3 to 28))
    private val twoCopies = oneCopy.copy(id = "two", cards = mapOf(1 to 2, 2 to 1, 3 to 27))
    private val renathal = twoCopies.copy(id = "forty", cards = mapOf(1 to 2, 2 to 1, 3 to 37))

    @Test
    fun distinguishesDuplicateCopiesAndStartingSizes() {
        val evidence = PowerLogStartingDeck(30, listOf("ONE", "ONE"))
        assertEquals(twoCopies, identifyPowerLogDeck(evidence, listOf(oneCopy, twoCopies, renathal), db))
    }

    @Test
    fun waitsForDistinctAndReliableEvidence() {
        val ambiguous = PowerLogStartingDeck(30, listOf("ONE", "TWO"))
        assertNull(identifyPowerLogDeck(ambiguous, listOf(oneCopy, twoCopies), db))
        val missingCard = PowerLogStartingDeck(30, listOf("ONE", "TWO", "UNKNOWN"))
        assertNull(identifyPowerLogDeck(missingCard, listOf(oneCopy), db))
    }
}
