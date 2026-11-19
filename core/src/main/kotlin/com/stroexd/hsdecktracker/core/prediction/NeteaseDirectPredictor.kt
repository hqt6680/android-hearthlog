package com.stroexd.hsdecktracker.core.prediction

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.DeckDefinition
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class NeteaseGuestProfile(
    val endpoint: String,
    val sid: String,
    val cookie: String,
    val userAgent: String,
    val clientType: String,
    val version: String,
    val rc4Key: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeteaseGuestProfile) return false
        return endpoint == other.endpoint &&
            sid == other.sid &&
            cookie == other.cookie &&
            userAgent == other.userAgent &&
            clientType == other.clientType &&
            version == other.version &&
            rc4Key.contentEquals(other.rc4Key)
    }

    override fun hashCode(): Int {
        var result = endpoint.hashCode()
        result = 31 * result + sid.hashCode()
        result = 31 * result + cookie.hashCode()
        result = 31 * result + userAgent.hashCode()
        result = 31 * result + clientType.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + rc4Key.contentHashCode()
        return result
    }
}

data class PredictedAction(
    val action: String,
    val card: JsonObject? = null,
    val target: JsonObject? = null,
    val opponentTarget: JsonObject? = null,
    val targetHero: JsonObject? = null,
    val opponentTargetHero: JsonObject? = null,
    val position: Int? = null,
    val subOption: JsonObject? = null,
)

data class DirectPredictionResult(
    val winRatePercent: Double? = null,
    val recommendations: List<PredictedAction>? = null,
    val errorCode: String? = null,
)

class DirectPredictionRequest(
    val preview: PredictStatePreview,
    val logBytes: ByteArray,
    val deck: DeckDefinition,
    val cardIdByDbfId: (Int) -> String?,
    val cardNameById: (String) -> String? = { null },
)

/**
 * Pure-Kotlin client for NetEase's Constructed `standard_substep` prediction endpoint.
 */
