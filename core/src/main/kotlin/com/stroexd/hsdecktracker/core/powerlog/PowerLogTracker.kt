package com.stroexd.hsdecktracker.core.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.stats.MatchResult
import java.security.MessageDigest

data class PowerLogCard(
    val cardId: String,
    val attack: Int? = null,
    val cost: Int? = null,
    val health: Int? = null,
    val taunt: Boolean = false,
    val canAttackHero: Boolean? = null,
    val canAttackMinions: Boolean? = null,
    val remainingAttacks: Int? = null,
    val playOptionReported: Boolean? = null,
    val entityId: Int? = null,
    val combatState: PowerLogCombatState? = null,
)

data class PowerLogCombatState(
    val position: Int?,
    val maxHealth: Int?,
    val damage: Int,
    val armor: Int,
    val exhausted: Boolean?,
    val turnsInPlay: Int?,
)

data class PowerLogHeroPower(val entityId: Int, val cardId: String?)

/** A visible entity from a completed PLAY block, with the initiating log turn and zone. */
data class PowerLogPublicPlay(
    val playerId: Int,
    val entityId: Int,
    val cardId: String,
    val turn: Int?,
    val sourceZone: String?,
)

data class PowerLogActionTarget(
    val index: Int,
    val entityId: Int?,
    val error: String?,
    val errorParam: Int?,
)

data class PowerLogActionSubOption(
    val index: Int,
    val entityId: Int?,
    val error: String?,
    val errorParam: Int?,
    val targets: List<PowerLogActionTarget> = emptyList(),
)

data class PowerLogActionOption(
    val index: Int,
    val type: String,
    val entityId: Int?,
    val error: String?,
    val errorParam: Int?,
    val subOptions: List<PowerLogActionSubOption> = emptyList(),
    val targets: List<PowerLogActionTarget> = emptyList(),
)

/** Observed prefix of the active local player's option packet. The revision is local to the match. */
data class PowerLogActionOptions(
    val packetId: Int,
    val revision: Long,
    val options: List<PowerLogActionOption>,
)

enum class PowerLogDeckChangeKind { TAKEN, RETURNED }

data class PowerLogDeckChange(val cardId: String, val kind: PowerLogDeckChangeKind, val turn: Int)

/** A logged move into or out of a player's deck. Unrevealed card identities are null. */
data class PowerLogDeckEvent(
    val entityId: Int,
    val playerId: Int,
    val kind: PowerLogDeckChangeKind,
    val turn: Int?,
    val cardId: String?,
)

enum class PowerLogHandOrigin { OPENING, COIN, DRAWN, GENERATED, RETURNED, OTHER, UNKNOWN }

data class PowerLogHandMark(
    val entityId: Int,
    val position: Int?,
    val enteredTurn: Int?,
    val origin: PowerLogHandOrigin,
    val cardId: String? = null,
    val creatorCardId: String? = null,
)

data class PowerLogOpeningCard(val cardId: String, val position: Int, val cost: Int? = null, val entityId: Int? = null)

data class PowerLogOpeningChoice(
    val choiceId: Int,
    val cards: List<PowerLogOpeningCard>,
    val friendlyHeroClass: HsClass? = null,
    val opponentHeroClass: HsClass? = null,
    val playerId: Int? = null,
    val sourceEntityId: Int? = null,
)

data class PowerLogChoiceOption(val index: Int, val entityId: Int, val cardId: String?)

/** Observed options for a local Discover or other in-match choice. */
data class PowerLogGeneralChoice(
    val choiceId: Int,
    val revision: Long,
    val sourceEntityId: Int?,
    val minSelections: Int,
    val maxSelections: Int,
    val options: List<PowerLogChoiceOption>,
)

data class PowerLogSide(
    val hand: List<PowerLogCard> = emptyList(),
    val handCount: Int = 0,
    val graveyard: List<PowerLogCard> = emptyList(),
    val secrets: List<PowerLogCard> = emptyList(),
    val secretCount: Int = 0,
    val board: List<PowerLogCard> = emptyList(),
    val boardAttack: Int = 0,
    val heroClass: HsClass? = null,
    val deckCount: Int? = null,
    val played: List<PowerLogCard> = emptyList(),
    val heroEffectiveHealth: Int? = null,
    val deckChanges: List<PowerLogDeckChange> = emptyList(),
    val handMarks: List<PowerLogHandMark> = emptyList(),
    /** Attack budget for the active side. Totals are independent of eligible defenders and secrets. */
    val availableAttack: Int? = null,
    val availableAttackUpperBound: Int? = null,
    val heroAttack: Int? = null,
    val weaponAttack: Int? = null,
    val weaponDurability: Int? = null,
    val isCurrentTurn: Boolean? = null,
    val availableMana: Int? = null,
    val boardOccupiedSlots: Int = board.size,
    val heroEntityId: Int? = null,
    val heroCardId: String? = null,
    val heroCombatState: PowerLogCombatState? = null,
    val heroPower: PowerLogHeroPower? = null,
    val playerId: Int? = null,
    val playerEntityId: Int? = null,
    val weaponEntityId: Int? = null,
    val weaponCardId: String? = null,
    val weaponCombatState: PowerLogCombatState? = null,
    val secretObservations: List<PowerLogSecretObservation> = emptyList(),
)

/** Starting-deck entities for the local player. Unrevealed identities are omitted. */
data class PowerLogStartingDeck(val size: Int, val revealedCards: List<String>)

data class PowerLogSnapshot(
    val gameNumber: Long,
    val format: GameFormat,
    val turn: Int,
    val friendly: PowerLogSide,
    val opponent: PowerLogSide,
    val result: MatchResult?,
    /** When false, the sides are ordered by player number with no local identity or result. */
    val localPlayerIdentified: Boolean,
    val openingChoice: PowerLogOpeningChoice? = null,
    val actionOptions: PowerLogActionOptions? = null,
    val generalChoice: PowerLogGeneralChoice? = null,
    val publicPlays: List<PowerLogPublicPlay> = emptyList(),
    val deckEvents: List<PowerLogDeckEvent> = emptyList(),
    val startingDeck: PowerLogStartingDeck? = null,
    val gameComplete: Boolean = false,
)

/** Reduces the game's Power.log stream. Feed complete lines in order from one session. */
class PowerLogTracker(private val captureCombatTags: Boolean = false) {
    var snapshot: PowerLogSnapshot? = null
        private set

    private var sequence = 0L
    private var game: Game? = null
    private var nextFriendlyPlayerId: Int? = null

    /** Explicit numeric tags observed for public entities still on the battlefield. */
    fun observedBattlefieldTags(): Map<Int, Map<String, Int>> = game?.observedBattlefieldTags().orEmpty()

    fun reset() {
        game = null
        snapshot = null
        nextFriendlyPlayerId = null
    }

    /** An explicitly identified player number for the current match or the first match after reset. */
    fun setFriendlyPlayerId(id: Int?): PowerLogSnapshot? {
        val selected = id?.takeIf { it in 1..2 }
        val current = game
        if (current == null) nextFriendlyPlayerId = selected else current.explicitPlayer = selected
        return publish()
    }

    /** Returns a changed snapshot. Check [snapshot] also to observe a match being cleared. */
    fun accept(line: String): PowerLogSnapshot? {
        if (line.length > MAX_LINE_LENGTH) return null
        val match = LOG_LINE.matchEntire(line.trim()) ?: return null
        val stream = match.groupValues[1]
        val message = match.groupValues[2].trim()
        if (stream == "DebugPrintPower" && message in START_OR_RESET) {
            if (message == "CREATE_GAME") {
                game = Game(++sequence, nextFriendlyPlayerId, captureCombatTags)
                nextFriendlyPlayerId = null
                snapshot = null
                return null
            }
            val active = game
            if (active == null) {
                nextFriendlyPlayerId = null
                snapshot = null
                return null
            }
            active.onResetGame()
            return publish()
        }
        val current = game ?: return null
        when (stream) {
            "DebugPrintGame" -> current.onMetadata(message)
            "DebugPrintPower" -> current.onPower(message)
            "DebugPrintOptions" -> current.onOptions(message)
            "DebugPrintChoices", "DebugPrintEntityChoices" -> current.onChoice(message)
            "SendChoices" -> current.onSentChoice(message)
            "DebugPrintEntitiesChosen" -> current.onEntitiesChosen(message)
            "SendOption" -> current.invalidateOptions()
        }
        return publish()
    }

    private fun publish(): PowerLogSnapshot? {
        val next = game?.toSnapshot()
        if (next == snapshot) return null
        snapshot = next
        return next
    }

