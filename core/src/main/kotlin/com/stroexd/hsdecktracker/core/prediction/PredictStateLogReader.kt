package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.powerlog.PowerLogDecisionGate
import com.stroexd.hsdecktracker.core.powerlog.PowerLogChoiceOption
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import com.stroexd.hsdecktracker.core.powerlog.PowerLogTracker
import com.stroexd.hsdecktracker.core.powerlog.projectNativeOptionsForAudit
import java.io.Reader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Offline comparison data for an ordinary constructed decision. */
data class PredictStatePreview(
    val rawTurn: Int,
    val optionsId: Int,
    val optionCount: Int,
    val format: GameFormat,
    val comparisonJson: JsonObject,
    val unresolvedFields: List<String>,
    val endedAtLoggedAction: Boolean,
    val lastLineNumber: Int,
    val localPlayerId: Int,
    val kind: PowerLogPredictionKind = PowerLogPredictionKind.ACTION,
)

object PredictStateLogReader {
    private val sendOption = Regex("""(?:[DWE] [\d:.]+ +)?GameState\.SendOption\(\) - .+""")

    /** Replays a Power.log stream and returns its latest matching decision. The caller closes [reader]. */
    fun read(reader: Reader, rawTurn: Int? = null, optionsId: Int? = null): PredictStatePreview? =
        readDecision(reader, rawTurn, optionsId, activeOnly = false)

    /** Accepts only a decision still pending at the end of the supplied log prefix. */
    fun readActive(
        reader: Reader,
        kind: PowerLogPredictionKind = PowerLogPredictionKind.ACTION,
        checkCancelled: () -> Unit = {},
    ): PredictStatePreview? = readDecision(reader, null, null, activeOnly = true, kind = kind, checkCancelled = checkCancelled)

    fun readLatestActive(reader: Reader): PredictStatePreview? =
        readDecision(reader, null, null, activeOnly = true, kind = null)

    private fun readDecision(reader: Reader, rawTurn: Int?, optionsId: Int?, activeOnly: Boolean,
        kind: PowerLogPredictionKind? = PowerLogPredictionKind.ACTION,
        checkCancelled: () -> Unit = {},
    ): PredictStatePreview? {
        val tracker = PowerLogTracker(captureCombatTags = true)
        val gate = PowerLogDecisionGate()
        val input = reader.buffered()
        var latest: PredictStatePreview? = null
        var lineNumber = 0

        fun capture(endedAtLoggedAction: Boolean): PredictStatePreview? {
            val candidate = tracker.snapshot ?: return null
            val phase = kind ?: candidate.activePredictionAnchor(lineNumber)?.kind ?: return null
            if (phase != PowerLogPredictionKind.ACTION) return phasePreview(candidate, lineNumber, phase)
            val options = candidate.actionOptions ?: return null
            val localPlayerId = candidate.friendly.playerId ?: return null
            if (rawTurn != null && candidate.turn != rawTurn) return null
            if (optionsId != null && options.packetId != optionsId) return null

            // A logged SendOption or the end of the file marks the end of the available read batch.
            gate.onRead(candidate)
            repeat(2) { gate.onEmptyRead() }
            val snapshot = gate.onEmptyRead() ?: return null
            val projection = projectNativeOptionsForAudit(snapshot, tracker.observedBattlefieldTags()) ?: return null
            return PredictStatePreview(
                rawTurn = snapshot.turn,
                optionsId = options.packetId,
                optionCount = options.options.size,
                format = snapshot.format,
                comparisonJson = projection.comparisonJson,
                unresolvedFields = projection.unresolvedFields,
                endedAtLoggedAction = endedAtLoggedAction,
                lastLineNumber = lineNumber,
                localPlayerId = localPlayerId,
            )
        }

        var line = input.readLine()?.removePrefix("\uFEFF")
        while (line != null) {
            if (lineNumber % 256 == 0) checkCancelled()
            if (tracker.snapshot?.actionOptions != null && sendOption.matches(line.trim())) {
                if (!activeOnly) latest = capture(endedAtLoggedAction = true) ?: latest
                gate.onRead(null)
            }
            tracker.accept(line)
            lineNumber++
            line = input.readLine()
        }
        checkCancelled()
        return capture(endedAtLoggedAction = false) ?: latest
    }

    private fun phasePreview(snapshot: PowerLogSnapshot, lines: Int, kind: PowerLogPredictionKind): PredictStatePreview? {
        val anchor = snapshot.activePredictionAnchor(lines)?.takeIf { it.kind == kind } ?: return null
        val opening = snapshot.openingChoice
        val choice = snapshot.generalChoice
        val player = (if (kind == PowerLogPredictionKind.MULLIGAN) opening?.playerId else snapshot.friendly.playerId)
            ?: return null
        val options = if (opening != null) opening.cards.mapIndexed { index, card ->
            PowerLogChoiceOption(index, checkNotNull(card.entityId), card.cardId)
        } else choice?.options.orEmpty()
        val state = buildJsonObject {
            if (kind == PowerLogPredictionKind.MULLIGAN || kind == PowerLogPredictionKind.CHOICE) {
                putJsonObject("choice") {
                    put("id", anchor.optionsId)
                    put("sourceEntityId", opening?.sourceEntityId ?: checkNotNull(choice?.sourceEntityId))
                    put("choiceType", if (opening != null) "MULLIGAN" else "GENERAL")
                    put("min", choice?.minSelections ?: 0)
                    put("max", choice?.maxSelections ?: options.size)
                    putJsonArray("options") {
                        options.forEach { option -> add(buildJsonObject {
                            put("entityId", option.entityId)
                            option.cardId?.let { put("cardId", it) }
                        }) }
                    }
                }
            }
        }
        val envelope = buildJsonObject {
            putJsonObject("data") {
                putJsonArray("turns") { add(buildJsonObject {
                    putJsonArray("processes") { add(buildJsonObject { put("state", state) }) }
                }) }
            }
        }
        return PredictStatePreview(anchor.rawTurn, anchor.optionsId, options.size, snapshot.format, envelope,
            emptyList(), endedAtLoggedAction = false, lastLineNumber = lines, localPlayerId = player, kind = kind)
    }
}
