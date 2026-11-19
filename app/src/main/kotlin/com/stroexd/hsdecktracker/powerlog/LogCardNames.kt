package com.stroexd.hsdecktracker.powerlog

import android.content.res.AssetManager
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream

internal data class LogCardMetadata(val cardId: String, val name: String, val dbfId: Int?, val cost: Int?)

internal class LogCardNames(private val assets: AssetManager) {
    // The .dat suffix preserves the compressed index when AAPT packages assets.
    private val catalog by lazy {
        try {
            assets.open("card-names-zhCN.dat").use { input ->
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    val entries = lines.mapNotNull { line ->
                        val fields = line.split('\t', limit = 4)
                        val id = fields.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val name = fields.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        LogCardMetadata(id, name, fields.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 },
                            fields.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 })
                    }.toList()
                    val byDbfId = buildMap {
                        for (entry in entries) entry.dbfId?.let { put(it, entry) }
                    }
                    Index(entries.associateBy { it.cardId }, byDbfId)
                }
            }
        } catch (_: IOException) {
            Index(emptyMap(), emptyMap())
        }
    }

    private data class Index(val byCardId: Map<String, LogCardMetadata>, val byDbfId: Map<Int, LogCardMetadata>)

    fun preload() {
        catalog.byCardId.size
    }

    fun dbfId(cardId: String): Int? = catalog.byCardId[cardId]?.dbfId

    fun byDbfId(dbfId: Int): LogCardMetadata? = catalog.byDbfId[dbfId]

    fun resolve(database: CardDatabase, cardId: String?, suppliedName: String? = null): String? {
        val id = cardId?.trim().orEmpty()
        database.byCardId(id)?.name.displayName(id)?.let { return it }
        val localChinese = database.locale == "zhCN" || (database.isEmpty && Locale.getDefault().language == "zh")
        if (localChinese) catalog.byCardId[id]?.name.displayName(id)?.let { return it }
        return suppliedName.displayName(id)
    }

    private fun String?.displayName(cardId: String): String? {
        val name = this?.trim() ?: return null
        return name.takeIf {
            it.isNotEmpty() && !it.equals(cardId, ignoreCase = true) &&
                !it.startsWith("UNKNOWN ENTITY", ignoreCase = true) &&
                it.any(Char::isLetter) && it.none { char -> char == '\uFFFD' || char.isISOControl() } &&
                !rawIdentifier.matches(it)
        }
    }

    private companion object {
        val rawIdentifier = Regex("[A-Za-z0-9]+(?:_[A-Za-z0-9]+)+")
    }
}
