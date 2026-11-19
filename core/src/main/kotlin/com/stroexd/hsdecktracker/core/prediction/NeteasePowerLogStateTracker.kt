package com.stroexd.hsdecktracker.core.prediction

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class NeteaseTrackedChoice(
    val id: Int,
    val playerName: String,
    val choiceType: String,
    val countMin: Int,
    val countMax: Int,
    val sourceEntityId: Int,
    val entityIds: List<Int>,
)

data class NeteasePowerLogSnapshot(
    val entities: JsonObject,
    val playedCards: JsonObject,
    val deckCardCounter: JsonObject,
    val localController: Int,
    val activeChoice: NeteaseTrackedChoice?,
    val buildNumber: Int?,
    val gameType: String?,
    val formatType: String?,
    val scenarioId: Int?,
)

/**
 * Stateful parser building the entity, played-card, deck-counter, and choice snapshot
 * expected by NetEase's Constructed prediction endpoint from Hearthstone's Power.log.
 */
class NeteasePowerLogStateTracker {
    private class PowerEntity(val id: Int) {
        val tags: MutableMap<String, JsonPrimitive> = LinkedHashMap()
        var zoneEntrySequence: Long = 0L

        fun strTag(name: String): String? = tags[name]?.content
        fun intTag(name: String): Int = strTag(name)?.toIntOrNull() ?: 0
        val cardType: String? get() = strTag("CARDTYPE")
        val zone: String? get() = strTag("ZONE")
        val cardId: String? get() = strTag("_CARD_ID")?.takeIf { it.isNotEmpty() }
        val controller: Int get() = intTag("CONTROLLER")
        val attachedTo: Int get() = intTag("ATTACHED")
    }

