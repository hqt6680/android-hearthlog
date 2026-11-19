package com.stroexd.hsdecktracker.cloud

import com.stroexd.hsdecktracker.core.prediction.NeteaseDirectCodec
import com.stroexd.hsdecktracker.core.prediction.NeteaseGuestProfile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class NeteaseGuestSession {
    private class Session(val profile: NeteaseGuestProfile, val expiresAtMillis: Long)

    private val session = AtomicReference<Session>()
    private val bootstrapLock = Mutex()

    suspend fun profile(): NeteaseGuestProfile = bootstrapLock.withLock {
        session.get()?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }?.let { return@withLock it.profile }
        val nonce = (random.nextInt(989_999) + 10_000).toString().encodeToByteArray()
        val key = MessageDigest.getInstance("MD5").digest(nonce)
        val encryption = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        encryption.init(Cipher.ENCRYPT_MODE, publicKey)
        val query = Base64.getEncoder().encodeToString(encryption.doFinal(nonce))
        val next = bootstrap(query, key)
        currentCoroutineContext().ensureActive()
        session.set(next)
        next.profile
    }

    fun invalidate(profile: NeteaseGuestProfile) {
        val current = session.get() ?: return
        if (current.profile == profile) session.compareAndSet(current, null)
    }

    private suspend fun bootstrap(query: String, key: ByteArray): Session = suspendCancellableCoroutine { continuation ->
        val url = bootstrapUrl.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("source", "android")
            .addQueryParameter("game", "hs")
            .addQueryParameter("machine", "test")
            .addQueryParameter("sys", "")
            .addQueryParameter("version", "")
            .addQueryParameter("adid", "")
            .build()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", BOOTSTRAP_USER_AGENT).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(IOException("NetEase guest session request failed")))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use { readSession(it, key) } })
            }
        })
    }

    private fun readSession(response: Response, key: ByteArray): Session {
        if (!response.isSuccessful) throw IOException("NetEase guest session HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty NetEase guest session response")
        val encrypted = body.byteStream().readGuestConfig().toString(Charsets.US_ASCII).trim()
        val config = try {
            val json = if (encrypted.startsWith('{')) encrypted else
                NeteaseDirectCodec.rc4(NeteaseDirectCodec.decodeBase64Url(encrypted), key).decodeToString()
            Json.parseToJsonElement(json).jsonObject
        } catch (_: IllegalArgumentException) {
            throw IOException("Invalid NetEase guest session response")
        }
        val sid = runCatching { config["session"]?.jsonPrimitive?.content }.getOrNull()
        val advertisedEndpoint = runCatching {
            config["mobile"]?.jsonObject?.get("regular_action_recommend_url")?.jsonPrimitive?.content
        }.getOrNull()
        val cookie = Cookie.parseAll(bootstrapUrl, response.headers)
            .singleOrNull { it.name == "ls_session_id" && it.value.isNotEmpty() && it.matches(predictionUrl) }
        if (sid == null || !sessionId.matches(sid) || advertisedEndpoint != predictionUrl.toString() || cookie == null) {
            throw IOException("Incomplete NetEase guest session")
        }
        val profile = NeteaseGuestProfile(
            endpoint = predictionUrl.toString(),
            sid = sid,
            cookie = "${cookie.name}=${cookie.value}",
            userAgent = "HSAng/4.0.4.486",
            clientType = "win",
            version = "4.0.4.486",
            rc4Key = key,
        )
        return Session(profile, cookie.expiresAt)
    }

    private fun InputStream.readGuestConfig(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() > MAX_CONFIG_BYTES - count) throw IOException("NetEase guest configuration too large")
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val BOOTSTRAP_USER_AGENT = "Mozilla/5.0 HSAngAndroid/4.0.5.340/"
        const val MAX_CONFIG_BYTES = 256 * 1024
        const val BOX_PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDNmtTSQuaXSVvmkzTk94P+mrgh" +
            "qivjnOxKN5rLPggFOhBOgUEECNKFiutp7XLJZI8h0xiIKq/AghhOHxSLI4I8ak7g" +
            "BBVAbOHHRZ3lAXucXqFs4Q/nThN9PipoX8zZWTt8+kkNR0Y1Bq224WU5S6v8/5HN" +
            "VG49RO84cIWqe1nOFQIDAQAB"
        val bootstrapUrl = "https://hs-api.lushi.163.com/hs/i/initbox".toHttpUrl()
        val predictionUrl = "https://hs-api.lushi.163.com/hs/predict/standard_substep".toHttpUrl()
        val sessionId = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        val random = SecureRandom()
        val publicKey by lazy {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(BOX_PUBLIC_KEY)))
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
