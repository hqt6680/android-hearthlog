package com.stroexd.hsdecktracker.core.prediction

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class OpeningOffer(val entityId: Int, val cardId: String)

/** Reads the public NetEase Box aggregate statistics for the offered opening cards. */
class NeteaseOpeningStats(client: OkHttpClient) {
    private val http = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun keepRates(deckTitle: String, wild: Boolean, offers: List<OpeningOffer>): Map<Int, Double> {
        val title = deckTitle.trim()
        if (title.length !in 1..128 || offers.size !in 3..4 ||
            offers.any { it.entityId <= 0 || !CARD_ID.matches(it.cardId) } ||
            offers.map { it.entityId }.distinct().size != offers.size
        ) return emptyMap()
        val url = ENDPOINT.newBuilder()
            .addQueryParameter("title", title)
            .addQueryParameter("is_wild", if (wild) "1" else "0")
            .addQueryParameter("card_list", offers.joinToString(",") { it.cardId })
            .build()
        val request = Request.Builder().url(url).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(emptyMap())
                }

                override fun onResponse(call: Call, response: Response) {
                    val rates = try {
                        response.use {
                            val body = it.body
                            if (!it.isSuccessful || body == null || body.source().request(MAX_RESPONSE_BYTES + 1)) {
                                emptyMap()
                            } else {
                                projectNeteaseKeepRates(body.string(), offers)
                            }
                        }
                    } catch (_: IOException) {
                        emptyMap()
                    }
                    continuation.resume(rates)
                }
            })
        }
    }

    private companion object {
        val ENDPOINT = "https://hs-api.lushi.163.com/hs/c/cg_liupai_on_start_info".toHttpUrl()
        val CARD_ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val MAX_RESPONSE_BYTES = 32L * 1024
    }
}

internal fun projectNeteaseKeepRates(body: String, offers: List<OpeningOffer>): Map<Int, Double> {
    val payload = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return emptyMap()
    val status = payload["status"] as? JsonPrimitive
    if (status == null || status.isString || status.booleanOrNull != true) return emptyMap()
    val result = payload["result"] as? JsonObject ?: return emptyMap()
    return buildMap {
        for (offer in offers) {
            val entry = result[offer.cardId] as? JsonObject ?: continue
            val used = entry.count("use_cnt") ?: continue
            val total = entry.count("total_cnt") ?: continue
            if (total > 0 && used in 0..total) put(offer.entityId, used * 100.0 / total)
        }
    }
}

private fun JsonObject.count(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