    private class Game(private val number: Long, var explicitPlayer: Int?, private val captureCombatTags: Boolean) {
        private var isConstructed = false
        private var format: GameFormat? = null
        private var gameEntity: Int? = null
        private var firstPlayer: Int? = null
        private var openingDealDepth: Int? = null
        private var openingCoinId: Int? = null
        private val playerEntities = HashMap<Int, Int>()
        private val aliasedPlayers = HashMap<String, Int>()
        private val entities = LinkedHashMap<Int, Entity>()
        private val deckObserved = HashSet<Int>()
        private val openingHeroClasses = HashMap<Int, HsClass>()
        private val deckMoves = ArrayList<DeckMove>()
        private val observedDeckMoves = ArrayList<ObservedDeckMove>()
        private var plays = emptyList<PowerLogPublicPlay>()
        private val blockPlays = ArrayDeque<OpenPlay?>()
        private val acceptedOptions = HashSet<Int>()
        private var actionOptions: ActionOptions? = null
        private var optionRevision = 0L
        private var currentEntity: Entity? = null
        private var openingShowCardId: String? = null
        private var inferredPlayer: Int? = null
        private var turn = 0
        private var turnObserved = false
        private var gameStep: String? = null
        private var currentChoiceId: Int? = null
        private var sentChoiceId: Int? = null
        private val mulliganChoices = HashMap<Int, Int>()
        private val openingChoices = mutableMapOf<Int, OpeningChoices>()
        private var openingClosed = false
        private val originalDeckEntities = ArrayList<Entity>()
        private var generalChoice: GeneralChoice? = null
        private var incomingGeneralChoice: GeneralChoice? = null
        private var choiceRevision = 0L
        private var inGameReset = false
        private var gameResetBlockDepth: Int? = null
        private var pendingResetEntityTags: MutableList<Pair<String, String>>? = null
        private var gameComplete = false

        fun onResetGame() {
            inGameReset = true
            gameResetBlockDepth = blockPlays.size.takeIf { it > 0 }
            pendingResetEntityTags = null
            invalidateOptions()
            clearGeneralChoice()
            currentEntity = null
            openingShowCardId = null
            currentChoiceId = null
            sentChoiceId = null
            for (entity in entities.values) {
                if (entity.id != gameEntity) entity.resetForRewind()
            }
        }

        fun onMetadata(message: String) {
            PLAYER_META.matchEntire(message)?.let { match ->
                val player = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..2 } ?: return
                alias(match.groupValues[2])?.let { aliasedPlayers[it] = player }
                return
            }
            val key = message.substringBefore('=').trim()
            val value = message.substringAfter('=', "").trim()
            when (key) {
                "GameType" -> isConstructed = value in CONSTRUCTED_GAME_TYPES
                "FormatType" -> format = when (value) {
                    "FT_STANDARD", "2" -> GameFormat.STANDARD
                    "FT_WILD", "1" -> GameFormat.WILD
                    else -> null
                }
            }
        }

        fun onPower(message: String) {
            invalidateOptions()
            if (message.startsWith("tag=")) {
                val tag = TAG_VALUE.matchEntire(message) ?: return
                val activeEntity = currentEntity
                if (activeEntity != null) {
                    applyTag(activeEntity, tag.groupValues[1], tag.groupValues[2])
                    return
                }
                val pending = pendingResetEntityTags ?: return
                val tagName = tag.groupValues[1]
                val tagValue = tag.groupValues[2]
                val resolvedId = when (tagName) {
                    "ENTITY_ID" -> tagValue.toIntOrNull()
                    "PLAYER_ID" -> tagValue.toIntOrNull()?.let(playerEntities::get)
                    else -> null
                }
                val resolved = resolvedId?.let(::entity)
                if (resolved != null) {
                    currentEntity = resolved
                    pendingResetEntityTags = null
                    for ((k, v) in pending) applyTag(resolved, k, v)
                    applyTag(resolved, tagName, tagValue)
                } else {
                    pending += tagName to tagValue
                }
                return
            }
            currentEntity = null
            pendingResetEntityTags = null
            openingShowCardId = null
            when {
                message.startsWith("GameEntity ") -> {
                    val id = GAME_ENTITY.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull() ?: return
                    gameEntity = id
                    currentEntity = entity(id)
                }
                message.startsWith("Player ") -> {
                    val player = PLAYER.matchEntire(message) ?: return
                    val id = player.groupValues[1].toIntOrNull() ?: return
                    val playerId = player.groupValues[2].toIntOrNull()?.takeIf { it in 1..2 } ?: return
                    playerEntities[playerId] = id
                    currentEntity = entity(id)?.also {
                        it.kind = "PLAYER"
                        it.controller = playerId
                    }
                }
                message.startsWith("TAG_CHANGE ") -> {
                    val match = TAG_CHANGE.matchEntire(message) ?: return
                    val (_, reference, tag, value) = match.groupValues
                    val target = findEntity(reference) ?: identifyNamedPlayer(reference, tag, value) ?: return
                    updateDescriptor(target, reference)
                    applyTag(target, tag, value)
                }
                message.startsWith("HIDE_ENTITY ") -> {
                    val match = HIDE_ENTITY.matchEntire(message) ?: return
                    val target = findEntity(match.groupValues[1]) ?: return
                    val previousCardId = target.cardId
                    val previouslyExposedHandCard = target.publicHandCardId?.takeIf { it == previousCardId }
                    val wasPublic = target.identityPublic || (previousCardId != null && previousCardId == target.publicHandCardId)
                    target.cardId = null
                    target.identityPublic = false
                    target.freshReveal = false
                    target.publicHandCardId = null
                    target.battlefieldCardId = null
                    target.openingRevealCardId = null
                    applyTag(target, match.groupValues[2], match.groupValues[3], previousCardId, wasPublic)
                    if (target.zone == "SECRET" && previouslyExposedHandCard != null) {
                        target.publicSecretCardId = previouslyExposedHandCard
                    }
                }
                message.startsWith("FULL_ENTITY ") || message.startsWith("SHOW_ENTITY ") ||
                    message.startsWith("CHANGE_ENTITY ") -> assignEntity(message)
                message.startsWith("BLOCK_START ") -> {
                    val play = if (message.startsWith("BLOCK_START BlockType=PLAY ")) {
                        val reference = message.substringAfter(" Entity=", "").substringBefore(" EffectCardId=")
                        findEntity(reference)?.let {
                            updateDescriptor(it, reference)
                            OpenPlay(it, turn.takeIf { turnObserved }, it.zone)
                        }
                    } else {
                        null
                    }
                    if (blockPlays.size < MAX_ENTITIES) blockPlays.addLast(play)
                }
                message == "BLOCK_END" -> {
                    if (openingDealDepth == blockPlays.size) openingDealDepth = null
                    if (gameResetBlockDepth == blockPlays.size) {
                        inGameReset = false
                        gameResetBlockDepth = null
                        pendingResetEntityTags = null
                    }
                    // Wait for the play to finish before exposing its identity. A hidden spell can
                    // pass through PLAY before moving to SECRET.
                    val play = blockPlays.removeLastOrNull() ?: return
                    val target = play.entity
                    if (!target.identityPublic || target.zone !in PUBLIC_ZONES) return
                    val player = target.controller ?: return
                    val cardId = target.cardId ?: return
                    target.battlefieldCardId = cardId
                    if (plays.size < MAX_ENTITIES) {
                        plays += PowerLogPublicPlay(player, target.id, cardId, play.turn, play.sourceZone)
                    }
                }
            }
        }

        fun onOptions(message: String) {
            if (message.startsWith("id=")) {
                invalidateOptions()
                val packetId = message.substringAfter('=').toIntOrNull()?.takeIf { it >= 0 } ?: return
                val active = activePlayer()
                if (active != null && active == localPlayer()) clearGeneralChoice()
                actionOptions = ActionOptions(packetId, ++optionRevision)
                return
            }
            val packet = actionOptions ?: return
            OPTION_ROW.matchEntire(message)?.let {
                val (index, type, reference, error, errorParam) = it.destructured
                val optionIndex = index.toIntOrNull() ?: return
                val id = actionEntityId(reference)
                if (type == "POWER" && error == "NONE" && id != null) confirmOption(id, reference)
                packet.addOption(PowerLogActionOption(optionIndex, type, id, error.ifEmpty { null }, errorParam.toIntOrNull()))
                return
            }
            val (kind, index, reference, error, errorParam) = OPTION_CHILD_ROW.matchEntire(message)?.destructured ?: return
            val childIndex = index.toIntOrNull() ?: return
            val id = actionEntityId(reference)
            if (kind == "subOption") {
                packet.addSubOption(PowerLogActionSubOption(childIndex, id, error.ifEmpty { null }, errorParam.toIntOrNull()))
            } else {
                packet.addTarget(PowerLogActionTarget(childIndex, id, error.ifEmpty { null }, errorParam.toIntOrNull()))
            }
        }

