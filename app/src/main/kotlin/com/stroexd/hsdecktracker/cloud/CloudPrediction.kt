package com.stroexd.hsdecktracker.cloud

import android.os.SystemClock
import android.util.Log
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.prediction.PowerLogPredictionKind
import com.stroexd.hsdecktracker.core.prediction.PredictStateLogReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

internal const val MAX_POWER_LOG_BYTES = 16 * 1024 * 1024

internal data class PredictionKey(val matchId: String, val rawTurn: Int, val optionsId: Int, val kind: PowerLogPredictionKind)

internal data class PredictionInput(
    val key: PredictionKey,
    val format: GameFormat,
    val deck: Deck,
    val log: ByteArray,
    val sequence: Int,
)

internal enum class PredictionStage { IDLE, NEED_DECK, LOG_TOO_LARGE, NO_DECISION, WAITING, PREDICTING, READY, FAILED }

internal data class CloudPredictionState(
    val key: PredictionKey? = null,
    val stage: PredictionStage = PredictionStage.IDLE,
    val result: PredictionResult? = null,
    val errorCode: String? = null,
)

@Serializable
internal data class CloudAction(
    val action: String,
    val card: JsonElement? = null,
    val target: JsonElement? = null,
    val opponentTarget: JsonElement? = null,
    val targetHero: JsonElement? = null,
    val opponentTargetHero: JsonElement? = null,
    val position: Int? = null,
    val subOption: JsonElement? = null,
)

internal data class PredictionResult(
    val key: PredictionKey,
    val winRatePercent: Double?,
    val recommendations: List<CloudAction>?,
)

private class PredictionLogTooLarge : IOException()

