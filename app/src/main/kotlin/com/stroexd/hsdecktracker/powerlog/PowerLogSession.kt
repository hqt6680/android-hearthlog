package com.stroexd.hsdecktracker.powerlog

import com.stroexd.hsdecktracker.cloud.CloudPrediction
import com.stroexd.hsdecktracker.cloud.MAX_POWER_LOG_BYTES
import com.stroexd.hsdecktracker.cloud.PredictionInput
import com.stroexd.hsdecktracker.cloud.PredictionKey
import com.stroexd.hsdecktracker.cloud.PredictionStage
import com.stroexd.hsdecktracker.cloud.isPowerLogMatchStart
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.powerlog.ConstructedDeckLog
import com.stroexd.hsdecktracker.core.powerlog.DeckLogKind
import com.stroexd.hsdecktracker.core.powerlog.PowerLogDecisionGate
import com.stroexd.hsdecktracker.core.powerlog.PowerLogCard
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSide
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import com.stroexd.hsdecktracker.core.powerlog.PowerLogTracker
import com.stroexd.hsdecktracker.core.powerlog.gameLogTimeOfDayMillis
import com.stroexd.hsdecktracker.core.powerlog.identifyPowerLogDeck
import com.stroexd.hsdecktracker.core.prediction.PowerLogPredictionKind
import com.stroexd.hsdecktracker.core.prediction.activePredictionAnchor
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PowerLogSessionState(
    val active: Boolean = false,
    val snapshot: PowerLogSnapshot? = null,
    val selectedDeck: Deck? = null,
    val revision: Long = 0,
    val cloudDecision: PredictionKey? = null,
    val logTruncated: Boolean = false,
)

internal fun PowerLogSnapshot.localHeroClass(): HsClass? =
    openingChoice?.friendlyHeroClass?.takeIf { it.isPlayable }
        ?: friendly.heroClass?.takeIf { localPlayerIdentified && it.isPlayable }

internal fun Deck.matchesPowerLog(match: PowerLogSnapshot): Boolean =
    cards.isNotEmpty() &&
        (format == match.format || (match.format == GameFormat.WILD && format == GameFormat.STANDARD)) &&
        (match.localHeroClass()?.let { it == heroClass || !heroClass.isPlayable } != false)