        fun invalidateOptions() {
            acceptedOptions.clear()
            actionOptions = null
        }

        private fun confirmOption(id: Int, reference: String) {
            val target = entities[id] ?: return
            val zone = DESCRIPTOR_ZONE.findAll(reference).lastOrNull()?.groupValues?.get(1) ?: return
            if (zone == target.zone && target.controller == activePlayer()) acceptedOptions += id
        }

        private fun assignEntity(message: String) {
            val reference = message.substringBeforeLast(" CardID=", "")
            if (reference.isEmpty()) return
            val creating = reference.startsWith("FULL_ENTITY - Creating ID=")
            val id = if (creating) {
                reference.substringAfterLast('=').toIntOrNull()
            } else {
                entityId(reference.substringAfter("Updating ").removePrefix("Entity="))
            }
            if (id == null) {
                if (inGameReset && !creating && message.startsWith("FULL_ENTITY - Updating ")) {
                    pendingResetEntityTags = mutableListOf()
                }
                return
            }
            val target = entity(id) ?: return
            currentEntity = target
            if (creating) {
                target.wasCreated = true
                target.createdInOpeningDeal = openingDealDepth != null && openingDealDepth == blockPlays.size
            }
            val card = message.substringAfterLast(" CardID=").takeIf { CARD_ID.matches(it) }
            val isChange = message.startsWith("CHANGE_ENTITY")
            if (isChange) {
                target.originalDeckIdentityUncertain = true
                target.publicSecretCardId = null
            }
            if (creating || isChange) target.observedTags = null
            if (isChange && (card == null || card != target.cardId)) {
                target.hasCoinTag = false
                if (target.id == openingCoinId) openingCoinId = null
                target.pendingDeckMove = null
                target.initialDeckMove = null
                target.initiallyInDeck = false
                target.cost = null
                target.cardId = null
                target.openingRevealCardId = null
                if (target.zone == "HAND") target.publicHandCardId = null
            }
            if (card != null) {
                if (target.zone == "DECK" && message.startsWith("SHOW_ENTITY") && collectingOpeningEvidence()) {
                    openingShowCardId = card
                }
                val public = target.cardId == card && target.identityPublic
                if (target.zone == "HAND" && card != target.publicHandCardId) target.publicHandCardId = null
                target.cardId = card
                target.identityPublic = public || target.zone == "PLAY"
                target.freshReveal = message.startsWith("SHOW_ENTITY") || message.startsWith("CHANGE_ENTITY")
            }
            updateDescriptor(target, reference.substringAfter("Updating ", ""))
            if (target.freshReveal && target.zone == "GRAVEYARD") target.identityPublic = true
            observeEntity(target)
        }

        private fun findEntity(reference: String): Entity? {
            val id = when (reference) {
                "GameEntity" -> gameEntity
                else -> entityId(reference) ?: alias(reference)?.let(aliasedPlayers::get)?.let(playerEntities::get)
            } ?: return null
            return entity(id)
        }

        private fun identifyNamedPlayer(reference: String, tag: String, value: String): Entity? {
            if (reference == "GameEntity" || reference.toIntOrNull() != null) return null
            val key = alias(reference) ?: return null
            val candidates = playerEntities.filterValues { entities[it]?.currentPlayer == true }.keys
            val player = when {
                tag in CURRENT_PLAYER_TAGS && value == "0" -> candidates.singleOrNull()
                tag in CURRENT_PLAYER_TAGS && value == "1" -> (playerEntities.keys - aliasedPlayers.values.toSet()).singleOrNull()
                tag in RESOURCES_TAGS && gameStep in START_OF_TURN_STEPS -> candidates.singleOrNull()
                else -> null
            } ?: return null
            if (player in aliasedPlayers.values) return null
            val id = playerEntities[player] ?: return null
            return entities[id]?.also { aliasedPlayers[key] = player }
        }

        private fun entity(id: Int): Entity? {
            if (id !in 1..MAX_ENTITIES) return null
            return entities.getOrPut(id) { Entity(id) }
        }

