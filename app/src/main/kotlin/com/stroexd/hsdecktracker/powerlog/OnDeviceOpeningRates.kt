package com.stroexd.hsdecktracker.powerlog

import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.prediction.NeteaseOpeningStats
import com.stroexd.hsdecktracker.core.prediction.OpeningOffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class OnDeviceOpeningRateState(
    val revision: Long,
    val choiceId: Int,
    val deckTitle: String,
    val loading: Boolean,
    val rates: Map<Int, Double> = emptyMap(),
)

internal class OnDeviceOpeningRates(
    scope: CoroutineScope,
    sessions: StateFlow<PowerLogSessionState>,
    stats: NeteaseOpeningStats,
) {
    private val mutable = MutableStateFlow<OnDeviceOpeningRateState?>(null)
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            sessions.map(::query).distinctUntilChanged().collectLatest { input ->
                val opening = input?.state
                mutable.value = opening
                if (input != null && opening != null) {
                    val rates = stats.keepRates(opening.deckTitle, input.wild, input.offers)
                    mutable.value = opening.copy(loading = false, rates = rates)
                }
            }
        }
    }

    private fun query(session: PowerLogSessionState): Query? {
        val match = session.snapshot ?: return null
        val opening = match.openingChoice ?: return null
        val title = session.selectedDeck?.name?.trim()?.takeIf { it.length in 1..128 } ?: return null
        if (!session.active || match.result != null || match.format !in setOf(GameFormat.STANDARD, GameFormat.WILD)) return null
        val offers = opening.cards.map { card -> OpeningOffer(card.entityId ?: return null, card.cardId) }
        return Query(OnDeviceOpeningRateState(session.revision, opening.choiceId, title, loading = true),
            match.format == GameFormat.WILD, offers)
    }

    private data class Query(val state: OnDeviceOpeningRateState, val wild: Boolean, val offers: List<OpeningOffer>)
}