internal class PowerLogSession(
    private val cards: () -> CardDatabase,
    private val cloud: CloudPrediction,
    private val savedDecks: () -> List<Deck>,
    private val synchronizeGameDecks: (List<Deck>) -> Unit,
) {
    private val sessionState = MutableStateFlow(PowerLogSessionState())
    val state = sessionState.asStateFlow()
    private var tracker = PowerLogTracker(captureCombatTags = true)
    private var trackedGame: Long? = null
    private val deckLog = ConstructedDeckLog()
    private var nextQueuedDeck: TimedDeck? = null
    private var activeQueuedDeck: Deck? = null
    private var selectedDeck: Deck? = null
    private var manualDeckChoice = false
    private var revision = 0L
    private val decisionGate = PowerLogDecisionGate(quietPolls = 1)
    private var matchId: String? = null
    private var matchLog: ByteArrayOutputStream? = null
    private var matchLogLineCount = 0
    private var stableDecision: PredictionCheckpoint? = null
    private var activeDecision: PredictionKey? = null
    private var observation: PhaseObservation? = null
    private var unchangedPolls = 0
    private var offeredDecision: PredictionKey? = null
    private var submittedDecision: PredictionKey? = null
    private var submittedDeck: Deck? = null
    private var logTruncated = false

    @Synchronized
    fun start() {
        clearMatch()
        clearDeckLog()
        sessionState.value = PowerLogSessionState(active = true, revision = revision)
    }

    @Synchronized
    fun stop() {
        clearMatch()
        clearDeckLog()
        sessionState.value = PowerLogSessionState()
    }

    @Synchronized
    fun accept(input: PowerLogInput) {
        if (!sessionState.value.active) return
        if (input.reset) clearMatch()
        if (input.deckLogReset) clearDeckLog()
        acceptDeckLog(input.deckLogLines, input.acceptQueuedDeck)
        if (input.idle) {
            observePrediction(tracker.snapshot, completeRead = true, idle = true)
            publish()
            return
        }
        for (line in input.lines) {
            if (isPowerLogMatchStart(line)) matchQueuedDeck(gameLogTimeOfDayMillis(line))
            if (line.contains(TRUNCATED_LOG_MARKER)) logTruncated = true
            appendForPrediction(line)
            val match = tracker.accept(line) ?: continue
            if (trackedGame != match.gameNumber) {
                trackedGame = match.gameNumber
                selectedDeck = null
                manualDeckChoice = false
                revision++
            }
        }
        observePrediction(tracker.snapshot, completeRead = input.completeRead, idle = false)
        publish()
    }

    private fun acceptDeckLog(lines: List<String>, acceptQueue: Boolean) {
        if (lines.isEmpty()) return
        val imported = linkedMapOf<String, Deck>()
        for (line in lines) {
            val entry = deckLog.accept(line) ?: continue
            val deck = Deck.fromDefinition(entry.definition, entry.name, cards(), System.currentTimeMillis())
                .copy(id = "hearthstone-${entry.deckId}")
                .takeIf { it.cardCount in COMPLETE_DECK_SIZES }
                ?: continue
            imported[deck.id] = deck
            if (entry.kind == DeckLogKind.QUEUED && acceptQueue) {
                nextQueuedDeck = TimedDeck(deck, entry.timeOfDayMillis)
            }
        }
        if (imported.isNotEmpty()) synchronizeGameDecks(imported.values.toList())
    }

    private fun clearDeckLog() {
        deckLog.reset()
        nextQueuedDeck = null
    }

    private fun matchQueuedDeck(gameStarted: Long?) {
        activeQueuedDeck = null
        val queued = nextQueuedDeck ?: return
        nextQueuedDeck = null
        if (gameStarted == null) return
        val age = Math.floorMod(gameStarted - queued.timeOfDay, MILLIS_PER_DAY)
        if (age <= MAX_QUEUE_WAIT_MILLIS) activeQueuedDeck = queued.deck
    }

    @Synchronized
    fun selectDeck(matchRevision: Long, deck: Deck?) {
        val current = sessionState.value
        val match = current.snapshot ?: return
        if (!current.active || revision != matchRevision || match.result != null ||
            (!match.localPlayerIdentified && match.openingChoice == null)) return
        if (deck != null && !deck.matchesPowerLog(match)) return
        selectedDeck = deck
        manualDeckChoice = true
        publish()
    }

    private fun publish() {
        val match = tracker.snapshot
        if (match == null || selectedDeck?.matchesPowerLog(match) == false) selectedDeck = null
        if (match != null && match.result == null && !manualDeckChoice) {
            selectedDeck = defaultDeck(match)
        }
        publishPrediction(match)
        sessionState.value = PowerLogSessionState(true, match, selectedDeck, revision, offeredDecision, logTruncated)
    }

    private fun clearMatch() {
        tracker = PowerLogTracker(captureCombatTags = true)
        trackedGame = null
        activeQueuedDeck = null
        selectedDeck = null
        manualDeckChoice = false
        logTruncated = false
        clearPrediction()
        revision++
    }

    private fun appendForPrediction(line: String) {
        if (isPowerLogMatchStart(line)) {
            clearPrediction()
            if (cloud.canPredict) {
                matchId = UUID.randomUUID().toString()
                matchLog = ByteArrayOutputStream()
            }
        }
        if (matchId != null) matchLogLineCount++
        val log = matchLog ?: return
        val bytes = line.encodeToByteArray()
        if (bytes.size >= MAX_POWER_LOG_BYTES - log.size()) {
            matchLog = null
            return
        }
        log.write(bytes)
        log.write('\n'.code)
    }

    private fun defaultDeck(match: PowerLogSnapshot): Deck? {
        val startingDeck = match.startingDeck
        val queued = activeQueuedDeck
        if (queued != null && queued.matchesPowerLog(match) &&
            (startingDeck == null || startingDeck.size !in COMPLETE_DECK_SIZES || queued.cardCount == startingDeck.size)
        ) return queued
        val heroClass = match.localHeroClass() ?: return null
        if (startingDeck == null) return null
        val candidates = savedDecks().filter { it.heroClass == heroClass && it.matchesPowerLog(match) }
        return identifyPowerLogDeck(startingDeck, candidates, cards())
    }

    private fun observePrediction(match: PowerLogSnapshot?, completeRead: Boolean, idle: Boolean) {
        val id = matchId
        val anchor = if (id != null && cloud.canPredict) match?.activePredictionAnchor(matchLogLineCount) else null
        if (id == null || anchor == null || match == null) {
            decisionGate.onRead(null)
            activeDecision = null
            stableDecision = null
            observation = null
            return
        }
        val key = PredictionKey(id, anchor.rawTurn, anchor.optionsId, anchor.kind)
        if (key.kind == PowerLogPredictionKind.ACTION) {
            observation = null
            activeDecision = key
            if (stableDecision?.key != key) stableDecision = null
            val settled = if (idle) decisionGate.onEmptyRead() else decisionGate.onActionPoll(match, completeRead)
            if (settled != null) recordCheckpoint(key)
            return
        }
        decisionGate.onRead(null)
        val position = when (key.kind) {
            PowerLogPredictionKind.MULLIGAN -> match.openingChoice
            PowerLogPredictionKind.CHOICE -> match.generalChoice
            PowerLogPredictionKind.OPPONENT -> OpponentPosition(
                publicSide(match.friendly), publicSide(match.opponent), match.publicPlays.lastOrNull()?.entityId,
            )
            PowerLogPredictionKind.ACTION -> error("Action packets use the decision gate")
        }
        val next = PhaseObservation(key.kind, key.rawTurn, key.optionsId.takeUnless { key.kind == PowerLogPredictionKind.OPPONENT }, position)
        val quietPolls = when (key.kind) {
            PowerLogPredictionKind.MULLIGAN -> 0
            PowerLogPredictionKind.CHOICE -> 1
            PowerLogPredictionKind.OPPONENT -> 2
            PowerLogPredictionKind.ACTION -> error("Action packets use the decision gate")
        }
        val previousOpponent = activeDecision?.takeIf { key.kind == PowerLogPredictionKind.OPPONENT &&
            it.kind == key.kind && it.matchId == key.matchId && it.rawTurn == key.rawTurn }
        if (observation != next) {
            observation = next
            unchangedPolls = 0
            stableDecision = null
            previousOpponent?.let(cloud::prepareOpponentRefresh)
        } else if (completeRead && unchangedPolls < quietPolls) {
            unchangedPolls++
        }
        if (completeRead && unchangedPolls >= quietPolls) recordCheckpoint(key)
        activeDecision = if (key.kind == PowerLogPredictionKind.OPPONENT) stableDecision?.key ?: previousOpponent else key
    }

    private fun recordCheckpoint(key: PredictionKey) {
        val log = matchLog ?: return
        if (stableDecision == null && matchLogLineCount > 0)
            stableDecision = PredictionCheckpoint(key, matchLogLineCount, log.size())
    }

    private fun publishPrediction(match: PowerLogSnapshot?) {
        val key = activeDecision
        val selected = selectedDeck?.takeIf { it.cardCount in COMPLETE_DECK_SIZES }
        if (key != null && key == submittedDecision && selected != submittedDeck) {
            if (offeredDecision != null) cloud.showLive(null)
            offeredDecision = null
            return
        }
        val log = matchLog
        if (key != offeredDecision || (key != null && submittedDecision != key)) {
            offeredDecision = key
            cloud.showLive(key, when {
                key != null && log == null -> PredictionStage.LOG_TOO_LARGE
                key != null && selected == null -> PredictionStage.NEED_DECK
                key != null -> PredictionStage.WAITING
                else -> PredictionStage.IDLE
            })
        }
        val checkpoint = stableDecision
        if (match != null && checkpoint != null && key == checkpoint.key && key != submittedDecision && selected != null && log != null) {
            submittedDecision = key
            submittedDeck = selected
            val current = log.toByteArray()
            val prefix = if (current.size == checkpoint.logBytes) current else current.copyOf(checkpoint.logBytes)
            cloud.submitLive(PredictionInput(key, match.format, selected, prefix, checkpoint.sequence))
        }
    }

    private fun clearPrediction() {
        decisionGate.onRead(null)
        matchId = null
        matchLog = null
        matchLogLineCount = 0
        stableDecision = null
        activeDecision = null
        observation = null
        unchangedPolls = 0
        offeredDecision = null
        submittedDecision = null
        submittedDeck = null
        cloud.showLive(null)
    }

    private data class PredictionCheckpoint(val key: PredictionKey, val sequence: Int, val logBytes: Int)
    private data class TimedDeck(val deck: Deck, val timeOfDay: Long)
    private data class PhaseObservation(val kind: PowerLogPredictionKind, val turn: Int, val id: Int?, val position: Any?)

    private data class PublicSide(
        val board: List<PowerLogCard>,
        val hero: String?,
        val heroHealth: Int?,
        val heroAttack: Int?,
        val heroPower: String?,
        val weapon: String?,
        val weaponAttack: Int?,
        val weaponDurability: Int?,
        val cardsInHand: Int,
        val cardsInDeck: Int?,
        val secrets: Int,
        val mana: Int?,
    )

    private data class OpponentPosition(val friendly: PublicSide, val opponent: PublicSide, val latestPlayEntity: Int?)

    private fun publicSide(side: PowerLogSide): PublicSide = PublicSide(
        side.board, side.heroCardId, side.heroEffectiveHealth, side.heroAttack, side.heroPower?.cardId,
        side.weaponCardId, side.weaponAttack, side.weaponDurability, side.handCount, side.deckCount,
        side.secretCount, side.availableMana,
    )

    private companion object {
        val COMPLETE_DECK_SIZES = setOf(20, 30, 40)
        const val MAX_QUEUE_WAIT_MILLIS = 10 * 60 * 1_000L
        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1_000L
        const val TRUNCATED_LOG_MARKER = "Truncating log, which has reached the size limit"
    }

}