        private fun updateDescriptor(target: Entity, reference: String) {
            if (!reference.trimStart().startsWith("[")) return
            if (target.controller == null) {
                target.controller = CONTROLLER.find(reference)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2 }
            }
            if (target.cardId == null) {
                target.cardId = DESCRIPTOR_CARD.find(reference)?.groupValues?.get(1)?.takeIf { CARD_ID.matches(it) }
            }
            if ((target.position ?: 0) == 0 && DESCRIPTOR_ZONE.findAll(reference).lastOrNull()?.groupValues?.get(1) == target.zone) {
                POSITION.findAll(reference).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { target.position = it }
            }
        }

        private fun applyTag(
            target: Entity,
            tag: String,
            value: String,
            cardAtZoneChange: String? = target.cardId,
            wasPublic: Boolean = target.identityPublic || (cardAtZoneChange != null && cardAtZoneChange == target.publicHandCardId),
        ) {
            if (captureCombatTags) {
                val key = AUDIT_COMBAT_TAGS[tag]
                if (key != null) {
                    value.toIntOrNull()?.let { number ->
                        val observed = target.observedTags ?: HashMap<String, Int>().also { target.observedTags = it }
                        observed[key] = number
                    }
                }
            }
            when (tag) {
                "CONTROLLER", "50" -> {
                    val oldController = target.controller
                    val newController = value.toIntOrNull()?.takeIf { it in 1..2 }
                    if (target.zone == "SECRET" && newController != null && oldController != null &&
                        oldController != newController && oldController == localPlayer()
                    ) {
                        target.publicSecretCardId = target.cardId ?: target.publicSecretCardId
                    }
                    if (target.zone == "DECK" && oldController != null && newController != null &&
                        oldController != newController && observedDeckMoves.size < MAX_ENTITIES - 1
                    ) {
                        recordDeckEvent(target, oldController, PowerLogDeckChangeKind.TAKEN, target.cardId)
                        recordDeckEvent(target, newController, PowerLogDeckChangeKind.RETURNED, target.cardId)
                    }
                    target.controller = newController
                }
                "ZONE", "49" -> moveEntity(target, value, cardAtZoneChange, wasPublic)
                "ZONE_POSITION", "263" -> target.position = value.toIntOrNull()?.takeIf { it >= 0 }
                "CARDTYPE", "202" -> target.kind = when (value) {
                    "2" -> "PLAYER"
                    "3" -> "HERO"
                    "4" -> "MINION"
                    "5" -> "SPELL"
                    "7" -> "WEAPON"
                    "10" -> "HERO_POWER"
                    "39" -> "LOCATION"
                    else -> value
                }
                "ATK", "47" -> target.attack = value.toIntOrNull()?.takeIf { it >= 0 }
                "COST", "48" -> target.cost = value.toIntOrNull()?.takeIf { it >= 0 }
                "HEALTH", "45" -> target.health = value.toIntOrNull()?.takeIf { it >= 0 }
                "DAMAGE", "44" -> target.damage = value.toIntOrNull()?.takeIf { it >= 0 } ?: target.damage
                "ARMOR", "292" -> target.armor = value.toIntOrNull()?.takeIf { it >= 0 } ?: target.armor
                "HERO_ENTITY", "27" -> target.heroEntity = value.toIntOrNull()?.takeIf { it in 1..MAX_ENTITIES }
                "STEP", "19" -> if (target.id == gameEntity) {
                    if (gameStep in MULLIGAN_STEPS && value !in MULLIGAN_STEPS) closeOpening()
                    gameStep = value
                }
                "TURN", "20" -> if (target.id == gameEntity) value.toIntOrNull()?.takeIf { it >= 0 }?.let {
                    if (it != turn) {
                        entities.values.forEach { entity -> entity.attacksUsed = 0 }
                        clearGeneralChoice()
                    }
                    turn = it
                    turnObserved = true
                }
                "CURRENT_PLAYER", "23" -> target.currentPlayer = flag(value)
                "FIRST_PLAYER", "24" -> if (flag(value) == true && firstPlayer == null && collectingOpeningEvidence()) {
                    firstPlayer = target.controller.takeIf { target.kind == "PLAYER" }
                    if (firstPlayer != null) openingDealDepth = blockPlays.size.takeIf { it > 0 }
                }
                "RESOURCES", "26" -> target.resources = value.toIntOrNull()?.takeIf { it >= 0 }
                "RESOURCES_USED", "25" -> target.resourcesUsed = value.toIntOrNull() ?: target.resourcesUsed
                "TEMP_RESOURCES", "295" -> target.tempResources = value.toIntOrNull() ?: target.tempResources
                "MULLIGAN_STATE", "305" -> target.mulliganDone = value == "DONE" || value == "4"
                "COIN_CARD" -> target.hasCoinTag = flag(value) == true
                "CREATOR", "313", "DISPLAYED_CREATOR", "385" -> {
                    val reference = value.toIntOrNull()?.takeIf { it > 0 }
                    val creator = reference?.takeIf { it <= MAX_ENTITIES && it != target.id }
                    if (tag == "CREATOR" || tag == "313") target.creatorEntityId = creator
                    else target.displayedCreatorEntityId = creator
                    if (reference != null) {
                        target.hasCreator = true
                        if (target.zone == "HAND" &&
                            (target.handOrigin == PowerLogHandOrigin.UNKNOWN || target.handOrigin == PowerLogHandOrigin.OTHER)
                        ) {
                            target.handOrigin = PowerLogHandOrigin.GENERATED
                        }
                    }
                }
                "MAIN_HAND_WEAPON_ENTITY", "334" -> target.weaponEntity = value.toIntOrNull()?.takeIf { it in 1..MAX_ENTITIES }
                "DURABILITY", "187" -> target.durability = value.toIntOrNull()?.takeIf { it >= 0 }
                "TAUNT", "190" -> target.taunt = flag(value) == true
                "EXHAUSTED", "43" -> target.exhausted = flag(value)
                "FROZEN", "260" -> target.frozen = flag(value) == true
                "CANT_ATTACK", "227" -> target.cantAttack = flag(value) == true
                "DORMANT", "1518", "DORMANT_VISUAL", "1090", "UNTOUCHABLE", "448" -> {
                    val key = when (tag) {
                        "DORMANT", "1518" -> "DORMANT"
                        "DORMANT_VISUAL", "1090" -> "DORMANT_VISUAL"
                        else -> "UNTOUCHABLE"
                    }
                    if ((value.toIntOrNull() ?: 0) > 0) target.attackBlocks += key else target.attackBlocks -= key
                }
                "WINDFURY", "189" -> target.windfury = flag(value) == true
                "MEGA_WINDFURY", "1207" -> target.megaWindfury = flag(value) == true
                "CHARGE", "197" -> target.charge = flag(value) == true
                "CANT_BE_SUMMONING_SICK", "253" -> target.cantBeSummoningSick = flag(value) == true
                "RUSH", "791" -> target.rush = flag(value) == true
                "CANNOT_ATTACK_HEROES", "413" -> target.cantAttackHero = flag(value) == true
                "NUM_ATTACKS_THIS_TURN", "297" -> target.attacksUsed = value.toIntOrNull()?.takeIf { it >= 0 }
                "EXTRA_ATTACKS_THIS_TURN", "444" -> target.extraAttacks = value.toIntOrNull()?.coerceIn(0, 20) ?: 0
                "NUM_TURNS_IN_PLAY", "271" -> target.turnsInPlay = value.toIntOrNull()?.takeIf { it >= 0 }
                "PLAYSTATE", "17" -> target.result = when (value) {
                    "WON", "4" -> MatchResult.WIN
                    "LOST", "5" -> MatchResult.LOSS
                    "TIED", "6" -> MatchResult.DRAW
                    else -> target.result
                }
                "STATE" -> if (target.id == gameEntity && (value == "COMPLETE" || value == "3")) {
                    gameComplete = true
                }
                "QUEST", "SIDEQUEST", "QUESTLINE" -> if (value == "1") target.quest = true
            }
            observeEntity(target)
        }

        fun observedBattlefieldTags(): Map<Int, Map<String, Int>> = buildMap {
            if (!captureCombatTags) return@buildMap
            for (entity in entities.values) {
                val tags = entity.observedTags ?: continue
                if (entity.zone == "PLAY" && entity.identityPublic && entity.kind in PUBLIC_BOARD_KINDS) {
                    put(entity.id, tags.toMap())
                }
            }
        }

        private fun observeEntity(entity: Entity) {
            val player = entity.controller ?: return
            if (entity.zone == "PLAY" && entity.kind == "HERO" && collectingOpeningEvidence()) {
                val heroClass = HsClass.fromHeroCardId(entity.cardId)
                if (heroClass.isPlayable) openingHeroClasses.putIfAbsent(player, heroClass)
            }
            if (entity.zone == "HAND") identifyOpeningCoin(entity, player)
            if (entity.zone == "DECK") {
                deckObserved += player
                if (entity.initiallyInDeck && entity.hasCreator && entity.initialDeckMove == null && deckMoves.size < MAX_ENTITIES) {
                    val move = DeckMove(player, entity.cardId, PowerLogDeckChangeKind.RETURNED, turn)
                    deckMoves += move
                    entity.initialDeckMove = move
                }
            }
            if (entity.originalDeckOwner == null && entity.initiallyInDeck && entity.zone == "DECK" &&
                !entity.hasCreator && collectingOpeningEvidence()
            ) {
                entity.originalDeckOwner = player
                originalDeckEntities += entity
            }
            if (entity.originalDeckOwner == player && !entity.originalDeckIdentityUncertain &&
                !entity.hasCreator && entity.originalDeckCardId == null
            ) {
                entity.originalDeckCardId = entity.cardId
            }
            entity.initialDeckMove?.let { move ->
                if (move.cardId == null) move.cardId = entity.cardId
            }
            if (entity.zone == "PLAY" && entity.kind in PUBLIC_BOARD_KINDS && blockPlays.none { it?.entity?.id == entity.id }) {
                entity.battlefieldCardId = entity.cardId
            }
            entity.pendingDeckMove?.let { move ->
                if (move.controller == null) move.controller = player
                if (move.cardId == null) move.cardId = entity.cardId
                if (move.cardId != null) entity.pendingDeckMove = null
            }
        }

        private fun moveEntity(target: Entity, value: String, cardAtZoneChange: String?, wasPublic: Boolean) {
            val zone = when (value) {
                "1" -> "PLAY"
                "2" -> "DECK"
                "3" -> "HAND"
                "4" -> "GRAVEYARD"
                "6" -> "SETASIDE"
                "7" -> "SECRET"
                else -> value
            }
            val previous = target.zone
            if (previous == zone) return
            target.publicSecretCardId = cardAtZoneChange?.takeIf {
                zone == "SECRET" && (it == target.publicHandCardId || previous == "GRAVEYARD" && wasPublic)
            }
            val returningSetaside = target.setasideDeckMove?.takeIf { zone == "DECK" }
            if (returningSetaside != null && returningSetaside.cardId == null) {
                returningSetaside.cardId = cardAtZoneChange
                if (returningSetaside.cardId == null) deckMoves.remove(returningSetaside)
            }
            val deckCard = cardAtZoneChange ?: if (previous == "DECK") target.initialDeckMove?.cardId else returningSetaside?.cardId
            val controller = target.controller
            if (controller != null && previous != null && previous != "INVALID" && previous != "0") {
                when {
                    previous == "DECK" -> recordDeckEvent(target, controller, PowerLogDeckChangeKind.TAKEN, deckCard, wasPublic)
                    zone == "DECK" -> recordDeckEvent(target, controller, PowerLogDeckChangeKind.RETURNED, cardAtZoneChange, wasPublic)
                }
            }
            target.pendingDeckMove = null
            target.setasideDeckMove = null
            val change = when {
                previous == "DECK" && zone in DECK_DESTINATIONS -> PowerLogDeckChangeKind.TAKEN
                previous in DECK_RETURN_ZONES && zone == "DECK" -> PowerLogDeckChangeKind.RETURNED
                returningSetaside != null && returningSetaside.cardId == null -> null
                previous == "SETASIDE" && zone == "DECK" && (returningSetaside != null || target.hasCreator) ->
                    PowerLogDeckChangeKind.RETURNED
                else -> null
            }
            if (change != null && deckMoves.size < MAX_ENTITIES) {
                val move = DeckMove(target.controller, deckCard, change, turn)
                deckMoves += move
                if (move.controller == null || move.cardId == null) target.pendingDeckMove = move
                if (zone == "SETASIDE") target.setasideDeckMove = move
            }
            if (zone == "DECK") {
                target.initiallyInDeck = previous == null || previous == "INVALID" || previous == "0"
                target.initialDeckMove = null
            }
            target.zone = zone
            target.position = null
            target.openingDraw = previous == "DECK" && zone == "HAND" && collectingOpeningEvidence()
            target.openingRevealCardId = openingShowCardId?.takeIf { target.openingDraw && target == currentEntity }
            if (zone == "PLAY") {
                target.enteredPlayTurn = turn.takeIf { turnObserved }
                target.turnsInPlay = null
                target.attacksUsed = 0
            } else if (zone == "HAND") {
                val player = target.controller?.let(playerEntities::get)?.let(entities::get)
                target.publicHandCardId = target.cardId.takeIf { previous in PUBLIC_ZONES && it == target.battlefieldCardId }
                target.battlefieldCardId = null
                val opening = turn == 0 || gameStep in MULLIGAN_STEPS ||
                    (turn == 1 && gameStep == null && player?.mulliganDone != true && player?.resources == null)
                target.handOrigin = when {
                    previous == "DECK" -> if (opening) PowerLogHandOrigin.OPENING else PowerLogHandOrigin.DRAWN
                    previous in PUBLIC_ZONES -> PowerLogHandOrigin.RETURNED
                    target.hasCreator || (previous == null && target.wasCreated && !collectingOpeningEvidence()) -> PowerLogHandOrigin.GENERATED
                    previous == null -> PowerLogHandOrigin.UNKNOWN
                    else -> PowerLogHandOrigin.OTHER
                }
                target.enteredHandTurn = turn.takeIf { turnObserved || target.handOrigin == PowerLogHandOrigin.OPENING }
            } else {
                target.publicHandCardId = null
                if (zone != "GRAVEYARD") target.battlefieldCardId = null
            }
            target.identityPublic = when (zone) {
                "PLAY" -> true
                "GRAVEYARD" -> target.freshReveal || previous == "PLAY"
                else -> false
            }
            target.freshReveal = false
        }

        private fun identifyOpeningCoin(target: Entity, player: Int) {
            if (openingCoinId != null || !collectingOpeningEvidence() || firstPlayer != 3 - player || target.position != 5 ||
                target.handOrigin !in setOf(PowerLogHandOrigin.UNKNOWN, PowerLogHandOrigin.GENERATED) ||
                !target.wasCreated || (target.creatorEntityId != null && target.creatorEntityId != gameEntity)) return
            val visibleCoin = target.hasCoinIdentity() && target.creatorEntityId == gameEntity
            val hiddenCoin = target.cardId == null && target.createdInOpeningDeal && openingDealDepth == blockPlays.size
            if (!visibleCoin && !hiddenCoin) return
            // A normal second player receives four cards from the deck before the opening coin.
            if (entities.values.count { it.controller == player && it.zone == "HAND" && it.handOrigin == PowerLogHandOrigin.OPENING } != 4) return
            openingCoinId = target.id
            target.handOrigin = PowerLogHandOrigin.COIN
            target.enteredHandTurn = turn
        }

        private fun creatorCardId(target: Entity): String? =
            sequenceOf(target.displayedCreatorEntityId, target.creatorEntityId).filterNotNull().firstNotNullOfOrNull { creator ->
                val source = entities[creator] ?: return@firstNotNullOfOrNull null
                source.publicHandCardId ?: source.cardId?.takeIf {
                    source.identityPublic && source.zone in PUBLIC_ZONES &&
                        (source.kind in PUBLIC_BOARD_KINDS || source.kind == "HERO_POWER" || source.zone == "GRAVEYARD")
                } ?: plays.lastOrNull { it.entityId == creator && it.cardId == source.cardId }?.cardId
            }

        private fun recordDeckEvent(
            target: Entity,
            player: Int,
            kind: PowerLogDeckChangeKind,
            cardAtMove: String?,
            isPublic: Boolean = false,
        ) {
            if (observedDeckMoves.size >= MAX_ENTITIES) return
            val event = PowerLogDeckEvent(target.id, player, kind, turn.takeIf { turnObserved }, cardAtMove)
            observedDeckMoves.add(ObservedDeckMove(event, isPublic))
        }

        fun onChoice(message: String) {
            if (message.startsWith("id=")) {
                incomingGeneralChoice = null
                currentChoiceId = null
                GENERAL_CHOICE.matchEntire(message)?.let {
                    startGeneralChoice(it)
                    return
                }
                val match = CHOICE.matchEntire(message) ?: return
                val id = match.groupValues[1].toIntOrNull() ?: return
                currentChoiceId = id
                val reference = match.groupValues[2]
                val player = playerFor(reference)
                if (player != null) mulliganChoices[id] = player
                val count = CHOICE_COUNT.find(message)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 3..5 }
                val idInHeader = entityId(reference)
                if (idInHeader != null && idInHeader !in playerEntities.values) return
                if (collectingOpeningEvidence() && count != null && openingChoices.size < MAX_OPENING_CHOICES) {
                    openingChoices[id] = OpeningChoices(id, player, count)
                }
            } else if (incomingGeneralChoice != null) {
                val choice = incomingGeneralChoice ?: return
                choice.accept(message)
                if (choice.isLocal) {
                    if (generalChoice !== choice) activateGeneralChoice(choice)
                } else if (generalChoice === choice) {
                    generalChoice = null
                }
            } else if (message.startsWith("Source=")) {
                val source = message.substringAfter('=')
                val choice = openingChoices[currentChoiceId] ?: return
                choice.fromGameEntity = source == "GameEntity" || gameEntity?.let { entityId(source) == it } == true
            } else if (message.startsWith("Entities[")) {
                val choice = currentChoiceId ?: return
                val match = CHOICE_ENTITY.matchEntire(message) ?: return
                val reference = match.groupValues[2]
                if (choice !in mulliganChoices) {
                    playerFor(reference)?.let { mulliganChoices[choice] = it }
                }
                val opening = openingChoices[choice] ?: return
                val index = match.groupValues[1].toIntOrNull()?.takeIf { it in 0 until opening.count } ?: return
                val entity = entityId(reference)?.takeIf { it in 1..MAX_ENTITIES } ?: return
                val controller = CONTROLLER.findAll(reference).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                opening.options[index] = OpeningOption(entity, controller)
            }
        }

        fun onSentChoice(message: String) {
            if (message.startsWith("id=")) {
                val sentGeneralId = SENT_GENERAL.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
                if (sentGeneralId != null && sentGeneralId == generalChoice?.id) clearGeneralChoice()
                sentChoiceId = SENT_MULLIGAN.matchEntire(message)?.groupValues?.get(1)?.toIntOrNull()
                if (sentChoiceId != null) closeOpening()
                if (inferredPlayer == null) {
                    sentChoiceId?.let { choice -> mulliganChoices[choice]?.let { inferredPlayer = it } }
                }
            } else if (message.startsWith("m_chosenEntities[") && sentChoiceId != null && inferredPlayer == null) {
                inferredPlayer = playerFor(message.substringAfter("]="))
            }
        }

        fun onEntitiesChosen(message: String) {
            val choice = generalChoice ?: return
            val (id, player) = ENTITIES_CHOSEN.matchEntire(message)?.destructured ?: return
            val owner = playerFor(player)
            if (id.toIntOrNull() == choice.id &&
                (owner == choice.player || owner == null && player == choice.playerReference)
            ) clearGeneralChoice()
        }

        private fun startGeneralChoice(header: kotlin.text.MatchResult) {
            val (id, player, minimum, maximum) = header.destructured
            val choiceId = id.toIntOrNull() ?: return
            val local = localPlayer() ?: return
            val owner = playerFor(player)
            if (owner != null && owner != local) return
            val max = maximum.toIntOrNull()?.takeIf { it in 1..MAX_CHOICE_OPTIONS } ?: return
            val min = minimum.toIntOrNull()?.takeIf { it in 0..max } ?: return
            val choice = GeneralChoice(choiceId, local, player, owner == local, ++choiceRevision, min, max)
            incomingGeneralChoice = choice
            if (choice.isLocal) activateGeneralChoice(choice)
        }

        private fun activateGeneralChoice(choice: GeneralChoice) {
            invalidateOptions()
            generalChoice = choice
        }

        private fun clearGeneralChoice() {
            generalChoice = null
            incomingGeneralChoice = null
        }

        private fun collectingOpeningEvidence(): Boolean =
            !openingClosed && turn <= 1 && (gameStep == null || gameStep in PRE_OPENING_STEPS)

        private fun closeOpening() {
            openingClosed = true
            openingChoices.clear()
        }

        private fun openingChoice(
            firstSide: Int,
            friendlyClass: HsClass?,
            opponentClass: HsClass?,
            confirmedLocal: Int?,
        ): PowerLogOpeningChoice? {
            if (openingClosed || gameStep !in MULLIGAN_STEPS) return null
            val choices = openingChoices.values.mapNotNull(::resolveOpeningChoices)
            if (choices.size != 2 || choices.map { it.player }.toSet() != playerEntities.keys) return null
            val shown = choices.singleOrNull { it.cards.isNotEmpty() } ?: return null
            val other = choices.single { it.player != shown.player }
            if (other.hasReveal || shown.cards.size != shown.expectedCards ||
                (confirmedLocal != null && confirmedLocal != shown.player)
            ) return null
            val participant = playerEntities[shown.player]?.let(entities::get)
            if (participant?.mulliganDone == true || participant?.result != null) return null
            return PowerLogOpeningChoice(
                choiceId = shown.choiceId,
                cards = shown.cards,
                friendlyHeroClass = if (firstSide == shown.player) friendlyClass else opponentClass,
                opponentHeroClass = if (firstSide == shown.player) opponentClass else friendlyClass,
                playerId = shown.player,
                sourceEntityId = gameEntity,
            )
        }

        private fun resolveOpeningChoices(choice: OpeningChoices): ResolvedOpeningChoices? {
            if (!choice.fromGameEntity || choice.options.size != choice.count ||
                choice.options.values.map { it.entityId }.toSet().size != choice.count
            ) return null
            val rows = choice.options.toSortedMap().map { (index, option) ->
                val target = entities[option.entityId]?.takeIf { it.zone == "HAND" } ?: return null
                val owner = target.controller?.takeIf { it in 1..2 } ?: return null
                if (option.controller != null && option.controller != owner) return null
                if (choice.declaredPlayer != null && choice.declaredPlayer != owner) return null
                index to target
            }
            val player = rows.map { it.second.controller }.distinct().singleOrNull() ?: return null
            val openingDraws = rows.filter { it.second.openingDraw }
            val expectedCards = choice.count.coerceAtMost(4)
            if (openingDraws.size != expectedCards) return null
            val cards = openingDraws.mapNotNull { (index, entity) ->
                val cardId = entity.openingRevealCardId?.takeIf { it == entity.cardId } ?: return@mapNotNull null
                PowerLogOpeningCard(cardId, entity.position?.takeIf { it > 0 } ?: index + 1, entity.cost, entity.id)
            }
            return ResolvedOpeningChoices(choice.id, player, expectedCards, cards, openingDraws.any { it.second.openingRevealCardId != null })
        }

        private fun playerFor(reference: String): Int? {
            val id = entityId(reference)
            return if (id != null) {
                playerEntities.entries.firstOrNull { it.value == id }?.key ?: entities[id]?.controller
                    ?: CONTROLLER.find(reference)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2 }
            } else alias(reference)?.let(aliasedPlayers::get)
        }

        private fun localPlayer(): Int? = (inferredPlayer ?: explicitPlayer)?.takeIf(playerEntities::containsKey)

        fun toSnapshot(): PowerLogSnapshot? {
            val matchFormat = format ?: return null
            if (!isConstructed || gameEntity == null || playerEntities.size != 2) return null
            val local = localPlayer()
            val firstSide = local ?: 1
            val result = local?.let(playerEntities::get)?.let(entities::get)?.result
            val friendly = side(firstSide, revealOwn = local != null)
            val opponent = side(3 - firstSide, revealOwn = false)
            val opening = openingChoice(firstSide, friendly.heroClass, opponent.heroClass, local)
            return PowerLogSnapshot(
                gameNumber = number,
                format = matchFormat,
                turn = turn,
                friendly = friendly,
                opponent = opponent,
                result = result,
                localPlayerIdentified = local != null,
                openingChoice = opening,
                actionOptions = if (local != null && result == null && friendly.isCurrentTurn == true) actionOptions?.snapshot() else null,
                generalChoice = generalChoice?.takeIf { it.player == local && result == null }?.snapshot(),
                publicPlays = plays,
                deckEvents = observedDeckMoves.map { (event, isPublic) ->
                    if (event.playerId == local || isPublic || event.cardId == null) event else event.copy(cardId = null)
                },
                startingDeck = (local ?: opening?.playerId)?.let { player ->
                    originalDeckEntities.filter { it.originalDeckOwner == player && !it.hasCreator }.let { cards ->
                        PowerLogStartingDeck(cards.size, cards.mapNotNull { it.originalDeckCardId })
                    }
                },
                gameComplete = gameComplete || result != null,
            )
        }

        private fun side(player: Int, revealOwn: Boolean): PowerLogSide {
            val owned = entities.values.filter { it.controller == player && it.kind != "PLAYER" }
            val hand = owned.filter { it.zone == "HAND" }.sortedBy { it.position ?: 0 }
            val secrets = owned.filter { it.zone == "SECRET" && !it.quest }
            val board = owned.filter { it.zone == "PLAY" && it.kind == "MINION" }.sortedBy { it.position ?: 0 }
            val participant = playerEntities[player]?.let(entities::get)
            val linkedHero = participant?.heroEntity
            val hero = if (linkedHero != null) {
                entities[linkedHero]?.takeIf { it.controller == player && it.zone == "PLAY" && it.kind == "HERO" }
            } else {
                owned.singleOrNull { it.zone == "PLAY" && it.kind == "HERO" }
            }
            val linkedWeapon = hero?.weaponEntity ?: participant?.weaponEntity
            val weapon = if (linkedWeapon != null) {
                entities[linkedWeapon]?.takeIf { it.controller == player && it.zone == "PLAY" && it.kind == "WEAPON" }
            } else {
                owned.singleOrNull { it.zone == "PLAY" && it.kind == "WEAPON" }
            }
            val active = activePlayer()?.let { it == player }
            val heroClass = HsClass.fromHeroCardId(hero?.cardId).takeIf { it.isPlayable }
            val startingClass = openingHeroClasses[player]?.takeIf { it == heroClass }
            val boardAttackBudgets = if (active == true) {
                board.associateWith { it.attackBudget(turn, acceptedOption = it.id in acceptedOptions) }
            } else emptyMap()
            val heroBudget = hero?.takeIf { active == true }?.let { it.attackBudget(turn, weapon, it.id in acceptedOptions) }
            val confirmedAttack = sumAttack(boardAttackBudgets.values.map { it.confirmed } + (heroBudget?.confirmed ?: 0))
            val possibleAttack = sumAttack(boardAttackBudgets.values.map { it.possible } + listOf(heroBudget?.possible ?: if (hero == null) 0 else null))
            return PowerLogSide(
                playerId = player,
                playerEntityId = participant?.id,
                hand = if (revealOwn) hand.mapNotNull { card -> card.toCard(playOptionReported = true.takeIf { card.id in acceptedOptions }) } else emptyList(),
                handCount = hand.size,
                graveyard = owned.filter { it.zone == "GRAVEYARD" && it.identityPublic }.mapNotNull(Entity::toCard),
                secrets = if (revealOwn) secrets.mapNotNull(Entity::toCard) else emptyList(),
                secretCount = secrets.size,
                board = board.mapNotNull { it.toCard(boardAttackBudgets[it], includeBoardStats = true) },
                boardAttack = sumAttack(board.map { it.attack ?: 0 }) ?: 0,
                heroClass = heroClass,
                deckCount = if (player in deckObserved) owned.count { it.zone == "DECK" } else null,
                played = plays.filter { it.playerId == player }.map { PowerLogCard(it.cardId) },
                heroEffectiveHealth = hero?.effectiveHealth(),
                deckChanges = if (revealOwn) deckMoves.mapNotNull { move ->
                    move.cardId?.takeIf { move.controller == player }?.let { PowerLogDeckChange(it, move.kind, move.turn) }
                } else emptyList(),
                handMarks = hand.map {
                    val cardId = if (it.id == openingCoinId || it.hasCoinIdentity()) STANDARD_COIN_CARD else it.publicHandCardId
                    val creator = if (it.handOrigin == PowerLogHandOrigin.GENERATED) creatorCardId(it) else null
                    PowerLogHandMark(it.id, it.position?.takeIf { slot -> slot > 0 }, it.enteredHandTurn, it.handOrigin, cardId, creator)
                },
                availableAttack = confirmedAttack.takeIf { active == true },
                availableAttackUpperBound = possibleAttack.takeIf { active == true },
                heroAttack = hero?.attack,
                weaponAttack = weapon?.attack,
                // Some weapon entities report maximum durability as HEALTH.
                weaponDurability = weapon?.let { (it.durability ?: it.health)?.minus(it.damage)?.coerceAtLeast(0) },
                isCurrentTurn = active,
                availableMana = participant?.availableMana()?.takeIf { active == true },
                boardOccupiedSlots = owned.count { it.zone == "PLAY" && it.kind in BOARD_SLOT_KINDS },
                heroEntityId = hero?.id,
                heroCardId = hero?.cardId,
                heroCombatState = hero?.combatState(),
                heroPower = owned.singleOrNull { it.zone == "PLAY" && it.kind == "HERO_POWER" }
                    ?.let { PowerLogHeroPower(it.id, it.cardId) },
                weaponEntityId = weapon?.id,
                weaponCardId = weapon?.cardId,
                weaponCombatState = weapon?.combatState(),
                secretObservations = if (!revealOwn && localPlayer() != null) secrets.map { secret ->
                    PowerLogSecretObservation(
                        entityId = secret.id,
                        revealedCardId = secret.publicSecretCardId,
                        deckClass = startingClass?.takeIf {
                            secret.originalDeckOwner == player && !secret.hasCreator && !secret.originalDeckIdentityUncertain
                        },
                        creatorCardId = creatorCardId(secret),
                    )
                } else emptyList(),
            )
        }

        private fun activePlayer(): Int? {
            if (!turnObserved || turn == 0) return null
            if (gameStep != null && gameStep !in ACTION_STEPS) return null
            val players = playerEntities.mapNotNull { (player, id) -> entities[id]?.let { player to it } }
            if (players.any { it.second.mulliganDone == false }) return null
            val current = players.singleOrNull { it.second.currentPlayer == true } ?: return null
            return current.first.takeIf { gameStep != null || current.second.mulliganDone == true || current.second.resources != null }
        }
    }

    private class ActionOptions(private val packetId: Int, private val revision: Long) {
        private val options = ArrayList<PowerLogActionOption>()
        private var rowCount = 0

        fun addOption(option: PowerLogActionOption) {
            if (rowCount >= MAX_OPTION_ROWS) return
            options.add(option)
            rowCount++
        }

        fun addSubOption(subOption: PowerLogActionSubOption) {
            if (rowCount >= MAX_OPTION_ROWS) return
            val option = options.lastOrNull() ?: return
            options[options.lastIndex] = option.copy(subOptions = option.subOptions + subOption)
            rowCount++
        }

        fun addTarget(target: PowerLogActionTarget) {
            if (rowCount >= MAX_OPTION_ROWS) return
            val option = options.lastOrNull() ?: return
            val subOption = option.subOptions.lastOrNull()
            options[options.lastIndex] = if (subOption == null) {
                option.copy(targets = option.targets + target)
            } else {
                val updated = subOption.copy(targets = subOption.targets + target)
                option.copy(subOptions = option.subOptions.dropLast(1) + updated)
            }
            rowCount++
        }

        fun snapshot() = PowerLogActionOptions(packetId, revision, options.toList())
    }

    private class OpenPlay(val entity: Entity, val turn: Int?, val sourceZone: String?)

    private data class ObservedDeckMove(val event: PowerLogDeckEvent, val isPublic: Boolean)

    private class DeckMove(var controller: Int?, var cardId: String?, val kind: PowerLogDeckChangeKind, val turn: Int)

    private class GeneralChoice(
        val id: Int,
        val player: Int,
        val playerReference: String,
        private val headerIdentifiesPlayer: Boolean,
        private val revision: Long,
        private val minSelections: Int,
        private val maxSelections: Int,
    ) {
        private var sourceEntityId: Int? = null
        private val options = sortedMapOf<Int, PowerLogChoiceOption>()
        private var hasUnconfirmedOption = false

        val isLocal: Boolean
            get() = headerIdentifiesPlayer || options.isNotEmpty() && !hasUnconfirmedOption

        fun accept(message: String) {
            if (message.startsWith("Source=")) {
                sourceEntityId = actionEntityId(message.substringAfter('='))
                return
            }
            val (position, reference) = CHOICE_ENTITY.matchEntire(message)?.destructured ?: return
            val index = position.toIntOrNull()?.takeIf { it in 0 until MAX_CHOICE_OPTIONS } ?: return
            val id = actionEntityId(reference) ?: return
            val cardId = DESCRIPTOR_CARD.findAll(reference).lastOrNull()?.groupValues?.get(1)?.takeIf(CARD_ID::matches)
            val owner = CONTROLLER.findAll(reference).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            if (owner != player || cardId == null) hasUnconfirmedOption = true
            options[index] = PowerLogChoiceOption(index, id, cardId)
        }

        fun snapshot() = PowerLogGeneralChoice(id, revision, sourceEntityId, minSelections, maxSelections, options.values.toList())
    }

    private class OpeningChoices(val id: Int, val declaredPlayer: Int?, val count: Int) {
        var fromGameEntity = false
        val options = mutableMapOf<Int, OpeningOption>()
    }

    private data class OpeningOption(val entityId: Int, val controller: Int?)

    private data class ResolvedOpeningChoices(
        val choiceId: Int,
        val player: Int,
        val expectedCards: Int,
        val cards: List<PowerLogOpeningCard>,
        val hasReveal: Boolean,
    )

    private data class AttackBudget(val confirmed: Int, val possible: Int?, val remaining: Int?, val canFace: Boolean?, val canTrade: Boolean?)

    private class Entity(val id: Int) {
        var observedTags: MutableMap<String, Int>? = null
        var cardId: String? = null
        var controller: Int? = null
        var zone: String? = null
        var kind: String? = null
        var position: Int? = null
        var attack: Int? = null
        var cost: Int? = null
        var health: Int? = null
        var damage = 0
        var armor = 0
        var heroEntity: Int? = null
        var weaponEntity: Int? = null
        var durability: Int? = null
        var currentPlayer: Boolean? = null
        var mulliganDone: Boolean? = null
        var resources: Int? = null
        var resourcesUsed = 0
        var tempResources = 0
        var exhausted: Boolean? = null
        var frozen = false
        var cantAttack = false
        val attackBlocks = mutableSetOf<String>()
        var windfury = false
        var megaWindfury = false
        var charge = false
        var cantBeSummoningSick = false
        var rush = false
        var cantAttackHero = false
        var attacksUsed: Int? = null
        var extraAttacks = 0
        var turnsInPlay: Int? = null
        var enteredPlayTurn: Int? = null
        var taunt = false
        var result: MatchResult? = null
        var quest = false
        var identityPublic = false
        var publicSecretCardId: String? = null
        var freshReveal = false
        var battlefieldCardId: String? = null
        var publicHandCardId: String? = null
        var enteredHandTurn: Int? = null
        var handOrigin = PowerLogHandOrigin.UNKNOWN
        var hasCreator = false
        var creatorEntityId: Int? = null
        var displayedCreatorEntityId: Int? = null
        var wasCreated = false
        var createdInOpeningDeal = false
        var hasCoinTag = false
        var initiallyInDeck = false
        var originalDeckOwner: Int? = null
        var originalDeckCardId: String? = null
        var originalDeckIdentityUncertain = false
        var initialDeckMove: DeckMove? = null
        var setasideDeckMove: DeckMove? = null
        var pendingDeckMove: DeckMove? = null
        var openingDraw = false
        var openingRevealCardId: String? = null

        fun hasCoinIdentity(): Boolean = hasCoinTag || cardId in KNOWN_COIN_IDS

        fun resetForRewind() {
            observedTags = null
            cardId = null
            zone = null
            if (kind != "PLAYER") kind = null
            position = null
            attack = null
            cost = null
            health = null
            damage = 0
            armor = 0
            heroEntity = null
            weaponEntity = null
            durability = null
            currentPlayer = null
            mulliganDone = null
            resources = null
            resourcesUsed = 0
            tempResources = 0
            exhausted = null
            frozen = false
            cantAttack = false
            attackBlocks.clear()
            windfury = false
            megaWindfury = false
            charge = false
            cantBeSummoningSick = false
            rush = false
            cantAttackHero = false
            attacksUsed = null
            extraAttacks = 0
            turnsInPlay = null
            enteredPlayTurn = null
            taunt = false
            result = null
            quest = false
            identityPublic = false
            publicSecretCardId = null
            freshReveal = false
            battlefieldCardId = null
            publicHandCardId = null
            hasCoinTag = false
        }

        fun toCard(budget: AttackBudget? = null, includeBoardStats: Boolean = false, playOptionReported: Boolean? = null): PowerLogCard? =
            cardId?.let {
                PowerLogCard(
                    cardId = it,
                    attack = attack,
                    cost = cost.takeIf { zone == "HAND" },
                    health = health?.let { maximum -> (maximum - damage).coerceAtLeast(0) }.takeIf { includeBoardStats },
                    taunt = includeBoardStats && taunt,
                    canAttackHero = budget?.canFace,
                    canAttackMinions = budget?.canTrade,
                    remainingAttacks = budget?.remaining,
                    playOptionReported = playOptionReported,
                    entityId = id,
                    combatState = if (includeBoardStats) combatState() else null,
                )
            }

        fun combatState() = PowerLogCombatState(position, health, damage, armor, exhausted, turnsInPlay)

        fun availableMana(): Int? = resources?.let {
            // Power.log counts locked Overload crystals in RESOURCES_USED at the start of the turn.
            (it.toLong() - resourcesUsed + tempResources).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }

        fun attackBudget(turn: Int, weapon: Entity? = null, acceptedOption: Boolean = false): AttackBudget {
            val hero = kind == "HERO"
            val atk = attack ?: weapon?.attack ?: 0.takeIf { hero && weapon == null }
            val fresh = !hero && (enteredPlayTurn == turn || turnsInPlay == 0)
            val ignoresSummoningSickness = charge || cantBeSummoningSick
            val rushOnly = rush && !ignoresSummoningSickness && fresh
            val maxAttacks = when {
                megaWindfury || weapon?.megaWindfury == true -> 4
                windfury || weapon?.windfury == true -> 2
                else -> 1
            } + extraAttacks
            val remaining = attacksUsed?.let { (maxAttacks - it).coerceAtLeast(0) }
            if (atk == 0 || remaining == 0 || frozen || cantAttack || attackBlocks.isNotEmpty() ||
                (fresh && !ignoresSummoningSickness && !rush && !acceptedOption) || (exhausted == true && !acceptedOption && !rushOnly)
            ) return AttackBudget(0, 0, 0, false, false)
            val possible = atk?.let { damageForAttacks(it, remaining ?: maxAttacks) }
            val confirmed = acceptedOption || exhausted == false
            val confirmedAttacks = if (confirmed) remaining ?: 1 else 0
            val canAttack = true.takeIf { confirmed && atk != null }
            return AttackBudget(
                damageForAttacks(attack ?: 0, confirmedAttacks),
                possible,
                remaining.takeIf { confirmed },
                if (rushOnly || cantAttackHero) false else canAttack,
                canAttack,
            )
        }

        fun effectiveHealth(): Int? = health?.let {
            ((it.toLong() - damage).coerceAtLeast(0L) + armor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private companion object {
        const val MAX_LINE_LENGTH = 16_384
        const val MAX_ENTITIES = 10_000
        const val MAX_OPENING_CHOICES = 4
        const val MAX_CHOICE_OPTIONS = 128
        const val MAX_OPTION_ROWS = 1_024
        const val STANDARD_COIN_CARD = "GAME_005"
        val KNOWN_COIN_IDS = setOf(STANDARD_COIN_CARD, "BE_COIN3", "TSC_COIN2", "TTN_COIN2")
        val START_OR_RESET = setOf("CREATE_GAME", "RESET_GAME")
        val CONSTRUCTED_GAME_TYPES = setOf("GT_VS_AI", "GT_VS_FRIEND", "GT_RANKED", "GT_CASUAL", "1", "2", "7", "8")
        val PUBLIC_ZONES = setOf("PLAY", "GRAVEYARD")
        val PUBLIC_BOARD_KINDS = setOf("HERO", "MINION", "WEAPON", "LOCATION")
        val BOARD_SLOT_KINDS = setOf("MINION", "LOCATION")
        val AUDIT_COMBAT_TAGS = buildMap {
            for ((number, name) in listOf(
                "43" to "EXHAUSTED", "44" to "DAMAGE", "45" to "HEALTH", "47" to "ATK",
                "50" to "CONTROLLER", "263" to "ZONE_POSITION", "292" to "ARMOR",
            )) {
                put(number, name)
                put(name, name)
            }
        }
        val CURRENT_PLAYER_TAGS = setOf("CURRENT_PLAYER", "23")
        val RESOURCES_TAGS = setOf("RESOURCES", "26")
        val ACTION_STEPS = setOf("MAIN_ACTION", "10", "MAIN_COMBAT", "11")
        val START_OF_TURN_STEPS = setOf("MAIN_READY", "6", "MAIN_RESOURCE", "7")
        val MULLIGAN_STEPS = setOf("BEGIN_MULLIGAN", "4")
        val PRE_OPENING_STEPS = MULLIGAN_STEPS + setOf("INVALID", "0", "BEGIN_FIRST", "1", "BEGIN_SHUFFLE", "2", "BEGIN_DRAW", "3")
        val DECK_DESTINATIONS = setOf("HAND", "GRAVEYARD", "PLAY", "SECRET", "SETASIDE")
        val DECK_RETURN_ZONES = setOf("HAND", "PLAY", "GRAVEYARD")
        val LOG_LINE = Regex("""(?:[DWE] [\d:.]+ +)?GameState\.(DebugPrintPower|DebugPrintGame|DebugPrintChoices|DebugPrintEntityChoices|DebugPrintEntitiesChosen|DebugPrintOptions|SendChoices|SendOption)\(\) - (.+)""")
        const val OPTION_ENTITY = """(\[.+\]|\d+)?"""
        const val OPTION_ERROR = """(?: error=(\w+)(?: errorParam=(-?\d+)?)?)?"""
        val OPTION_ROW = Regex("""option (\d+) type=(\w+) mainEntity=${OPTION_ENTITY}${OPTION_ERROR}""")
        val OPTION_CHILD_ROW = Regex("""(subOption|target) (\d+) entity=${OPTION_ENTITY}${OPTION_ERROR}""")
        val GAME_ENTITY = Regex("""GameEntity EntityID=(\d+)""")
        val PLAYER = Regex("""Player EntityID=(\d+) PlayerID=(\d+)(?: .*)?""")
        val PLAYER_META = Regex("""PlayerID=(\d+),\s*PlayerName=(.+)""")
        val TAG_VALUE = Regex("""tag=(\w+) value=(\w+)""")
        val TAG_CHANGE = Regex("""TAG_CHANGE Entity=(.+?) tag=(\w+) value=(\w+)(?: DEF CHANGE)?""")
        val HIDE_ENTITY = Regex("""HIDE_ENTITY - Entity=(.+?) tag=(\w+) value=(\w+)""")
        val CHOICE = Regex("""id=(\d+) Player=(.+?) TaskList=\d* ChoiceType=MULLIGAN(?: .*)?""")
        val GENERAL_CHOICE = Regex("""id=(\d+) Player=(.+?)(?: TaskList=\d*)? ChoiceType=GENERAL CountMin=(\d+) CountMax=(\d+)""")
        val CHOICE_COUNT = Regex("""(?:^|\s)CountMax=(\d+)(?=\s|$)""")
        val CHOICE_ENTITY = Regex("""Entities\[(\d+)]\s*=\s*(.+)""")
        val SENT_MULLIGAN = Regex("""id=(\d+) ChoiceType=MULLIGAN""")
        val SENT_GENERAL = Regex("""id=(\d+) ChoiceType=GENERAL""")
        val ENTITIES_CHOSEN = Regex("""id=(\d+) Player=(.+?) EntitiesCount=\d+""")
        val ENTITY_ID = Regex("""(?:^|\s)id=(\d+)(?=\s|\])""")
        val CONTROLLER = Regex("""(?:^|\s)player=(\d+)(?=\s|\])""", RegexOption.IGNORE_CASE)
        val DESCRIPTOR_CARD = Regex("""(?:^|\s)cardId=(\w+)(?=\s|\])""", RegexOption.IGNORE_CASE)
        val DESCRIPTOR_ZONE = Regex("""(?:^|\s)zone=(\w+)(?=\s|\])""")
        val POSITION = Regex("""(?:^|\s)zonePos=(\d+)(?=\s|\])""")
        val CARD_ID = Regex("""[A-Z0-9_]{2,64}""", RegexOption.IGNORE_CASE)

        fun flag(value: String): Boolean? = when (value) {
            "0" -> false
            "1" -> true
            else -> null
        }

        fun sumAttack(attacks: List<Int?>): Int? {
            if (attacks.any { it == null }) return null
            return attacks.sumOf { (it ?: 0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun damageForAttacks(attack: Int, count: Int): Int = (attack.toLong() * count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun entityId(reference: String): Int? {
            val trimmed = reference.trim()
            return trimmed.toIntOrNull() ?: trimmed.takeIf { it.startsWith("[") }?.let { value ->
                ENTITY_ID.findAll(value).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            }
        }

        fun actionEntityId(reference: String): Int? = entityId(reference)?.takeIf { it in 1..MAX_ENTITIES }

        fun alias(reference: String): String? {
            val name = reference.trim()
            if (name.isEmpty() || name.equals("UNKNOWN HUMAN PLAYER", ignoreCase = true) || name.startsWith("[")) return null
            // The opaque key stays in this match's reducer and never appears in the snapshot or record.
            return MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
