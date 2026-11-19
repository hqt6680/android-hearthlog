package com.stroexd.hsdecktracker.core.prediction

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class PredictAction(
    val optionId: Int?,
    val optionEntityId: Int?,
    val targetEntityId: Int?,
    val subOptionId: Int?,
    val subOptionEntityId: Int?,
    val position: Int?,
    val chosen: List<Int>,
    val magneticTargetEntityId: Int?,
    val usingDisguised: Int?,
) {
    val isEndTurn: Boolean
        get() = (optionId == 0 || optionEntityId == 0) &&
            (optionId ?: 0) == 0 && (optionEntityId ?: 0) == 0 && chosen.isEmpty()
}

data class PredictResult(
    val winRatePercent: Double?,
    val actions: List<PredictAction>,
    val missingFields: List<String>,
) {
    val isComplete: Boolean
        get() = missingFields.isEmpty()
}

/** Reads already-decoded NetEase prediction JSON responses. */
object PredictResultParser {
    fun parse(json: String): PredictResult {
        val envelope = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw IllegalArgumentException("Invalid prediction JSON")

        val status = envelope.member("status") as? JsonPrimitive
        require(status != null && !status.isString && status.booleanOrNull == true) {
            "Prediction response did not report success"
        }
        val rawRet = envelope.member("ret")
        val ret = if (rawRet == null) 0 else requireNotNull(rawRet.int()) { "Invalid prediction response code" }
        val result = envelope.member("result") as? JsonObject
        require(result != null && result.member("status").int() == 200) {
            "Prediction result did not report success"
        }

        val missing = mutableListOf<String>()
        val winRate = if (ret == 0) readWinRate(result.member("winrate_data") as? JsonObject) else null
        if (winRate == null) missing += "result.winrate_data.winrate"

        val rawActions = result.member("rec") as? JsonArray
        val actions = rawActions.orEmpty().mapIndexedNotNull { index, element ->
            val action = (element as? JsonObject)?.let(::readAction)
            if (action == null) missing += "result.rec[$index]"
            action
        }
        if (rawActions.isNullOrEmpty()) missing += "result.rec"

        return PredictResult(winRate, actions, missing)
    }

    private fun readWinRate(data: JsonObject?): Double? {
        if (data == null || data["status"].number()?.intOrNull != 200) return null
        val rate = data["winrate"].number()?.doubleOrNull ?: return null
        return rate.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100)
    }

    private fun readAction(obj: JsonObject): PredictAction? {
        val optionId = obj.member("option_id").int()
        val optionEntityId = obj.member("option_entity_id").int()
        val rawChosen = obj.member("chosen")
        val chosen = when (rawChosen) {
            null, JsonNull -> emptyList()
            is JsonArray -> rawChosen.map { it.int() ?: return null }
            else -> return null
        }
        if (optionId == null && optionEntityId == null && chosen.isEmpty()) return null
        if (actionIntegerFields.any { name ->
            val value = obj.member(name)
            value != null && value.int() == null
        }) return null

        return PredictAction(
            optionId = optionId,
            optionEntityId = optionEntityId,
            targetEntityId = obj.member("target_entity_id").int(),
            subOptionId = obj.member("sub_option_id").int(),
            subOptionEntityId = obj.member("sub_option_entity_id").int(),
            position = obj.member("position").int(),
            chosen = chosen,
            magneticTargetEntityId = obj.member("magnetic_target_entity_id").int(),
            usingDisguised = obj.member("using_disguised").int(),
        )
    }

    private val actionIntegerFields = listOf(
        "option_id", "option_entity_id", "target_entity_id", "sub_option_id", "sub_option_entity_id",
        "position", "magnetic_target_entity_id", "using_disguised",
    )

    private fun JsonObject.member(name: String): JsonElement? =
        this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }
}
