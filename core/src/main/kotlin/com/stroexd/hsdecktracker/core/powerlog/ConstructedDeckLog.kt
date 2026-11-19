package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.DeckCode
import com.stroexd.hsdecktracker.core.deck.DeckDefinition

enum class DeckLogKind { COLLECTION, QUEUED }

data class DeckLogEntry(
    val deckId: Long,
    val name: String,
    val definition: DeckDefinition,
    val timeOfDayMillis: Long,
    val kind: DeckLogKind,
)

/** Reads complete constructed deck records from the game's Decks.log. */
class ConstructedDeckLog {
    private var kind: DeckLogKind? = null
    private var name: String? = null
    private var deckId: Long? = null

    fun reset() {
        kind = null
        clearRecord()
    }

    fun accept(line: String): DeckLogEntry? {
        if (line.length > MAX_LINE) return null
        val record = TIMED_RECORD.matchEntire(line.trim()) ?: return null
        val message = record.groupValues[5].trim()
        when (message) {
            "Deck Contents Received:", "Finished Editing Deck:" -> {
                clearRecord()
                kind = DeckLogKind.COLLECTION
                return null
            }
            "Finding Game With Deck:" -> {
                clearRecord()
                kind = DeckLogKind.QUEUED
                return null
            }
        }
        val entryKind = kind ?: return null
        if (message.startsWith("###")) {
            clearRecord()
            name = message.removePrefix("###").trim().takeIf { it.isNotEmpty() }
            return null
        }
        if (message.startsWith("# Deck ID:")) {
            deckId = message.removePrefix("# Deck ID:").trim().toLongOrNull()?.takeIf { it > 0 }
            return null
        }
        if (message.startsWith("#") || message.isEmpty()) return null
        if (!DECK_CODE.matches(message)) {
            reset()
            return null
        }
        val entryName = name
        val entryId = deckId
        clearRecord()
        if (entryKind == DeckLogKind.QUEUED) kind = null
        val time = record.timeOfDayMillis() ?: return null
        if (entryName == null || entryId == null) return null
        val definition = DeckCode.decodeOrNull(message) ?: return null
        if (definition.format !in CONSTRUCTED_FORMATS || definition.cards.isEmpty() ||
            definition.cards.values.any { it !in 1..100 } || definition.cardCount > 100
        ) return null
        return DeckLogEntry(entryId, entryName, definition, time, entryKind)
    }

    private fun clearRecord() {
        name = null
        deckId = null
    }

    private companion object {
        const val MAX_LINE = 1536
        val DECK_CODE = Regex("AAE[A-Za-z0-9+/]{6,}={0,2}")
        val CONSTRUCTED_FORMATS = setOf(GameFormat.STANDARD, GameFormat.WILD)
    }
}

/** Log time without the date, supplied separately by the containing log session. */
fun gameLogTimeOfDayMillis(line: String): Long? =
    TIMED_RECORD.matchEntire(line.trim())?.timeOfDayMillis()

private fun MatchResult.timeOfDayMillis(): Long? {
    val (hourText, minuteText, secondText, fraction) = groupValues.subList(1, 5)
    val hour = hourText.toInt().takeIf { it in 0..23 } ?: return null
    val minute = minuteText.toInt().takeIf { it in 0..59 } ?: return null
    val second = secondText.toInt().takeIf { it in 0..59 } ?: return null
    val millis = fraction.take(3).padEnd(3, '0').toInt()
    return ((hour * 60 + minute) * 60 + second) * 1000L + millis
}

private val TIMED_RECORD = Regex("[A-Z] +(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{1,9}) +(.*)")
