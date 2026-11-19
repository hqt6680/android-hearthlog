package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Serialized decision frame combining a log snapshot and deck definition. */
fun PredictStatePreview.toReplayFrame(deck: DeckDefinition): JsonObject? {
    if (unresolvedFields.any { it.endsWith(".sub_options") }) return null
    return buildJsonObject {
        put("rawTurn", rawTurn)
        put("kind", kind.wireName)
        put("optionsId", optionsId)
        put("format", format.name)
        put("lastLineNumber", lastLineNumber)
        put("localPlayerId", localPlayerId)
        put("snapshot", comparisonJson)
        put("deckFormat", deck.format.name)
        putJsonArray("deck") {
            for ((dbfId, count) in deck.cards) add(buildJsonObject {
                put("dbfId", dbfId)
                put("count", count)
            })
        }
        putJsonArray("sideboard") {
            for (card in deck.sideboards) add(buildJsonObject {
                put("dbfId", card.dbfId)
                put("count", card.count)
                put("ownerDbfId", card.ownerDbfId)
            })
        }
    }
}
