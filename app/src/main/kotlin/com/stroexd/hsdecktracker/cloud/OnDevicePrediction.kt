package com.stroexd.hsdecktracker.cloud

import com.stroexd.hsdecktracker.core.prediction.DirectPredictionRequest
import com.stroexd.hsdecktracker.core.prediction.DirectPredictionResult
import com.stroexd.hsdecktracker.core.prediction.NeteaseDirectPredictor
import com.stroexd.hsdecktracker.core.prediction.PredictStateLogReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient

internal data class OnDeviceResult(
    val winRatePercent: Double? = null,
    val recommendations: List<CloudAction>? = null,
    val errorCode: String? = null,
)

internal class OnDevicePrediction(
    private val session: NeteaseGuestSession,
    private val predictor: NeteaseDirectPredictor,
    private val cardIdByDbfId: (Int) -> String?,
    private val cardNameById: (String) -> String?,
) {
    constructor(
        session: NeteaseGuestSession,
        httpClient: OkHttpClient,
        cardIdByDbfId: (Int) -> String?,
        cardNameById: (String) -> String?,
    ) : this(session, NeteaseDirectPredictor(httpClient), cardIdByDbfId, cardNameById)

    suspend fun prepare() {
        session.profile()
    }

    suspend fun predict(input: PredictionInput): OnDeviceResult {
        val key = input.key
        val coroutine = currentCoroutineContext()
        val preview = input.log.inputStream().reader(Charsets.UTF_8).use { reader ->
            PredictStateLogReader.readActive(reader, key.kind, coroutine::ensureActive)
        }
        if (preview == null || preview.rawTurn != key.rawTurn || preview.optionsId != key.optionsId ||
            preview.format != input.format || preview.lastLineNumber != input.sequence ||
            preview.unresolvedFields.any { it.endsWith(".sub_options") }
        ) {
            return OnDeviceResult(errorCode = "unsupported_decision")
        }

        val request = DirectPredictionRequest(
            preview = preview,
            logBytes = input.log,
            deck = input.deck.toDefinition(),
            cardIdByDbfId = cardIdByDbfId,
            cardNameById = cardNameById,
        )

        val profile = session.profile()
        val first = predictor.predict(request, profile)
        if (first.errorCode != "predictor_auth_required") return first.toOnDeviceResult()
        session.invalidate(profile)
        return predictor.predict(request, session.profile()).toOnDeviceResult()
    }

    private fun DirectPredictionResult.toOnDeviceResult(): OnDeviceResult = OnDeviceResult(
        winRatePercent = winRatePercent,
        recommendations = recommendations?.map { step ->
            CloudAction(
                action = step.action,
                card = step.card,
                target = step.target,
                opponentTarget = step.opponentTarget,
                targetHero = step.targetHero,
                opponentTargetHero = step.opponentTargetHero,
                position = step.position,
                subOption = step.subOption,
            )
        },
        errorCode = errorCode,
    )
}
