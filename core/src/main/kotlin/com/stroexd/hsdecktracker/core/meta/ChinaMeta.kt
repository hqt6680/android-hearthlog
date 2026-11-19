package com.stroexd.hsdecktracker.core.meta

import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class ChinaMetaKind(val file: String, val variable: String) {
    DECKS("card_group_all_v2.js", "CARD_GROUP_STATISTICS"),
    CLASSES("hero_statistics_v2.js", "HERO_STATISTICS"),
}

data class ChinaMetaStats(
    val games: Long,
    val wins: Long,
    val total: Long,
    val averageSeconds: Double?,
) {
    val winRate: Double? get() = if (games > 0) wins.toDouble() / games else null
    val pickRate: Double? get() = if (total > 0) games.toDouble() / total else null
    val netWinsPerHour: Double?
        get() = averageSeconds?.let { seconds -> winRate?.let { (2 * it - 1) * 3600 / seconds } }
}

data class ChinaMetaRow(
    val format: String,
    val rank: String,
    val classId: String?,
    val title: String,
    val code: String?,
    val periods: Map<Int, ChinaMetaStats>,
)

data class ChinaMetaSnapshot(val rows: List<ChinaMetaRow>, val modifiedAt: Instant?)

object ChinaMeta {
    private const val ROOT = "https://hs2.166.net/hs-public-data/hs_data/ld2_card_group_statistics"
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private val periods = listOf(1, 3, 7, 30)
    private val formats = setOf("FT_STANDARD", "FT_WILD")
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun fetch(kind: ChinaMetaKind): ChinaMetaSnapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$ROOT/${kind.file}").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("缺少响应内容")
            if (source.request(MAX_BYTES + 1)) throw IOException("数据超过大小限制")
            val modifiedAt = response.header("Last-Modified")?.let(::parseHttpDate)
            ChinaMetaSnapshot(parse(kind, source.readUtf8()), modifiedAt)
        }
    }

    fun parse(kind: ChinaMetaKind, payload: String): List<ChinaMetaRow> {
        val assignment = Regex("""\A\s*var\s+${kind.variable}\s*=\s*""")
        val prefix = assignment.find(payload) ?: throw IllegalArgumentException("数据格式已变更")
        val json = payload.substring(prefix.range.last + 1).trim().removeSuffix(";").trimEnd()
        val rows = Json.parseToJsonElement(json) as? JsonArray
            ?: throw IllegalArgumentException("数据应为 JSON 数组")
        return rows.mapNotNull { element -> (element as? JsonObject)?.let { parseRow(kind, it) } }
            .also { require(it.isNotEmpty()) { "未找到有效的构筑数据" } }
    }

    private fun parseRow(kind: ChinaMetaKind, data: JsonObject): ChinaMetaRow? {
        val format = data.string("format_type")?.takeIf(formats::contains) ?: return null
        val rank = data.string("level_type") ?: return null
        val titleField = if (kind == ChinaMetaKind.DECKS) "title" else "hero"
        val title = data.string(titleField)?.takeIf(String::isNotBlank) ?: return null
        val classId = if (kind == ChinaMetaKind.DECKS) data.string("job") else title
        val code = data.string("cipher")?.takeIf(String::isNotBlank)
        val stats = periods.mapNotNull { days -> parsePeriod(data, days)?.let { days to it } }.toMap()
        return ChinaMetaRow(format, rank, classId, title, code, stats).takeIf { stats.isNotEmpty() }
    }

    private fun parsePeriod(data: JsonObject, days: Int): ChinaMetaStats? {
        val suffix = "_${days}d"
        val games = data.number("use_cnt$suffix")?.longOrNull ?: return null
        val wins = data.number("win_cnt$suffix")?.longOrNull ?: return null
        val total = data.number("total_cnt$suffix")?.longOrNull ?: return null
        if (wins !in 0..games || games > total) return null
        val seconds = data.number("battle_avg_time$suffix")?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
        return ChinaMetaStats(games, wins, total, seconds)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): JsonPrimitive? = (get(key) as? JsonPrimitive)
        ?.takeUnless { it.isString }

    private fun parseHttpDate(value: String): Instant? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}
