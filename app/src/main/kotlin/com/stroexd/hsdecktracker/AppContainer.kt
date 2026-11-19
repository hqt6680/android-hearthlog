package com.stroexd.hsdecktracker

import android.content.Context
import android.content.res.Resources
import com.stroexd.hsdecktracker.cloud.CloudPrediction
import com.stroexd.hsdecktracker.cloud.NeteaseGuestSession
import com.stroexd.hsdecktracker.cloud.OnDevicePrediction
import com.stroexd.hsdecktracker.core.data.CardRepository
import com.stroexd.hsdecktracker.core.data.CollectionRepository
import com.stroexd.hsdecktracker.core.data.DeckRepository
import com.stroexd.hsdecktracker.core.data.HttpClient
import com.stroexd.hsdecktracker.core.data.MetaRepository
import com.stroexd.hsdecktracker.core.data.SettingsRepository
import com.stroexd.hsdecktracker.core.prediction.NeteaseOpeningStats
import com.stroexd.hsdecktracker.powerlog.LogCardNames
import com.stroexd.hsdecktracker.powerlog.OnDeviceOpeningRates
import com.stroexd.hsdecktracker.powerlog.PowerLogSession
import com.stroexd.hsdecktracker.powerlog.ShizukuLogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataDir = File(context.filesDir, "data")
    private val cacheDir = File(context.filesDir, "cache")

    val okHttpClient: OkHttpClient = HttpClient.defaultClient()
    val http = HttpClient(okHttpClient)

    val settings = SettingsRepository(dataDir)
    val decks = DeckRepository(dataDir)
    val collection = CollectionRepository(dataDir)
    val cards = CardRepository(cacheDir, http)
    val meta = MetaRepository(cacheDir, http)
    val powerLogSource = ShizukuLogSource(context.applicationContext)
    internal val logCardNames = LogCardNames(context.assets)
    private val onDevicePrediction = OnDevicePrediction(
        session = NeteaseGuestSession(),
        httpClient = okHttpClient,
        cardIdByDbfId = { dbfId -> cards.db.byDbfId(dbfId)?.id ?: logCardNames.byDbfId(dbfId)?.cardId },
        cardNameById = { cardId -> logCardNames.resolve(cards.db, cardId) },
    )
    internal val cloudPrediction = CloudPrediction(appScope, onDevicePrediction)
    internal val powerLog = PowerLogSession(
        cards = { cards.db },
        cloud = cloudPrediction,
        savedDecks = { decks.decks.value },
        synchronizeGameDecks = { gameDecks ->
            appScope.launch(start = CoroutineStart.UNDISPATCHED) { decks.syncGameDecks(gameDecks) }
        },
    )
    internal val onDeviceOpeningRates = OnDeviceOpeningRates(appScope, powerLog.state, NeteaseOpeningStats(okHttpClient))

    val gameLocale: StateFlow<String> = settings.settings
        .map { it.gameLocale(deviceLanguage()) }
        .stateIn(appScope, SharingStarted.Eagerly, settings.value.gameLocale(deviceLanguage()))

    val appLocale: StateFlow<Locale> = gameLocale
        .map(::toLocale)
        .stateIn(appScope, SharingStarted.Eagerly, toLocale(gameLocale.value))

    init {
        appScope.launch(Dispatchers.IO) { logCardNames.preload() }
        appScope.launch(Dispatchers.IO) {
            try { onDevicePrediction.prepare() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry when the first decision is available. */ }
        }
        appScope.launch { gameLocale.collect { cards.load(it) } }
        appScope.launch { appLocale.collect { Locale.setDefault(it) } }
    }

    fun checkForUpdates() {
        appScope.launch { cards.checkForUpdate() }
    }

    fun refreshCards() {
        appScope.launch { cards.load(gameLocale.value, forceRefresh = true) }
    }

    private companion object {
        fun deviceLanguage(): String = Resources.getSystem().configuration.locales[0].language

        fun toLocale(gameLocale: String): Locale =
            Locale.forLanguageTag(gameLocale.take(2) + "-" + gameLocale.drop(2))
    }
}
