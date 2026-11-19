package com.stroexd.hsdecktracker.core.powerlog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Evidence for comparing an ordinary Options packet with the original client offline. */
data class PowerLogNativeOptionProjection(val comparisonJson: JsonObject, val unresolvedFields: List<String>)

@JvmOverloads
fun projectNativeOptionsForAudit(
    snapshot: PowerLogSnapshot,
    battlefieldTags: Map<Int, Map<String, Int>> = emptyMap(),
): PowerLogNativeOptionProjection? {
    if (!snapshot.localPlayerIdentified || snapshot.result != null || snapshot.openingChoice != null || snapshot.generalChoice != null) {
        return null
    }
    val packet = snapshot.actionOptions?.takeIf { it.options.isNotEmpty() } ?: return null
    val owners = visibleOptionOwners(snapshot)
    val unresolved = mutableListOf(
        "type", "data.extra_infos", "state.entities", "state.played_cards", "state.deck_card_counter",
    )
    val comparison = buildJsonObject {
        putJsonObject("data") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    putJsonArray("processes") {
                        add(buildJsonObject {
                            putJsonObject("state") {
                                put("options_id", packet.packetId)
                                putJsonArray("options") {
                                    packet.options.forEach { add(projectOption(it, owners, unresolved)) }
                                }
                                val entities = projectBattlefieldEntities(snapshot, battlefieldTags)
                                if (entities.isNotEmpty()) put("entities", entities)
                            }
                        })
                    }
                })
            }
        }
    }
    return PowerLogNativeOptionProjection(comparison, unresolved.toList())
}

private fun projectBattlefieldEntities(snapshot: PowerLogSnapshot, tags: Map<Int, Map<String, Int>>): JsonObject = buildJsonObject {
    for ((prefix, side) in listOf("my" to snapshot.friendly, "opp" to snapshot.opponent)) {
        val hero = projectEntity(side.heroEntityId, side.heroCardId, tags)
        if (hero != null) put("${prefix}_hero", JsonArray(listOf(hero)))
        val lineup = side.board.mapNotNull { projectEntity(it.entityId, it.cardId, tags) }
        if (lineup.isNotEmpty()) put("${prefix}_lineup", JsonArray(lineup))
    }
}

private fun projectEntity(id: Int?, cardId: String?, tags: Map<Int, Map<String, Int>>): JsonObject? {
    val entityId = id ?: return null
    val observed = tags[entityId] ?: return null
    return buildJsonObject {
        put("ENTITY_ID", entityId)
        if (cardId != null) put("_CARD_ID", cardId)
        putExplicitCombatTags(observed)
    }
}

private fun JsonObjectBuilder.putExplicitCombatTags(observed: Map<String, Int>) {
    for (tag in listOf("ATK", "HEALTH", "DAMAGE", "ARMOR", "EXHAUSTED", "ZONE_POSITION", "CONTROLLER")) {
        observed[tag]?.let { put(tag, it) }
    }
}

private fun visibleOptionOwners(snapshot: PowerLogSnapshot): Map<Int, Int> = buildMap {
    for (side in listOf(snapshot.friendly, snapshot.opponent)) {
        val playerId = side.playerId ?: continue
        side.heroEntityId?.let { put(it, playerId) }
        side.heroPower?.let { put(it.entityId, playerId) }
        side.weaponEntityId?.let { put(it, playerId) }
        (side.hand + side.board + side.graveyard).forEach { card ->
            card.entityId?.let { put(it, playerId) }
        }
    }
}

private fun projectOption(
    option: PowerLogActionOption,
    owners: Map<Int, Int>,
    unresolved: MutableList<String>,
): JsonObject = buildJsonObject {
    val path = "state.options[${option.index}]"
    put("id", option.index)
    put("type", option.type)
    if (option.error == null) unresolved.add("$path.error") else put("error", option.error)
    if (option.type == "END_TURN") return@buildJsonObject
    val entityId = option.entityId
    if (entityId == null) {
        unresolved.add("$path.entity_id/player_id")
    } else {
        put("entity_id", entityId)
        val owner = owners[entityId]
        if (owner == null) unresolved.add("$path.player_id") else put("player_id", owner)
    }
    val legalTargets = option.targets.filter { it.error == "NONE" }.mapNotNull { target ->
        val id = target.entityId
        if (id == null) {
            unresolved.add("$path.targets[${target.index}].entity_id")
            null
        } else {
            buildJsonObject {
                put("id", target.index)
                put("entity_id", id)
            }
        }
    }
    if (legalTargets.isNotEmpty()) put("targets", JsonArray(legalTargets))
    if (option.subOptions.isNotEmpty()) unresolved.add("$path.sub_options")
}