internal class CloudPrediction(
    private val scope: CoroutineScope,
    private val onDevice: OnDevicePrediction?,
) {
    val canPredict: Boolean get() = onDevice != null
    private val live = MutableStateFlow(CloudPredictionState())
    val liveState = live.asStateFlow()
    private val replay = MutableStateFlow(CloudPredictionState())
    val replayState = replay.asStateFlow()
    private var liveJob: Job? = null
    private var replayJob: Job? = null

    @Synchronized
    fun showLive(key: PredictionKey?, stage: PredictionStage = PredictionStage.IDLE) {
        if (live.value.key == key && (liveJob?.isActive == true || live.value.stage == PredictionStage.READY)) return
        liveJob?.cancel()
        live.value = CloudPredictionState(key, stage, live.value.result.forOpponentTurn(key))
    }

    @Synchronized
    fun prepareOpponentRefresh(key: PredictionKey) {
        val previous = live.value
        if (key.kind != PowerLogPredictionKind.OPPONENT || previous.key != key) return
        liveJob?.cancel()
        live.value = previous.copy(stage = PredictionStage.PREDICTING, errorCode = null)
    }

    @Synchronized
    fun submitLive(input: PredictionInput) {
        val engine = onDevice ?: return
        if (live.value.key == input.key && (liveJob?.isActive == true || live.value.stage == PredictionStage.READY)) return
        liveJob?.cancel()
        live.value = CloudPredictionState(input.key, PredictionStage.PREDICTING, live.value.result.forOpponentTurn(input.key))
        liveJob = scope.launch(Dispatchers.IO) { runPrediction(input, engine, live) }
    }

    @Synchronized
    fun submitReplay(deck: Deck, rawTurn: Int?, optionsId: Int?, openLog: () -> InputStream?) {
        val engine = onDevice ?: return
        replayJob?.cancel()
        replay.value = CloudPredictionState(stage = PredictionStage.PREDICTING)
        replayJob = scope.launch(Dispatchers.IO) {
            val input = try {
                val bytes = checkNotNull(openLog()).use { it.readLimited(MAX_POWER_LOG_BYTES) }
                val currentFrame = if (rawTurn == null && optionsId == null) {
                    bytes.inputStream().reader(Charsets.UTF_8).use(PredictStateLogReader::readLatestActive)
                } else null
                val frame = currentFrame ?: bytes.inputStream().reader(Charsets.UTF_8).use { reader ->
                    PredictStateLogReader.read(reader, rawTurn, optionsId)
                }
                if (frame == null || (frame.format != deck.format &&
                        !(frame.format == GameFormat.WILD && deck.format == GameFormat.STANDARD))) {
                    ensureActive()
                    replay.value = CloudPredictionState(stage = PredictionStage.NO_DECISION)
                    return@launch
                }
                val (prefix, sequence) = bytes.inputStream().reader(Charsets.UTF_8).useLines { lines ->
                    val match = ByteArrayOutputStream()
                    var lineCount = 0
                    lines.take(frame.lastLineNumber).forEach { line ->
                        val startsMatch = isPowerLogMatchStart(line)
                        if (startsMatch) {
                            match.reset()
                            lineCount = 0
                        }
                        if (startsMatch || lineCount > 0) {
                            match.write(line.encodeToByteArray())
                            match.write('\n'.code)
                            lineCount++
                        }
                    }
                    require(lineCount > 0)
                    match.toByteArray() to lineCount
                }
                PredictionInput(
                    PredictionKey(UUID.randomUUID().toString(), frame.rawTurn, frame.optionsId, frame.kind),
                    frame.format, deck, prefix, sequence,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ensureActive()
                replay.value = CloudPredictionState(stage = if (failure is PredictionLogTooLarge)
                    PredictionStage.LOG_TOO_LARGE else PredictionStage.NO_DECISION)
                return@launch
            }
            ensureActive()
            replay.value = CloudPredictionState(input.key, PredictionStage.PREDICTING)
            runPrediction(input, engine, replay)
        }
    }

    private suspend fun runPrediction(
        input: PredictionInput,
        engine: OnDevicePrediction,
        state: MutableStateFlow<CloudPredictionState>,
    ) {
        val key = input.key
        val job = currentCoroutineContext()[Job]
        val liveRequest = state === live
        val startedAt = SystemClock.elapsedRealtime()
        if (liveRequest) {
            Log.i("HearthLogPrediction", "start ${key.kind.wireName} turn=${key.rawTurn} options=${key.optionsId} sequence=${input.sequence}")
        }
        val result = try {
            engine.predict(input)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("HearthLogPrediction", "${key.kind.wireName}: ${failure.javaClass.simpleName}")
            OnDeviceResult(errorCode = "connection")
        }
        val error = result.errorCode ?: if (!completeResult(key.kind, result.winRatePercent, result.recommendations))
            "incomplete_result" else null
        val published = synchronized(this) {
            val previous = state.value
            if (job?.isActive == false || previous.key != key) return
            val next = if (error == null) {
                val prediction = PredictionResult(key, result.winRatePercent, result.recommendations)
                CloudPredictionState(key, PredictionStage.READY, prediction)
            } else {
                CloudPredictionState(key, PredictionStage.FAILED, previous.result.forOpponentTurn(key), error)
            }
            state.compareAndSet(previous, next)
        }
        if (published && error != null) {
            Log.w("HearthLogPrediction", "${key.kind.wireName}: $error")
        } else if (published && liveRequest) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i("HearthLogPrediction", "ready ${key.kind.wireName} turn=${key.rawTurn} options=${key.optionsId} " +
                "sequence=${input.sequence} recommendations=${result.recommendations.orEmpty().size} elapsedMs=$elapsed")
        }
    }

    private fun completeResult(kind: PowerLogPredictionKind, rate: Double?, recommendations: List<CloudAction>?): Boolean =
        rate != null && rate.isFinite() && rate in 0.0..100.0 &&
            ((kind != PowerLogPredictionKind.CHOICE && kind != PowerLogPredictionKind.MULLIGAN) || !recommendations.isNullOrEmpty())
}

private fun PredictionResult?.forOpponentTurn(next: PredictionKey?): PredictionResult? =
    this?.takeIf { next?.kind == PowerLogPredictionKind.OPPONENT && key.kind == next.kind &&
        key.matchId == next.matchId && key.rawTurn == next.rawTurn && winRatePercent != null }

private fun InputStream.readLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.size() > limit - read) throw PredictionLogTooLarge()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun isPowerLogMatchStart(line: String): Boolean =
    line.substringAfter("GameState.DebugPrintPower() - ", "").trim() == "CREATE_GAME"
