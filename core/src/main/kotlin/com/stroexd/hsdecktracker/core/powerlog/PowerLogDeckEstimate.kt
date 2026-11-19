package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.deck.Deck

data class PowerLogDeckEstimate(
    val cards: Map<Int, Int>,
    val hasUnresolvedChanges: Boolean,
    val uncataloguedCards: Map<String, Int> = emptyMap(),
)

fun estimatePowerLogDeck(
    deck: Deck,
    side: PowerLogSide,
    db: CardDatabase,
    fallbackDbfId: (String) -> Int? = { null },
): PowerLogDeckEstimate {
    val counts = deck.cards.toMutableMap()
    val uncataloguedCards = mutableMapOf<String, Int>()
    var unresolved = false
    for (change in side.deckChanges) {
        val dbfId = db.byCardId(change.cardId)?.dbfId
            ?: fallbackDbfId(change.cardId)?.takeIf { it in deck.cards }
        val recorded = if (dbfId != null) counts.applyChange(dbfId, change.kind)
            else uncataloguedCards.applyChange(change.cardId, change.kind)
        if (!recorded) unresolved = true
    }
    val estimatedCount = counts.values.sum() + uncataloguedCards.values.sum()
    val loggedCount = side.deckCount
    return PowerLogDeckEstimate(counts, unresolved || (loggedCount != null && loggedCount != estimatedCount), uncataloguedCards)
}

private fun <K> MutableMap<K, Int>.applyChange(key: K, kind: PowerLogDeckChangeKind): Boolean {
    val count = getOrDefault(key, 0)
    when (kind) {
        PowerLogDeckChangeKind.RETURNED -> this[key] = count + 1
        PowerLogDeckChangeKind.TAKEN -> when (count) {
            0 -> return false
            1 -> remove(key)
            else -> this[key] = count - 1
        }
    }
    return true
}