    private data class PlayedCardEntry(
        val entityId: Int,
        val playedTurn: Int,
        var cardId: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("ENTITY_ID", entityId)
            put("_PLAYED_TURN", playedTurn)
            cardId?.takeIf { it.isNotEmpty() }?.let { put("_CARD_ID", it) }
        }
    }

    private class MutableChoice(
        val id: Int,
        val playerName: String,
        val choiceType: String,
        val countMin: Int,
        val countMax: Int,
    ) {
        var sourceEntityId: Int = 0
        val entityIds: MutableList<Int> = ArrayList()

        fun snapshot() = NeteaseTrackedChoice(
            id = id,
            playerName = playerName,
            choiceType = choiceType,
            countMin = countMin,
            countMax = countMax,
            sourceEntityId = sourceEntityId,
            entityIds = entityIds.toList(),
        )
    }

    private val entities = HashMap<Int, PowerEntity>()
    private val playerIdToEntityId = HashMap<Int, Int>()
    private val playerIdToName = HashMap<Int, String>()
    private val playerNameToPlayerId = HashMap<String, Int>()
    private val deckPushCounts = HashMap<Int, MutableMap<String, Int>>()
    private val deckPopCounts = HashMap<Int, MutableMap<String, Int>>()
    private val playedCardsByController = HashMap<Int, MutableList<PlayedCardEntry>>()
    private val activeChoices = LinkedHashMap<Int, MutableChoice>()

    private var currentEntity: PowerEntity? = null
    private var currentParsingChoice: MutableChoice? = null
    private var gameEntityId: Int? = null
    private var nextZoneSequence: Long = 0L
    private var graveyardEnterIndex: Int = 0
    private var buildNumber: Int? = null
    private var gameType: String? = null
    private var formatType: String? = null
    private var scenarioId: Int? = null

    fun reset() {
        entities.clear()
        playerIdToEntityId.clear()
        playerIdToName.clear()
        playerNameToPlayerId.clear()
        deckPushCounts.clear()
        deckPopCounts.clear()
        playedCardsByController.clear()
        activeChoices.clear()
        currentEntity = null
        currentParsingChoice = null
        gameEntityId = null
        nextZoneSequence = 0L
        graveyardEnterIndex = 0
        buildNumber = null
        gameType = null
        formatType = null
        scenarioId = null
    }

    fun processLine(rawLine: String) {
        val line = rawLine.removePrefix("\uFEFF")
        when {
            DEBUG_PRINT_GAME in line -> handleGameLine(line.substringAfter(DEBUG_PRINT_GAME))
            DEBUG_PRINT_CHOICES in line -> handleChoiceHeaderLine(line.substringAfter(DEBUG_PRINT_CHOICES))
            SEND_CHOICES in line -> handleSentChoiceLine(line.substringAfter(SEND_CHOICES))
            DEBUG_ENTITIES_CHOSEN in line -> handleEntitiesChosenLine(line.substringAfter(DEBUG_ENTITIES_CHOSEN))
            DEBUG_PRINT_POWER in line -> handlePowerLine(line.substringAfter(DEBUG_PRINT_POWER))
        }
    }

    private fun handleGameLine(rest: String) {
        val body = rest.trim()
        when {
            body.startsWith("BuildNumber=") ->
                BUILD_NUMBER_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { buildNumber = it }
            body.startsWith("GameType=") ->
                GAME_TYPE_REGEX.find(body)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let { gameType = it }
            body.startsWith("FormatType=") ->
                FORMAT_TYPE_REGEX.find(body)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let { formatType = it }
            body.startsWith("ScenarioID=") ->
                SCENARIO_ID_REGEX.find(body)?.groupValues?.get(1)?.toIntOrNull()?.let { scenarioId = it }
            "PlayerName=" in body -> {
                val match = PLAYER_NAME_REGEX.find(body) ?: return
                val playerId = match.groupValues[1].toIntOrNull() ?: return
                val playerName = match.groupValues[2].trim()
                if (playerName.isNotEmpty()) {
                    playerIdToName[playerId] = playerName
                    playerNameToPlayerId[playerName] = playerId
                }
            }
        }
    }

    private fun handleChoiceHeaderLine(rest: String) {
        val header = CHOICE_HEADER_REGEX.find(rest)
        if (header != null) {
            val id = header.groupValues[1].toIntOrNull() ?: return
            val playerName = header.groupValues[2].trim()
            val choiceType = header.groupValues[3].trim()
            val countMin = header.groupValues[4].toIntOrNull() ?: 0
            val countMax = header.groupValues[5].toIntOrNull() ?: 0
            val choice = MutableChoice(id, playerName, choiceType, countMin, countMax)
            activeChoices[id] = choice
            currentParsingChoice = choice
            return
        }
        val parsing = currentParsingChoice ?: return
        val trimmed = rest.trimStart()
        if (trimmed.startsWith("Source=")) {
            val sourceRaw = trimmed.substringAfter("Source=").trim()
            parseEntityId(sourceRaw)?.let { parsing.sourceEntityId = it }
        } else {
            val entityMatch = CHOICE_ENTITY_REGEX.find(trimmed)
            if (entityMatch != null) {
                parseEntityId(entityMatch.groupValues[1])?.let { id ->
                    if (id > 0 && id !in parsing.entityIds) parsing.entityIds += id
                }
            } else if (trimmed.isNotEmpty()) {
                currentParsingChoice = null
            }
        }
    }

    private fun handleSentChoiceLine(rest: String) {
        val id = SENT_CHOICE_ID_REGEX.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: return
        activeChoices.remove(id)
        if (currentParsingChoice?.id == id) currentParsingChoice = null
    }

    private fun handleEntitiesChosenLine(rest: String) {
        val id = ENTITIES_CHOSEN_REGEX.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: return
        activeChoices.remove(id)
        if (currentParsingChoice?.id == id) currentParsingChoice = null
    }

    private fun handlePowerLine(rest: String) {
        val body = rest.trimStart()
        if (body == "CREATE_GAME" || body == "RESET_GAME") {
            reset()
            return
        }
        if (body.startsWith("tag=")) {
            val entity = currentEntity ?: return
            val match = INLINE_TAG_REGEX.find(body) ?: return
            applyTag(entity, match.groupValues[1], match.groupValues[2])
            return
        }
        if (body == "BLOCK_END" || body.startsWith("META_DATA")) {
            currentEntity = null
            return
        }
        val blockStart = ACTION_BLOCK_START_REGEX.find(body)
        if (blockStart != null) {
            currentEntity = null
            if (blockStart.groupValues[1] == "PLAY") {
                parseEntityId(blockStart.groupValues[2])?.let { entityId ->
                    entities[entityId]?.let(::trackPlayedCard)
                }
            }
            return
        }
        val tagChange = TAG_CHANGE_REGEX.find(body)
        if (tagChange != null) {
            currentEntity = null
            val entityId = parseEntityId(tagChange.groupValues[1]) ?: return
            val entity = getOrCreateEntity(entityId)
            applyTag(entity, tagChange.groupValues[2], tagChange.groupValues[3])
            return
        }
        val hide = HIDE_ENTITY_REGEX.find(body)
        if (hide != null) {
            currentEntity = null
            val entityId = parseEntityId(hide.groupValues[1]) ?: return
            val entity = getOrCreateEntity(entityId)
            applyTag(entity, hide.groupValues[2], hide.groupValues[3])
            return
        }
        val createMatch = CREATE_ENTITY_REGEX.find(body)
        if (createMatch != null) {
            val gameId = createMatch.groupValues[1].toIntOrNull()
            val playerEntityId = createMatch.groupValues[2].toIntOrNull()
            val fullEntityId = createMatch.groupValues[3].toIntOrNull()
            val createdCardId = createMatch.groupValues[4].trim()
            when {
                gameId != null -> {
                    gameEntityId = gameId
                    val entity = getOrCreateEntity(gameId)
                    entity.tags["ENTITY_ID"] = JsonPrimitive(gameId)
                    entity.tags["CARDTYPE"] = JsonPrimitive("GAME")
                    currentEntity = entity
                }
                playerEntityId != null -> {
                    val entity = getOrCreateEntity(playerEntityId)
                    entity.tags["ENTITY_ID"] = JsonPrimitive(playerEntityId)
                    entity.tags["CARDTYPE"] = JsonPrimitive("PLAYER")
                    PLAYER_ENTITY_REGEX.find(body)?.let { match ->
                        val pId = match.groupValues[2].toIntOrNull()
                        if (pId != null) {
                            playerIdToEntityId[pId] = playerEntityId
                            entity.tags["PLAYER_ID"] = JsonPrimitive(pId)
                        }
                        match.groupValues[3].toLongOrNull()?.let { entity.tags["_GAME_ACCOUNT_ID_HI"] = JsonPrimitive(it) }
                        match.groupValues[4].toLongOrNull()?.let { entity.tags["_GAME_ACCOUNT_ID_LO"] = JsonPrimitive(it) }
                    }
                    currentEntity = entity
                }
                fullEntityId != null -> {
                    val entity = getOrCreateEntity(fullEntityId)
                    entity.tags["ENTITY_ID"] = JsonPrimitive(fullEntityId)
                    if (createdCardId.isNotEmpty()) entity.tags["_CARD_ID"] = JsonPrimitive(createdCardId)
                    currentEntity = entity
                }
            }
            return
        }
        val updateMatch = FULL_ENTITY_UPDATE_REGEX.find(body)
            ?: SHOW_ENTITY_REGEX.find(body)
            ?: CHANGE_ENTITY_REGEX.find(body)
        if (updateMatch != null) {
            val entityId = parseEntityId(updateMatch.groupValues[1]) ?: return
            val entity = getOrCreateEntity(entityId)
            val newCardId = updateMatch.groupValues[2].trim()
            if (newCardId.isNotEmpty()) {
                entity.tags["_CARD_ID"] = JsonPrimitive(newCardId)
                completePlayedCardIds(entityId, newCardId)
            }
            currentEntity = entity
            return
        }
        currentEntity = null
    }

    private fun getOrCreateEntity(id: Int): PowerEntity =
        entities.getOrPut(id) {
            PowerEntity(id).apply { tags["ENTITY_ID"] = JsonPrimitive(id) }
        }

    private fun parseEntityId(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed == "GameEntity") return gameEntityId ?: 1
        trimmed.toIntOrNull()?.let { return it }
        ENTITY_ID_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val playerId = playerNameToPlayerId[trimmed]
            ?: playerNameToPlayerId.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value
        if (playerId != null) return playerIdToEntityId[playerId]
        return null
    }

    private fun applyTag(entity: PowerEntity, rawTag: String, rawValue: String) {
        val tag = rawTag.trim()
        val trimmedVal = rawValue.trim()
        val normalizedPrimitive = normalizeTagValue(tag, trimmedVal)
        val oldZone = entity.zone
        val oldController = entity.controller

        entity.tags[tag] = normalizedPrimitive

        if (tag == "ZONE") {
            val newZone = entity.zone
            if (newZone != null && newZone != oldZone) {
                entity.zoneEntrySequence = ++nextZoneSequence
                if (newZone == "GRAVEYARD" && oldZone != "GRAVEYARD") {
                    entity.tags["_ENTER_ZONE_IDX"] = JsonPrimitive(++graveyardEnterIndex)
                    entity.tags["_ENTER_ZONE_TURN"] = JsonPrimitive(currentRoundTurn())
                }
                trackDeckTransition(entity, oldZone, newZone, oldController, entity.controller)
            }
        } else if (tag == "CONTROLLER") {
            val newController = entity.controller
            if (entity.zone == "DECK" && oldController > 0 && newController > 0 && oldController != newController) {
                trackDeckTransition(entity, "DECK", "DECK", oldController, newController)
            }
        } else if (tag == "PLAYER_ID") {
            val pId = entity.intTag("PLAYER_ID")
            if (pId > 0) playerIdToEntityId[pId] = entity.id
        } else if (tag == "STEP" && trimmedVal != "BEGIN_MULLIGAN") {
            activeChoices.entries.removeAll { it.value.choiceType == "MULLIGAN" }
        }
    }

    private fun normalizeTagValue(tag: String, value: String): JsonPrimitive {
        val normalized = if (tag == "CARDRACE" && value == "BEAST") "PET" else value
        normalized.toIntOrNull()?.let { return JsonPrimitive(it) }
        return JsonPrimitive(normalized)
    }

    private fun trackDeckTransition(
        entity: PowerEntity,
        oldZone: String?,
        newZone: String?,
        oldController: Int,
        newController: Int,
    ) {
        val cardKey = entity.cardId.orEmpty()
        if (oldZone == "DECK" && newZone != "DECK" && oldController > 0) {
            incrementCounter(deckPopCounts, oldController, cardKey)
        } else if (oldZone != null && oldZone != "DECK" && newZone == "DECK" && newController > 0) {
            incrementCounter(deckPushCounts, newController, cardKey)
        } else if (oldZone == "DECK" && newZone == "DECK" && oldController > 0 && newController > 0 && oldController != newController) {
            incrementCounter(deckPopCounts, oldController, cardKey)
            incrementCounter(deckPushCounts, newController, cardKey)
        }
    }

    private fun incrementCounter(target: MutableMap<Int, MutableMap<String, Int>>, controller: Int, cardId: String) {
        val map = target.getOrPut(controller) { LinkedHashMap() }
        map[cardId] = (map[cardId] ?: 0) + 1
    }

    private fun currentRoundTurn(): Int {
        val game = (gameEntityId ?: 1).let(entities::get)
            ?: entities.values.firstOrNull { it.cardType == "GAME" }
            ?: return 1
        val numTurns = game.intTag("NUM_TURNS_IN_PLAY")
        if (numTurns > 0) return maxOf(1, numTurns)
        val turn = game.intTag("TURN")
        return maxOf(1, (maxOf(1, turn) + 1) / 2)
    }

    private fun trackPlayedCard(entity: PowerEntity) {
        val controller = entity.controller
        if (controller <= 0) return
        val turn = currentRoundTurn()
        val list = playedCardsByController.getOrPut(controller) { ArrayList() }
        val existing = list.firstOrNull { it.entityId == entity.id && it.playedTurn == turn }
        if (existing != null) {
            if (existing.cardId == null && entity.cardId != null) existing.cardId = entity.cardId
            return
        }
        list += PlayedCardEntry(entity.id, turn, entity.cardId)
    }

    private fun completePlayedCardIds(entityId: Int, cardId: String) {
        if (cardId.isEmpty()) return
        for (entries in playedCardsByController.values) {
            for (entry in entries) {
                if (entry.entityId == entityId && entry.cardId.isNullOrEmpty()) {
                    entry.cardId = cardId
                }
            }
        }
    }

    fun buildSnapshot(localPlayerId: Int, preferredChoiceId: Int? = null): NeteasePowerLogSnapshot {
        val oppPlayerId = entities.values
            .firstOrNull { it.cardType == "PLAYER" && it.intTag("PLAYER_ID").let { id -> id > 0 && id != localPlayerId } }
            ?.intTag("PLAYER_ID")
            ?: if (localPlayerId == 1) 2 else 1

        val activeChoice = selectActiveChoice(localPlayerId, preferredChoiceId)?.snapshot()
        val sortedEntities = entities.values.sortedWith(compareBy<PowerEntity> { it.zoneEntrySequence }.thenBy { it.id })

        val attachmentsByParent = HashMap<Int, MutableList<PowerEntity>>()
        val attachmentIds = HashSet<Int>()
        for (entity in sortedEntities) {
            val parentId = entity.attachedTo
            if (parentId > 0 && entity.zone == "PLAY") {
                val parent = entities[parentId]
                if (parent != null && parent.cardType != "GAME" &&
                    (parent.cardType == "PLAYER" || parent.zone in ATTACHMENT_PARENT_ZONES)
                ) {
                    attachmentIds += entity.id
                    attachmentsByParent.getOrPut(parentId) { ArrayList() } += entity
                }
            }
        }

        val gameGroup = mutableListOf<JsonObject>()
        val myPlayer = mutableListOf<JsonObject>()
        val oppPlayer = mutableListOf<JsonObject>()
        val myHero = mutableListOf<JsonObject>()
        val oppHero = mutableListOf<JsonObject>()
        val myHeroPower = mutableListOf<JsonObject>()
        val oppHeroPower = mutableListOf<JsonObject>()
        val myWeapon = mutableListOf<JsonObject>()
        val oppWeapon = mutableListOf<JsonObject>()
        val myLineup = mutableListOf<JsonObject>()
        val oppLineup = mutableListOf<JsonObject>()
        val myHands = mutableListOf<JsonObject>()
        val oppHands = mutableListOf<JsonObject>()
        val mySecrets = mutableListOf<JsonObject>()
        val oppSecrets = mutableListOf<JsonObject>()
        val myDeck = mutableListOf<JsonObject>()
        val oppDeck = mutableListOf<JsonObject>()
        val myGraveyard = mutableListOf<JsonObject>()
        val oppGraveyard = mutableListOf<JsonObject>()
        val attachments = mutableListOf<JsonObject>()
        val others = mutableListOf<JsonObject>()
        val placedIds = HashSet<Int>()

        fun entityPayload(entity: PowerEntity, includeAttachments: Boolean, stripCardId: Boolean = false): JsonObject {
            placedIds += entity.id
            val children = if (includeAttachments) attachmentsByParent[entity.id].orEmpty() else emptyList()
            return buildJsonObject {
                for ((k, v) in entity.tags) {
                    if (stripCardId && k == "_CARD_ID") continue
                    put(k, v)
                }
                if (children.isNotEmpty()) {
                    put("_ATTACHMENTS", JsonArray(children.map { JsonPrimitive(it.id) }))
                }
            }
        }

        for (entity in sortedEntities) {
            if (entity.id in attachmentIds) {
                attachments += entityPayload(entity, includeAttachments = false)
                placedIds += entity.id
                continue
            }
            val type = entity.cardType
            val zone = entity.zone
            val controller = entity.controller
            when {
                type == "GAME" -> {
                    placedIds += entity.id
                    gameGroup += buildJsonObject {
                        for ((k, v) in entity.tags) {
                            if (k == "_CARD_ID") continue
                            put(k, v)
                        }
                        if ("BUILD_NUMBER" !in entity.tags) put("BUILD_NUMBER", buildNumber ?: 246003)
                        if ("GAME_TYPE" !in entity.tags && gameType != null) put("GAME_TYPE", gameType!!)
                        if ("FormatType" !in entity.tags && formatType != null) put("FormatType", formatType!!)
                        if ("SCENARIO_ID" !in entity.tags) put("SCENARIO_ID", scenarioId ?: 2)
                    }
                }
                type == "PLAYER" -> {
                    val pId = entity.intTag("PLAYER_ID")
                    if (pId == localPlayerId) {
                        myPlayer += entityPayload(entity, includeAttachments = true, stripCardId = true)
                    } else if (pId == oppPlayerId) {
                        oppPlayer += entityPayload(entity, includeAttachments = true, stripCardId = true)
                    }
                }
                zone == "PLAY" && type == "HERO" -> {
                    if (controller == localPlayerId) myHero += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppHero += entityPayload(entity, includeAttachments = true)
                }
                zone == "PLAY" && type == "HERO_POWER" -> {
                    if (controller == localPlayerId) myHeroPower += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppHeroPower += entityPayload(entity, includeAttachments = true)
                }
                zone == "PLAY" && type == "WEAPON" -> {
                    if (controller == localPlayerId) myWeapon += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppWeapon += entityPayload(entity, includeAttachments = true)
                }
                zone == "PLAY" && (type == "MINION" || type == "LOCATION") -> {
                    if (controller == localPlayerId) myLineup += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppLineup += entityPayload(entity, includeAttachments = true)
                }
                zone == "HAND" -> {
                    if (controller == localPlayerId) myHands += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppHands += entityPayload(entity, includeAttachments = true)
                }
                zone == "SECRET" -> {
                    if (controller == localPlayerId) mySecrets += entityPayload(entity, includeAttachments = true)
                    else if (controller == oppPlayerId) oppSecrets += entityPayload(entity, includeAttachments = true)
                }
                zone == "DECK" -> {
                    val deckEntry = buildJsonObject {
                        put("ENTITY_ID", entity.id)
                        entity.cardId?.let { put("_CARD_ID", it) }
                    }
                    placedIds += entity.id
                    if (controller == localPlayerId) myDeck += deckEntry
                    else if (controller == oppPlayerId) oppDeck += deckEntry
                }
                zone == "GRAVEYARD" && type == "MINION" -> {
                    if (controller == localPlayerId) myGraveyard += entityPayload(entity, includeAttachments = false)
                    else if (controller == oppPlayerId) oppGraveyard += entityPayload(entity, includeAttachments = false)
                }
            }
        }

        if (activeChoice != null && activeChoice.choiceType.equals("GENERAL", ignoreCase = true)) {
            val extraIds = buildSet {
                activeChoice.entityIds.forEach { if (it > 0) add(it) }
                if (activeChoice.sourceEntityId > 0) add(activeChoice.sourceEntityId)
            }
            for (extraId in extraIds) {
                if (extraId in placedIds) continue
                val entity = entities[extraId] ?: continue
                if (entity.cardType == "GAME" || entity.cardType == "PLAYER") continue
                others += entityPayload(entity, includeAttachments = false)
            }
        }

        val entitiesJson = buildJsonObject {
            fun putGroup(name: String, items: List<JsonObject>) {
                if (items.isNotEmpty()) put(name, JsonArray(items))
            }
            putGroup("game", gameGroup)
            putGroup("my_player", myPlayer)
            putGroup("opp_player", oppPlayer)
            putGroup("my_hero", myHero)
            putGroup("my_hero_power", myHeroPower)
            putGroup("my_weapon", myWeapon)
            putGroup("my_lineup", myLineup)
            putGroup("my_hands", myHands)
            putGroup("my_secrets", mySecrets)
            putGroup("my_deck", myDeck)
            putGroup("my_graveyard", myGraveyard)
            putGroup("opp_hero", oppHero)
            putGroup("opp_hero_power", oppHeroPower)
            putGroup("opp_weapon", oppWeapon)
            putGroup("opp_lineup", oppLineup)
            putGroup("opp_hands", oppHands)
            putGroup("opp_secrets", oppSecrets)
            putGroup("opp_deck", oppDeck)
            putGroup("opp_graveyard", oppGraveyard)
            putGroup("attachments", attachments)
            putGroup("others", others)
        }

        val playedJson = buildJsonObject {
            val myPlayed = playedCardsByController[localPlayerId].orEmpty()
            val oppPlayed = playedCardsByController[oppPlayerId].orEmpty()
            if (myPlayed.isNotEmpty()) put("my", JsonArray(myPlayed.map { it.toJson() }))
            if (oppPlayed.isNotEmpty()) put("opp", JsonArray(oppPlayed.map { it.toJson() }))
        }

        val counterJson = buildJsonObject {
            put("my_deck", buildDeckCounterSide(localPlayerId, myDeck.size))
            put("opp_deck", buildDeckCounterSide(oppPlayerId, oppDeck.size))
        }

        return NeteasePowerLogSnapshot(
            entities = entitiesJson,
            playedCards = playedJson,
            deckCardCounter = counterJson,
            localController = localPlayerId,
            activeChoice = activeChoice,
            buildNumber = buildNumber,
            gameType = gameType,
            formatType = formatType,
            scenarioId = scenarioId,
        )
    }

    private fun buildDeckCounterSide(controller: Int, currentDeckCount: Int): JsonObject {
        val rawPush = deckPushCounts[controller].orEmpty()
        val rawPop = deckPopCounts[controller].orEmpty()
        val pushSum = rawPush.values.sum()
        val popSum = rawPop.values.sum()
        val initialSize = maxOf(0, currentDeckCount + popSum - pushSum)

        val pushObj = buildJsonObject {
            val unknownPush = (rawPush[""] ?: 0) + initialSize
            if (unknownPush > 0) put("", unknownPush)
            for ((cardId, count) in rawPush) {
                if (cardId.isNotEmpty() && count > 0) put(cardId, count)
            }
        }
        val popObj = buildJsonObject {
            for ((cardId, count) in rawPop) {
                if (count > 0) put(cardId, count)
            }
        }
        return buildJsonObject {
            put("push", pushObj)
            put("pop", popObj)
        }
    }

    private fun selectActiveChoice(localPlayerId: Int, preferredChoiceId: Int?): MutableChoice? {
        if (preferredChoiceId != null) {
            activeChoices[preferredChoiceId]?.let { return it }
        }
        val localName = playerIdToName[localPlayerId]
        val list = activeChoices.values.toList()
        if (localName != null) {
            list.lastOrNull { it.playerName.equals(localName, ignoreCase = true) }?.let { return it }
        }
        return list.lastOrNull()
    }

    companion object {
        private const val DEBUG_PRINT_POWER = "GameState.DebugPrintPower() - "
        private const val DEBUG_PRINT_GAME = "GameState.DebugPrintGame() - "
        private const val DEBUG_PRINT_CHOICES = "GameState.DebugPrintEntityChoices() - "
        private const val SEND_CHOICES = "GameState.SendChoices() - "
        private const val DEBUG_ENTITIES_CHOSEN = "GameState.DebugPrintEntitiesChosen() - "
        private const val MAX_LINE_CHARS = 16384

        private val ATTACHMENT_PARENT_ZONES = setOf("PLAY", "HAND", "SECRET")

        private val BUILD_NUMBER_REGEX = Regex("""BuildNumber=(\d+)\b""")
        private val GAME_TYPE_REGEX = Regex("""GameType=(\S+)""")
        private val FORMAT_TYPE_REGEX = Regex("""FormatType=(\S+)""")
        private val SCENARIO_ID_REGEX = Regex("""ScenarioID=(\d+)""")
        private val PLAYER_NAME_REGEX = Regex("""PlayerID=(\d+),\s*PlayerName=(.{1,60})$""")

        private val CHOICE_HEADER_REGEX = Regex(
            """^\s*id=(\d+)\s+Player=(.+?)\s+TaskList=\d*\s+ChoiceType=(\S+)\s+CountMin=(\d+)\s+CountMax=(\d+)""",
        )
        private val CHOICE_ENTITY_REGEX = Regex("""^Entities\[\d+\]=(.+)$""")
        private val SENT_CHOICE_ID_REGEX = Regex("""id=(\d+)\b""")
        private val ENTITIES_CHOSEN_REGEX = Regex("""id=(\d+)\b""")

        private val CREATE_ENTITY_REGEX = Regex(
            """^(?:GameEntity EntityID=(\d+)|Player EntityID=(\d+).*|FULL_ENTITY - Creating ID=(\d+) CardID=(.*))$""",
        )
        private val PLAYER_ENTITY_REGEX = Regex(
            """^Player EntityID=(\d+) PlayerID=(\d+) GameAccountId=(?:\[hi=(\d+) lo=(\d+)\]|\[\])""",
        )
        private val FULL_ENTITY_UPDATE_REGEX = Regex("""^FULL_ENTITY - Updating (.+?) CardID=(.*)$""")
        private val SHOW_ENTITY_REGEX = Regex("""^SHOW_ENTITY - Updating Entity=(.+?) CardID=(\S*)$""")
        private val CHANGE_ENTITY_REGEX = Regex("""^CHANGE_ENTITY - Updating Entity=(.+?) CardID=(\S*)$""")
        private val HIDE_ENTITY_REGEX = Regex("""^HIDE_ENTITY - Entity=(.+?) tag=(\S+) value=(.*?)\s*$""")
        private val INLINE_TAG_REGEX = Regex("""^tag=(\S+) value=(.*?)(?: DEF CHANGE)?\s*$""")
        private val TAG_CHANGE_REGEX = Regex("""^TAG_CHANGE Entity=(.+?) tag=(\S+) value=(.*?)(?: DEF CHANGE)?\s*$""")
        private val ACTION_BLOCK_START_REGEX = Regex(
            """^BLOCK_START BlockType=(\S+) Entity=(.+?) EffectCardId=.*? EffectIndex=-?\d+ Target=(.+?) SubOption=""",
        )
        private val ENTITY_ID_REGEX = Regex("""(?:^|[\s\[])id=(\d+)(?=\s|\]|$)""")

        private val MULLIGAN_PLAYER_CHOICE_REGEX = Regex(
            """DebugPrintEntityChoices\(\) - id=(\d+) Player=(.{1,60}?) TaskList=\d* ChoiceType=MULLIGAN\b""",
        )
        private val SENT_MULLIGAN_REGEX = Regex("""SendChoices\(\) - id=(\d+) ChoiceType=MULLIGAN\b""")
        private val BATTLE_TAG_REGEX = Regex("""^[^\p{Cc}<>\[\]#]{1,52}#\d{2,10}$""")

        fun validBattleTag(value: String?): String? =
            value?.trim()?.takeIf { BATTLE_TAG_REGEX.matches(it) }

        /** Extracts the local player's BattleTag from the Power.log prefix using PlayerName and MULLIGAN choices. */
        fun readLocalBattleTag(
            lines: Sequence<String>,
            lastLine: Int,
            localPlayerId: Int,
            mulliganChoiceId: Int? = null,
            checkCancelled: () -> Unit = {},
        ): String? {
            val mulliganChoices = HashMap<Int, String>()
            var metadataTag: String? = null
            var localSentTag: String? = null
            var count = 0
            for (raw in lines) {
                if (++count > lastLine) break
                if ((count and 255) == 0) checkCancelled()
                val line = raw.removePrefix("\uFEFF")
                if (line.length > MAX_LINE_CHARS) return null
                when {
                    "DebugPrintPower() - CREATE_GAME" in line -> {
                        mulliganChoices.clear()
                        metadataTag = null
                        localSentTag = null
                    }
                    "PlayerName=" in line -> {
                        val match = PLAYER_NAME_REGEX.find(line) ?: continue
                        val candidateId = match.groupValues[1].toIntOrNull()
                        if (candidateId == localPlayerId) {
                            metadataTag = validBattleTag(match.groupValues[2])
                        }
                    }
                    "DebugPrintEntityChoices()" in line -> {
                        val match = MULLIGAN_PLAYER_CHOICE_REGEX.find(line) ?: continue
                        val id = match.groupValues[1].toIntOrNull() ?: continue
                        val tag = validBattleTag(match.groupValues[2]) ?: continue
                        if (mulliganChoices.size < 16) mulliganChoices[id] = tag
                    }
                    "SendChoices()" in line -> {
                        val match = SENT_MULLIGAN_REGEX.find(line) ?: continue
                        val id = match.groupValues[1].toIntOrNull() ?: continue
                        mulliganChoices[id]?.let { localSentTag = it }
                    }
                }
            }
            if (metadataTag != null && localSentTag != null && !metadataTag.equals(localSentTag, ignoreCase = true)) {
                return null
            }
            if (mulliganChoiceId != null) {
                val presented = mulliganChoices[mulliganChoiceId]
                if (presented != null) {
                    val known = localSentTag ?: metadataTag
                    if (known != null && !known.equals(presented, ignoreCase = true)) return null
                    return presented
                }
            }
            return localSentTag ?: metadataTag
        }
    }
}
