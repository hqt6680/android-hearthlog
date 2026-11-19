package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot

enum class PowerLogPredictionKind(val wireName: String) {
    ACTION("action"), MULLIGAN("mulligan"), CHOICE("choice"), OPPONENT("opponent");

    companion object {
        fun fromWireName(name: String): PowerLogPredictionKind? = entries.firstOrNull { it.wireName == name }
    }
}

data class PowerLogPredictionAnchor(val kind: PowerLogPredictionKind, val rawTurn: Int, val optionsId: Int)

/** Identifies the pending decision or the current opponent position in a complete log prefix. */
fun PowerLogSnapshot.activePredictionAnchor(logLineCount: Int): PowerLogPredictionAnchor? {
    if (result != null || gameComplete) return null
    openingChoice?.let { opening ->
        return if (turn >= 0 && opening.choiceId > 0 && (opening.playerId ?: 0) in 1..2 &&
            (opening.sourceEntityId ?: 0) > 0 && opening.cards.size in 3..4 &&
            opening.cards.all { (it.entityId ?: 0) > 0 }
        ) PowerLogPredictionAnchor(PowerLogPredictionKind.MULLIGAN, turn, opening.choiceId) else null
    }
    if (!localPlayerIdentified || (friendly.playerId ?: 0) !in 1..2 || turn <= 0) return null
    generalChoice?.let { choice ->
        return if (choice.choiceId > 0 && (choice.sourceEntityId ?: 0) > 0 &&
            choice.maxSelections in 1..choice.options.size
        ) PowerLogPredictionAnchor(PowerLogPredictionKind.CHOICE, turn, choice.choiceId) else null
    }
    if (friendly.isCurrentTurn == true) {
        val packet = actionOptions?.takeIf { it.packetId > 0 && it.options.isNotEmpty() } ?: return null
        return PowerLogPredictionAnchor(PowerLogPredictionKind.ACTION, turn, packet.packetId)
    }
    return if (friendly.isCurrentTurn == false && opponent.isCurrentTurn == true && logLineCount > 0)
        PowerLogPredictionAnchor(PowerLogPredictionKind.OPPONENT, turn, logLineCount) else null
}