class NeteaseDirectPredictor(
    client: OkHttpClient,
    private val totalBudgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val uuidGenerator: () -> String = { NeteaseDirectCodec.newRequestUuid() },
) {
    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun predict(
        request: DirectPredictionRequest,
        profile: NeteaseGuestProfile,
    ): DirectPredictionResult {
        val preview = request.preview
        val minTurn = if (preview.kind == PowerLogPredictionKind.MULLIGAN) 0 else 1
        if (preview.rawTurn < minTurn ||
            preview.optionsId < 1 || preview.localPlayerId <= 0 || preview.lastLineNumber <= 0
        ) {
            return DirectPredictionResult(errorCode = "invalid_log")
        }
        if (preview.format == GameFormat.STANDARD && request.deck.format != GameFormat.STANDARD) {
            return DirectPredictionResult(errorCode = "invalid_deck")
        }
        val cardGroup = buildCardGroup(request.deck, request.cardIdByDbfId)
            ?: return DirectPredictionResult(errorCode = "invalid_deck")

        val prepared = preparePayload(request, cardGroup)
            ?: return DirectPredictionResult(errorCode = "unsupported_decision")
        if (prepared.errorCode != null) return DirectPredictionResult(errorCode = prepared.errorCode)

        val deadlineMillis = nowMillis() + totalBudgetMillis
        val tasks = if (preview.kind == PowerLogPredictionKind.OPPONENT) "winrate" else "rec,winrate"
        val maxAttempts = if (preview.kind == PowerLogPredictionKind.ACTION) 3 else 2

        var lastErrorCode = "predictor_unavailable"
        var adviceWithoutRate: List<PredictedAction>? = null

        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            val remaining = deadlineMillis - nowMillis()
            if (remaining < MIN_ATTEMPT_BUDGET_MILLIS) {
                lastErrorCode = "timeout"
                break
            }
            val envelopeJson = buildEnvelopeJson(prepared.battleTag, prepared.stateJson, prepared.cardGroup)
            val outcome = sendPost(profile, envelopeJson, tasks)
            when (outcome) {
                is TransportOutcome.Failure -> {
                    if (!outcome.retryable) return DirectPredictionResult(errorCode = outcome.errorCode)
                    lastErrorCode = outcome.errorCode
                }
                is TransportOutcome.Success -> {
                    val mapped = mapResponse(outcome.json, prepared, request.cardNameById)
                    if (mapped.errorCode == null) {
                        if (preview.kind == PowerLogPredictionKind.OPPONENT || mapped.winRatePercent != null) {
                            return DirectPredictionResult(
                                winRatePercent = mapped.winRatePercent,
                                recommendations = mapped.recommendations,
                            )
                        }
                        if (!mapped.recommendations.isNullOrEmpty() &&
                            (preview.kind == PowerLogPredictionKind.MULLIGAN || preview.kind == PowerLogPredictionKind.CHOICE)
                        ) {
                            adviceWithoutRate = mapped.recommendations
                            lastErrorCode = "prediction_winrate_missing"
                            break
                        }
                        lastErrorCode = "prediction_response_missing"
                    } else if (mapped.errorCode == "predictor_auth_required") {
                        return DirectPredictionResult(errorCode = "predictor_auth_required")
                    } else {
                        lastErrorCode = mapped.errorCode
                        if (!mapped.retryable) break
                    }
                }
            }
            if (attempt < maxAttempts) {
                val sleepMs = minOf(retryDelayMillis * attempt, maxOf(0L, deadlineMillis - nowMillis() - MIN_ATTEMPT_BUDGET_MILLIS))
                if (sleepMs <= 0L) break
                delayMillis(sleepMs)
            }
        }

        if (adviceWithoutRate != null && nowMillis() + MIN_ATTEMPT_BUDGET_MILLIS <= deadlineMillis) {
            val winRateOnlyState = buildJsonObject {
                put("entities", prepared.entitiesJson)
                put("played_cards", prepared.playedCardsJson)
                put("deck_card_counter", prepared.deckCardCounterJson)
                prepared.choicesObject?.let {
                    put("choices_id", prepared.choicesId!!)
                    put("choices", it)
                }
            }
            val winRateEnvelope = buildEnvelopeJson(prepared.battleTag, winRateOnlyState, prepared.cardGroup)
            when (val followUp = sendPost(profile, winRateEnvelope, "winrate")) {
                is TransportOutcome.Success -> {
                    val winRate = runCatching {
                        parseWinRateOnly(followUp.json)
                    }.getOrNull()
                    if (winRate != null) {
                        return DirectPredictionResult(winRatePercent = winRate, recommendations = adviceWithoutRate)
                    }
                }
                is TransportOutcome.Failure -> {
                    if (followUp.errorCode == "predictor_auth_required") {
                        return DirectPredictionResult(errorCode = "predictor_auth_required")
                    }
                }
            }
        }

        return DirectPredictionResult(errorCode = lastErrorCode)
    }

    private class PreparedState(
        val battleTag: String,
        val cardGroup: String,
        val entitiesJson: JsonObject,
        val playedCardsJson: JsonObject,
        val deckCardCounterJson: JsonObject,
        val stateJson: JsonObject,
        val optionsList: List<JsonObject>,
        val choiceOptions: List<ChoiceOptionMeta>,
        val choicesId: Int?,
        val choicesObject: JsonObject?,
        val choiceMax: Int,
        val entityLookup: Map<Int, Pair<String, JsonObject>>,
        val kind: PowerLogPredictionKind,
        val errorCode: String? = null,
    )

    internal data class ChoiceOptionMeta(val entityId: Int, val cardId: String?)

    private suspend fun preparePayload(
        request: DirectPredictionRequest,
        cardGroup: String,
    ): PreparedState? {
        val preview = request.preview
        val coroutine = currentCoroutineContext()

        val allLines = request.logBytes.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().take(preview.lastLineNumber).toList()
        }
        val tracker = NeteasePowerLogStateTracker()
        var lineIndex = 0
        for (line in allLines) {
            if ((lineIndex++ and 255) == 0) coroutine.ensureActive()
            if (line.length > MAX_LOG_LINE_CHARS) {
                return PreparedState(
                    "", cardGroup, JsonObject(emptyMap()), JsonObject(emptyMap()), JsonObject(emptyMap()),
                    JsonObject(emptyMap()), emptyList(), emptyList(), null, null, 0, emptyMap(),
                    preview.kind, errorCode = "invalid_log",
                )
            }
            tracker.processLine(line)
        }

        val mulliganChoiceId = if (preview.kind == PowerLogPredictionKind.MULLIGAN) preview.optionsId else null
        val battleTag = NeteasePowerLogStateTracker.readLocalBattleTag(
            lines = allLines.asSequence(),
            lastLine = preview.lastLineNumber,
            localPlayerId = preview.localPlayerId,
            mulliganChoiceId = mulliganChoiceId,
            checkCancelled = coroutine::ensureActive,
        ) ?: return null

        val preferredChoiceId = if (preview.kind == PowerLogPredictionKind.MULLIGAN || preview.kind == PowerLogPredictionKind.CHOICE) {
            preview.optionsId
        } else null
        val snapshot = tracker.buildSnapshot(preview.localPlayerId, preferredChoiceId)
        val previewState = preview.comparisonJson["data"]?.jsonObject
            ?.get("turns")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("processes")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("state")?.jsonObject

        val previewChoiceObj = previewState?.get("choice")?.jsonObject
        val choiceMeta = if (preview.kind == PowerLogPredictionKind.MULLIGAN || preview.kind == PowerLogPredictionKind.CHOICE) {
            val previewOptions = previewChoiceObj?.get("options")?.jsonArray.orEmpty().mapNotNull { el ->
                val obj = el.jsonObject
                val entityId = obj["entityId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val cardId = obj["cardId"]?.jsonPrimitive?.contentOrNull
                ChoiceOptionMeta(entityId, cardId)
            }
            if (previewOptions.isEmpty()) return null
            previewOptions
        } else emptyList()

        val choiceSourceId = snapshot.activeChoice?.sourceEntityId?.takeIf { it > 0 }
            ?: previewChoiceObj?.get("sourceEntityId")?.jsonPrimitive?.intOrNull
            ?: 1
        val normalizedEntities = normalizeEntities(
            rawEntities = snapshot.entities,
            kind = preview.kind,
            format = preview.format,
            rawTurn = preview.rawTurn,
            localPlayerId = preview.localPlayerId,
            choiceMeta = choiceMeta,
            choiceSourceId = choiceSourceId,
        ) ?: return PreparedState(
            battleTag, cardGroup, JsonObject(emptyMap()), JsonObject(emptyMap()), JsonObject(emptyMap()),
            JsonObject(emptyMap()), emptyList(), emptyList(), null, null, 0, emptyMap(),
            preview.kind, errorCode = "invalid_log",
        )

        val entityLookup = buildEntityLookup(normalizedEntities)
        if ("my_hero" !in normalizedEntities || "opp_hero" !in normalizedEntities) {
            return null
        }

        val optionsList: List<JsonObject>
        var choicesId: Int? = null
        var choicesObject: JsonObject? = null
        var choiceMax = 0

        val stateJson = when (preview.kind) {
            PowerLogPredictionKind.OPPONENT -> {
                optionsList = emptyList()
                buildJsonObject {
                    put("entities", normalizedEntities)
                    put("played_cards", snapshot.playedCards)
                    put("deck_card_counter", snapshot.deckCardCounter)
                }
            }
            PowerLogPredictionKind.ACTION -> {
                val rawOptions = previewState?.get("options")?.jsonArray.orEmpty().map { it.jsonObject }
                if (rawOptions.isEmpty() || rawOptions.none { it["type"]?.jsonPrimitive?.contentOrNull == "END_TURN" }) {
                    return null
                }
                optionsList = rawOptions.map { option -> normalizeOptionPlayerId(option, entityLookup) }
                buildJsonObject {
                    put("options_id", preview.optionsId)
                    put("entities", normalizedEntities)
                    put("options", JsonArray(optionsList))
                    put("played_cards", snapshot.playedCards)
                    put("deck_card_counter", snapshot.deckCardCounter)
                }
            }
            PowerLogPredictionKind.MULLIGAN, PowerLogPredictionKind.CHOICE -> {
                optionsList = emptyList()
                val tracked = snapshot.activeChoice?.takeIf { it.id == preview.optionsId }
                val choiceEntityIds = (tracked?.entityIds?.takeIf { it.isNotEmpty() }
                    ?: choiceMeta.map { it.entityId }).distinct().filter { it > 0 }
                if (choiceEntityIds.isEmpty()) return null
                val countMin = tracked?.countMin ?: previewChoiceObj?.get("min")?.jsonPrimitive?.intOrNull ?: 0
                choiceMax = tracked?.countMax ?: previewChoiceObj?.get("max")?.jsonPrimitive?.intOrNull ?: choiceEntityIds.size
                val choiceType = tracked?.choiceType?.takeIf { it.isNotBlank() }
                    ?: if (preview.kind == PowerLogPredictionKind.MULLIGAN) "MULLIGAN" else "GENERAL"
                choicesId = preview.optionsId
                choicesObject = buildJsonObject {
                    put("source_entity_id", choiceSourceId)
                    putJsonArray("entities") { choiceEntityIds.forEach { add(JsonPrimitive(it)) } }
                    put("count_max", maxOf(1, choiceMax))
                    put("count_min", maxOf(0, countMin))
                    put("choice_type", choiceType)
                }
                buildJsonObject {
                    put("choices_id", choicesId)
                    put("entities", normalizedEntities)
                    put("choices", choicesObject)
                    put("played_cards", snapshot.playedCards)
                    put("deck_card_counter", snapshot.deckCardCounter)
                }
            }
        }

        return PreparedState(
            battleTag = battleTag,
            cardGroup = cardGroup,
            entitiesJson = normalizedEntities,
            playedCardsJson = snapshot.playedCards,
            deckCardCounterJson = snapshot.deckCardCounter,
            stateJson = stateJson,
            optionsList = optionsList,
            choiceOptions = choiceMeta,
            choicesId = choicesId,
            choicesObject = choicesObject,
            choiceMax = maxOf(1, choiceMax),
            entityLookup = entityLookup,
            kind = preview.kind,
        )
    }

    private fun normalizeEntities(
        rawEntities: JsonObject,
        kind: PowerLogPredictionKind,
        format: GameFormat,
        rawTurn: Int,
        localPlayerId: Int,
        choiceMeta: List<ChoiceOptionMeta>,
        choiceSourceId: Int,
    ): JsonObject? {
        val gameObj = rawEntities["game"]?.jsonArray?.singleOrNull()?.jsonObject ?: return null
        val observedTurn = gameObj["TURN"]?.jsonPrimitive?.intOrNull ?: 0
        if (observedTurn != rawTurn) return null

        val normalizedGame = buildJsonObject {
            for ((k, v) in gameObj) {
                if (k == "_CARD_ID") continue
                put(k, v)
            }
            if ("BUILD_NUMBER" !in gameObj) put("BUILD_NUMBER", 246003)
            if ("GAME_TYPE" !in gameObj) put("GAME_TYPE", "GT_RANKED")
            if ("FormatType" !in gameObj) {
                put("FormatType", if (format == GameFormat.WILD) "FT_WILD" else "FT_STANDARD")
            }
            if ("SCENARIO_ID" !in gameObj) put("SCENARIO_ID", 2)
        }

        fun normalizePlayerGroup(group: JsonArray?): JsonArray? {
            if (group == null || group.isEmpty()) return null
            val list = group.map { element ->
                val obj = element.jsonObject
                buildJsonObject {
                    for ((k, v) in obj) {
                        if (k == "_CARD_ID") continue
                        put(k, v)
                    }
                    if (kind != PowerLogPredictionKind.MULLIGAN && "MULLIGAN_STATE" !in obj) {
                        put("MULLIGAN_STATE", "DONE")
                    }
                }
            }
            return JsonArray(list)
        }

        val knownIds = HashSet<Int>()
        for ((_, value) in rawEntities) {
            val arr = value as? JsonArray ?: continue
            for (el in arr) {
                (el as? JsonObject)?.get("ENTITY_ID")?.jsonPrimitive?.intOrNull?.let(knownIds::add)
            }
        }

        val extraOthers = mutableListOf<JsonObject>()
        if (kind == PowerLogPredictionKind.CHOICE) {
            for (option in choiceMeta) {
                if (option.entityId !in knownIds) {
                    extraOthers += buildJsonObject {
                        put("ENTITY_ID", option.entityId)
                        put("CONTROLLER", localPlayerId)
                        put("ZONE", "SETASIDE")
                        if (choiceSourceId > 0) put("CREATOR", choiceSourceId)
                        option.cardId?.takeIf { it.isNotEmpty() }?.let { put("_CARD_ID", it) }
                    }
                }
            }
        }

        return buildJsonObject {
            put("game", JsonArray(listOf(normalizedGame)))
            normalizePlayerGroup(rawEntities["my_player"]?.jsonArray)?.let { put("my_player", it) }
            normalizePlayerGroup(rawEntities["opp_player"]?.jsonArray)?.let { put("opp_player", it) }
            for ((groupName, groupElement) in rawEntities) {
                if (groupName == "game" || groupName == "my_player" || groupName == "opp_player" || groupName == "others") {
                    continue
                }
                val arr = groupElement as? JsonArray ?: continue
                if (arr.isNotEmpty()) put(groupName, arr)
            }
            val mergedOthers = rawEntities["others"]?.jsonArray.orEmpty().toList() + extraOthers
            if (mergedOthers.isNotEmpty()) put("others", JsonArray(mergedOthers))
        }
    }

    private fun normalizeOptionPlayerId(
        option: JsonObject,
        entityLookup: Map<Int, Pair<String, JsonObject>>,
    ): JsonObject {
        val type = option["type"]?.jsonPrimitive?.contentOrNull
        if (type == "END_TURN" || "player_id" in option) return option
        val entityId = option["entity_id"]?.jsonPrimitive?.intOrNull ?: return option
        val controller = entityLookup[entityId]?.second?.get("CONTROLLER")?.jsonPrimitive?.intOrNull ?: return option
        return buildJsonObject {
            for ((k, v) in option) put(k, v)
            put("player_id", controller)
        }
    }

    private fun buildEntityLookup(entities: JsonObject): Map<Int, Pair<String, JsonObject>> = buildMap {
        for ((groupName, element) in entities) {
            val arr = element as? JsonArray ?: continue
            for (entry in arr) {
                val obj = entry as? JsonObject ?: continue
                val id = obj["ENTITY_ID"]?.jsonPrimitive?.intOrNull ?: continue
                if (id !in this) put(id, groupName to obj)
            }
        }
    }

    private fun buildEnvelopeJson(battleTag: String, stateJson: JsonObject, cardGroup: String): String =
        buildJsonObject {
            put("type", "netreloaded")
            put("uuid", uuidGenerator())
            put("btag", battleTag)
            putJsonObject("data") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        putJsonArray("processes") {
                            add(buildJsonObject {
                                put("state", stateJson)
                            })
                        }
                    })
                }
                putJsonObject("extra_infos") {
                    put("cardgroup", cardGroup)
                }
            }
        }.toString()

    private sealed interface TransportOutcome {
        data class Success(val json: String) : TransportOutcome
        data class Failure(val errorCode: String, val retryable: Boolean) : TransportOutcome
    }

    private suspend fun sendPost(
        profile: NeteaseGuestProfile,
        envelopeJson: String,
        tasks: String,
    ): TransportOutcome {
        val baseUrl = profile.endpoint.toHttpUrlOrNull()
            ?: return TransportOutcome.Failure("predictor_unavailable", retryable = false)
        val url = baseUrl.newBuilder()
            .addQueryParameter("sid", profile.sid)
            .addQueryParameter("compress", "1")
            .addQueryParameter("rank", "0")
            .addQueryParameter("client_type", profile.clientType)
            .addQueryParameter("version", profile.version)
            .build()

        val encodedData = NeteaseDirectCodec.encodeRequestData(envelopeJson.toByteArray(Charsets.UTF_8), profile.rc4Key)
        val formBody = FormBody.Builder()
            .add("data", encodedData)
            .add("tasks", tasks)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", profile.userAgent)
            .header("Cookie", profile.cookie)
            .header("Accept-Language", "zh-CN,en,*")
            .post(formBody)
            .build()

        val response = try {
            executeCall(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            return TransportOutcome.Failure("timeout", retryable = true)
        } catch (_: IOException) {
            return TransportOutcome.Failure("connection", retryable = true)
        }

        val httpCode = response.first
        val rawBody = response.second
        if (httpCode == 401 || httpCode == 403) {
            return TransportOutcome.Failure("predictor_auth_required", retryable = false)
        }
        if (httpCode == 408) {
            return TransportOutcome.Failure("timeout", retryable = true)
        }
        if (httpCode == 429 || httpCode >= 500) {
            return TransportOutcome.Failure("connection", retryable = true)
        }
        if (httpCode !in 200..299) {
            return TransportOutcome.Failure("predictor_unavailable", retryable = false)
        }

        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) {
            return TransportOutcome.Failure("prediction_response_missing", retryable = true)
        }
        if (trimmed.startsWith('{') || trimmed.startsWith('<') ||
            trimmed.startsWith("unauthorized", ignoreCase = true) ||
            trimmed.startsWith("forbidden", ignoreCase = true)
        ) {
            if (isAuthenticationFailureText(trimmed)) {
                return TransportOutcome.Failure("predictor_auth_required", retryable = false)
            }
            return TransportOutcome.Failure("predictor_auth_required", retryable = false)
        }

        val decrypted = try {
            NeteaseDirectCodec.decodeResponseData(trimmed, profile.rc4Key)
        } catch (_: IllegalArgumentException) {
            return TransportOutcome.Failure("predictor_auth_required", retryable = false)
        }

        val root = try {
            Json.parseToJsonElement(decrypted) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return TransportOutcome.Failure("predictor_auth_required", retryable = false)

        if (isAuthenticationFailureJson(root, decrypted)) {
            return TransportOutcome.Failure("predictor_auth_required", retryable = false)
        }

        return TransportOutcome.Success(decrypted)
    }

    private suspend fun executeCall(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { res ->
                            val body = res.body
                            val text = if (body == null) {
                                ""
                            } else {
                                val source = body.source()
                                if (source.request(MAX_RESPONSE_BYTES + 1)) {
                                    throw IOException("Prediction response exceeded limit")
                                }
                                body.string()
                            }
                            res.code to text
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    private data class MappedResponse(
        val winRatePercent: Double? = null,
        val recommendations: List<PredictedAction>? = null,
        val errorCode: String? = null,
        val retryable: Boolean = false,
    )

    private fun mapResponse(
        decryptedJson: String,
        prepared: PreparedState,
        cardNameById: (String) -> String?,
    ): MappedResponse {
        val root = runCatching { Json.parseToJsonElement(decryptedJson) as? JsonObject }.getOrNull()
            ?: return MappedResponse(errorCode = "predictor_auth_required")

        if (isPredictorNotReady(root, prepared.kind)) {
            return MappedResponse(errorCode = "prediction_response_missing", retryable = true)
        }

        val parsed = try {
            PredictResultParser.parse(decryptedJson)
        } catch (_: IllegalArgumentException) {
            return MappedResponse(errorCode = "prediction_response_missing", retryable = true)
        }

        if (prepared.kind == PowerLogPredictionKind.OPPONENT) {
            val rate = parsed.winRatePercent
            return if (rate != null) {
                MappedResponse(winRatePercent = rate, recommendations = emptyList())
            } else {
                MappedResponse(errorCode = "prediction_winrate_missing", retryable = true)
            }
        }

        val mappedActions = when (prepared.kind) {
            PowerLogPredictionKind.MULLIGAN -> {
                val explicitKeepAllReplace = parsed.actions.isEmpty() &&
                    ((root["result"] as? JsonObject)?.get("rec") as? JsonArray)?.firstOrNull()
                        ?.let { (it as? JsonObject)?.get("chosen") is JsonArray } == true
                mapMulligan(parsed.actions, prepared, cardNameById, explicitKeepAllReplace)
            }
            PowerLogPredictionKind.CHOICE -> mapChoice(parsed.actions, prepared, cardNameById)
            PowerLogPredictionKind.ACTION -> mapActionList(parsed.actions, prepared, cardNameById)
            PowerLogPredictionKind.OPPONENT -> emptyList()
        }

        if (mappedActions == null) {
            if (prepared.kind == PowerLogPredictionKind.ACTION && parsed.winRatePercent != null) {
                return MappedResponse(winRatePercent = parsed.winRatePercent, recommendations = emptyList())
            }
            return MappedResponse(errorCode = "prediction_response_missing", retryable = true)
        }

        return MappedResponse(
            winRatePercent = parsed.winRatePercent,
            recommendations = mappedActions,
        )
    }

    private fun parseWinRateOnly(decryptedJson: String): Double? {
        val root = Json.parseToJsonElement(decryptedJson) as? JsonObject ?: return null
        val status = (root["status"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        if (status != true) return null
        val result = root["result"] as? JsonObject ?: return null
        if (result["status"]?.jsonPrimitive?.intOrNull != 200) return null
        val winrateData = result["winrate_data"] as? JsonObject ?: return null
        if (winrateData["status"]?.jsonPrimitive?.intOrNull != 200) return null
        val rawRate = winrateData["winrate"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() } ?: return null
        return rawRate.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100.0)
    }

    private fun mapMulligan(
        actions: List<PredictAction>,
        prepared: PreparedState,
        cardNameById: (String) -> String?,
        explicitEmptyKeep: Boolean = false,
    ): List<PredictedAction>? {
        val first = actions.firstOrNull()
        if (first == null && !explicitEmptyKeep) return null
        val kept = first?.chosen?.toSet() ?: emptySet()
        val openingCards = prepared.choiceOptions.filter { option ->
            val cardId = option.cardId ?: entityCardId(option.entityId, prepared.entityLookup)
            cardId !in KNOWN_COIN_IDS
        }
        if (openingCards.isEmpty()) return null
        val replacements = openingCards.filter { it.entityId !in kept }
        return if (replacements.isEmpty()) {
            listOf(PredictedAction(action = "keep_all"))
        } else {
            replacements.map { card ->
                PredictedAction(
                    action = "replace",
                    card = entityReference(card.entityId, card.cardId, prepared.entityLookup, cardNameById),
                )
            }
        }
    }

    private fun mapChoice(
        actions: List<PredictAction>,
        prepared: PreparedState,
        cardNameById: (String) -> String?,
    ): List<PredictedAction>? {
        val chosenIds = LinkedHashSet<Int>()
        for (act in actions) {
            if (act.chosen.isNotEmpty()) {
                chosenIds.addAll(act.chosen)
            } else {
                val candidate = act.optionEntityId?.takeIf { it > 0 }
                    ?: act.targetEntityId?.takeIf { it > 0 }
                    ?: act.subOptionEntityId?.takeIf { it > 0 }
                    ?: act.optionId?.takeIf { it in prepared.choiceOptions.indices }?.let { prepared.choiceOptions[it].entityId }
                if (candidate != null && candidate > 0) chosenIds += candidate
            }
        }
        val validIds = prepared.choiceOptions.map { it.entityId }.toSet()
        val matched = chosenIds.filter { it in validIds }.take(prepared.choiceMax)
        if (matched.isEmpty()) return null
        return matched.map { id ->
            val fallbackCardId = prepared.choiceOptions.firstOrNull { it.entityId == id }?.cardId
            PredictedAction(
                action = "choose",
                target = entityReference(id, fallbackCardId, prepared.entityLookup, cardNameById),
            )
        }
    }

    private fun mapActionList(
        actions: List<PredictAction>,
        prepared: PreparedState,
        cardNameById: (String) -> String?,
    ): List<PredictedAction>? {
        if (actions.isEmpty()) return null
        val mapped = actions.take(MAX_RECOMMENDED_ACTIONS).mapNotNull { raw ->
            mapSingleAction(raw, prepared, cardNameById)
        }
        return mapped.takeIf { it.isNotEmpty() }
    }

    private fun mapSingleAction(
        raw: PredictAction,
        prepared: PreparedState,
        cardNameById: (String) -> String?,
    ): PredictedAction? {
        if (raw.isEndTurn) return PredictedAction(action = "end_turn")

        val sourceEntityId = raw.optionEntityId?.takeIf { it > 0 }
            ?: raw.optionId?.let { id ->
                prepared.optionsList.firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == id }
                    ?.get("entity_id")?.jsonPrimitive?.intOrNull
            }
            ?: return null

        val sourceEntry = prepared.entityLookup[sourceEntityId]
        val sourceGroup = sourceEntry?.first.orEmpty()
        val sourceCardType = sourceEntry?.second?.get("CARDTYPE")?.jsonPrimitive?.contentOrNull.orEmpty()

        var effectiveTargetId = raw.targetEntityId ?: 0
        if (isTargetlessMinionSelfTarget(raw, sourceEntityId, sourceGroup, sourceCardType, prepared.optionsList)) {
            effectiveTargetId = 0
        }

        val actionName = when {
            sourceGroup == "my_hero_power" -> "hero_skill"
            sourceGroup == "my_hero" -> "hero_attack"
            sourceGroup == "my_lineup" && sourceCardType == "LOCATION" -> "location_power"
            sourceGroup == "my_lineup" -> "minion_attack"
            sourceGroup == "my_hands" && effectiveTargetId == sourceEntityId && effectiveTargetId > 0 -> "trade"
            sourceGroup == "my_hands" -> when (sourceCardType) {
                "MINION" -> "play_minion"
                "WEAPON" -> "play_weapon"
                "LOCATION" -> "play_location"
                "HERO" -> "play_hero"
                else -> "play_special"
            }
            else -> "play_special"
        }

        val cardRef = entityReference(sourceEntityId, null, prepared.entityLookup, cardNameById)

        val actualTargetId = when {
            actionName == "trade" -> 0
            effectiveTargetId > 0 && effectiveTargetId != sourceEntityId -> effectiveTargetId
            effectiveTargetId == 0 && (raw.magneticTargetEntityId ?: 0) > 0 -> raw.magneticTargetEntityId!!
            else -> 0
        }

        var target: JsonObject? = null
        var opponentTarget: JsonObject? = null
        var targetHero: JsonObject? = null
        var opponentTargetHero: JsonObject? = null
        if (actualTargetId > 0) {
            val targetRef = entityReference(actualTargetId, null, prepared.entityLookup, cardNameById)
            val targetGroup = prepared.entityLookup[actualTargetId]?.first.orEmpty()
            when {
                targetGroup == "my_hero" -> targetHero = targetRef
                targetGroup == "opp_hero" -> opponentTargetHero = targetRef
                targetGroup.startsWith("opp_") -> opponentTarget = targetRef
                else -> target = targetRef
            }
        }

        val subOption = raw.subOptionEntityId?.takeIf { it > 0 }?.let { id ->
            entityReference(id, null, prepared.entityLookup, cardNameById)
        }
        val position = if (actionName == "location_power") 0 else raw.position?.takeIf { it >= 0 }

        return PredictedAction(
            action = actionName,
            card = cardRef,
            target = target,
            opponentTarget = opponentTarget,
            targetHero = targetHero,
            opponentTargetHero = opponentTargetHero,
            position = position,
            subOption = subOption,
        )
    }

    private fun isTargetlessMinionSelfTarget(
        raw: PredictAction,
        sourceEntityId: Int,
        sourceGroup: String,
        sourceCardType: String,
        options: List<JsonObject>,
    ): Boolean {
        val optionId = raw.optionId ?: return false
        if (optionId <= 0 || sourceGroup != "my_hands" || sourceCardType != "MINION") return false
        if (raw.targetEntityId != sourceEntityId) return false
        if ((raw.subOptionId ?: -1) != -1 || (raw.subOptionEntityId ?: 0) != 0 || (raw.magneticTargetEntityId ?: 0) != 0) {
            return false
        }
        val opt = options.firstOrNull { it["id"]?.jsonPrimitive?.intOrNull == optionId } ?: return false
        if (opt["type"]?.jsonPrimitive?.contentOrNull != "POWER" ||
            opt["error"]?.jsonPrimitive?.contentOrNull != "NONE" ||
            opt["entity_id"]?.jsonPrimitive?.intOrNull != sourceEntityId
        ) {
            return false
        }
        val targets = opt["targets"] as? JsonArray
        return targets == null || targets.isEmpty()
    }

    private fun entityCardId(entityId: Int, lookup: Map<Int, Pair<String, JsonObject>>): String? =
        lookup[entityId]?.second?.get("_CARD_ID")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun entityReference(
        entityId: Int,
        fallbackCardId: String?,
        lookup: Map<Int, Pair<String, JsonObject>>,
        cardNameById: (String) -> String?,
    ): JsonObject = buildJsonObject {
        put("ENTITY_ID", entityId)
        val entity = lookup[entityId]?.second
        val cardId = entity?.get("_CARD_ID")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: fallbackCardId?.takeIf { it.isNotEmpty() }
        if (cardId != null) {
            put("_CARD_ID", cardId)
            put("cardId", cardId)
            cardNameById(cardId)?.takeIf { it.length in 1..96 && DISPLAY_NAME_REGEX.matches(it) }?.let {
                put("name", it)
            }
        }
        entity?.get("ZONE_POSITION")?.jsonPrimitive?.intOrNull?.let { put("ZONE_POSITION", it) }
    }

    private fun isAuthenticationFailureJson(root: JsonObject, rawJson: String): Boolean {
        val ret = root["ret"]?.jsonPrimitive?.intOrNull
        val resultStatus = (root["result"] as? JsonObject)?.get("status")?.jsonPrimitive?.intOrNull
        if (ret == 401 || ret == 403 || resultStatus == 401 || resultStatus == 403) return true
        val msg = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return isAuthenticationFailureText(msg) || (root["status"]?.jsonPrimitive?.booleanOrNull == false && isAuthenticationFailureText(rawJson))
    }

    private fun isAuthenticationFailureText(text: String): Boolean {
        if (text.isBlank()) return false
        return AUTH_FAILURE_KEYWORDS.any { keyword -> text.contains(keyword, ignoreCase = true) }
    }

    private fun isPredictorNotReady(root: JsonObject, kind: PowerLogPredictionKind): Boolean {
        val status = (root["status"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val msg = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (msg.contains("TypeError", ignoreCase = true) && msg.contains("None", ignoreCase = true)) {
            return true
        }
        if (status != true) return true
        val result = root["result"] as? JsonObject ?: return true
        if (result["status"]?.jsonPrimitive?.intOrNull != 200) return true
        return kind != PowerLogPredictionKind.OPPONENT && result["rec"] !is JsonArray
    }

    companion object {
        private const val DEFAULT_BUDGET_MILLIS = 12_000L
        private const val DEFAULT_RETRY_DELAY_MILLIS = 300L
        private const val MIN_ATTEMPT_BUDGET_MILLIS = 1_500L
        private const val CALL_TIMEOUT_MILLIS = 8_000L
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        private const val MAX_LOG_LINE_CHARS = 16_384
        private const val MAX_RECOMMENDED_ACTIONS = 24

        private val KNOWN_COIN_IDS = setOf("GAME_005", "BE_COIN3", "TSC_COIN2", "TTN_COIN2")
        private val DISPLAY_NAME_REGEX = Regex("""^[^\p{Cc}\p{Cf}\p{Zl}\p{Zp}]+$""")

        private val AUTH_FAILURE_KEYWORDS = listOf(
            "unauthorized", "forbidden", "cookie", "\"sid\"", "sid=", "sid:",
            "sid expired", "invalid sid", "sid invalid", "sid verify", "sid 过期",
            "token", "login", "auth", "未登录", "登录失效", "登录过期", "鉴权", "认证失败", "认证过期",
        )

        /** Builds the NetEase `cardgroup` string (`mainId:count,...[|ownerId+sideId:count,...]`). */
        fun buildCardGroup(deck: DeckDefinition, cardIdByDbfId: (Int) -> String?): String? {
            if (deck.cards.isEmpty() || deck.cards.size > 40) return null
            val aggregatedMain = LinkedHashMap<String, Int>()
            var mainTotal = 0
            for ((dbfId, count) in deck.cards) {
                if (count !in 1..40) return null
                val cardId = cardIdByDbfId(dbfId)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                aggregatedMain[cardId] = (aggregatedMain[cardId] ?: 0) + count
                mainTotal += count
            }
            if (mainTotal !in VALID_DECK_TOTALS) return null

            val mainPart = aggregatedMain.entries
                .sortedWith(compareBy { it.key })
                .joinToString(",") { "${it.key}:${it.value}" }

            if (deck.sideboards.isEmpty()) return mainPart
            if (deck.sideboards.size > 24) return null

            data class SideEntry(val ownerId: String, val cardId: String, val count: Int)
            val aggregatedSide = LinkedHashMap<Pair<String, String>, Int>()
            for (side in deck.sideboards) {
                if (side.count !in 1..20) return null
                val ownerId = cardIdByDbfId(side.ownerDbfId)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val cardId = cardIdByDbfId(side.dbfId)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (ownerId !in aggregatedMain) return null
                val key = ownerId to cardId
                aggregatedSide[key] = (aggregatedSide[key] ?: 0) + side.count
            }

            val sidePart = aggregatedSide.entries
                .map { SideEntry(it.key.first, it.key.second, it.value) }
                .sortedWith(compareBy<SideEntry> { it.ownerId }.thenBy { it.cardId })
                .joinToString(",") { "${it.ownerId}+${it.cardId}:${it.count}" }

            return "$mainPart|$sidePart"
        }

        private val VALID_DECK_TOTALS = setOf(20, 30, 40)
    }
}
