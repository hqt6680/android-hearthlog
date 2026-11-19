package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.deck.Deck

/** Returns a unique consistent saved deck when queued-deck metadata is unavailable. */
fun identifyPowerLogDeck(
    startingDeck: PowerLogStartingDeck,
    candidates: List<Deck>,
    database: CardDatabase,
): Deck? {
    if (startingDeck.size !in setOf(20, 30, 40) || startingDeck.revealedCards.size < 2) return null
    val cards = startingDeck.revealedCards.map { database.byCardId(it)?.dbfId ?: return null }
    val counts = cards.groupingBy { it }.eachCount()
    return candidates.singleOrNull { deck ->
        deck.cardCount == startingDeck.size && counts.all { (cardId, count) -> (deck.cards[cardId] ?: 0) >= count }
    }
}
