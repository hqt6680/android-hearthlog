package com.stroexd.hsdecktracker.ui.tracker

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.cloud.PredictionKey
import com.stroexd.hsdecktracker.cloud.PredictionStage
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.powerlog.PowerLogCard
import com.stroexd.hsdecktracker.core.powerlog.PowerLogHandMark
import com.stroexd.hsdecktracker.core.powerlog.PowerLogHandOrigin
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSide
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import com.stroexd.hsdecktracker.core.powerlog.activeOpponentSecretCandidates
import com.stroexd.hsdecktracker.core.powerlog.estimatePowerLogDeck
import com.stroexd.hsdecktracker.core.prediction.PowerLogPredictionKind
import com.stroexd.hsdecktracker.powerlog.LogCardNames
import com.stroexd.hsdecktracker.powerlog.PowerLogSessionState
import com.stroexd.hsdecktracker.powerlog.localHeroClass
import com.stroexd.hsdecktracker.powerlog.matchesPowerLog
import com.stroexd.hsdecktracker.ui.label
import java.text.NumberFormat

@Composable
internal fun PowerLogAdvicePanel(match: PowerLogSnapshot, db: CardDatabase, session: PowerLogSessionState) {
    val container = LocalContext.current.appContainer
    val cloud by container.cloudPrediction.liveState.collectAsStateWithLifecycle()
    val direct = container.onDeviceOpeningRates
    val directRates = direct?.state?.collectAsStateWithLifecycle()?.value
    val names = container.logCardNames
    val cloudDecision = cloud.takeIf { it.key != null && it.key == session.cloudDecision }
    var tab by remember(session.revision) { mutableIntStateOf(0) }
    val opening = match.openingChoice
    val activeDirectRates = directRates?.takeIf {
        it.revision == session.revision && it.choiceId == opening?.choiceId &&
            it.deckTitle == session.selectedDeck?.name?.trim()
    }
    val choice = match.generalChoice
    LaunchedEffect(opening?.choiceId, choice?.choiceId) {
        if (opening != null || choice != null) tab = 0
    }
    Column {
        CloudPredictionWinRate(cloudDecision, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        val adviceTab = when {
            opening != null -> R.string.log_opening
            choice != null -> R.string.cloud_choice_tab
            else -> R.string.log_suggestions
        }
        LogTabs(tab, listOf(adviceTab, R.string.power_log_graveyard)) { tab = it }
        LogRows(session.revision, tab, decision = session.cloudDecision.takeIf { tab == 0 }) {
            when (tab) {
                0 -> {
                    if (opening != null) {
                        val keepRates = activeDirectRates?.rates.orEmpty()
                        val recommendations = cloudDecision?.result?.takeIf {
                            cloudDecision.stage == PredictionStage.READY &&
                                it.key.kind == PowerLogPredictionKind.MULLIGAN && it.key.optionsId == opening.choiceId
                        }?.recommendations.orEmpty()
                        val marks = mulliganMarks(opening.cards, recommendations)
                        items(opening.cards, key = { it.position }) { card ->
                            val name = names.resolve(db, card.cardId) ?: stringResource(R.string.log_unknown_card)
                            val percent = keepRates[card.entityId]?.let { rate ->
                                NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(rate / 100)
                            }.orEmpty()
                            val tint = when (marks[card.position]) {
                                true -> Color(0xFFC62828).copy(alpha = 0.4f)
                                false -> Color(0xFF2E7D32).copy(alpha = 0.4f)
                                null -> Color.Unspecified
                            }
                            LogCardRow(name, percent, cardId = card.cardId, tint = tint)
                        }
                    } else {
                        item {
                            if (cloudDecision != null) CloudPredictionResult(cloudDecision, db, match = match, showWinRate = false)
                            else if (match.result == null) LogNote(stringResource(
                                if (container.cloudPrediction.canPredict)
                                    R.string.cloud_waiting_decision else R.string.device_prediction_unavailable,
                            ))
                        }
                        if (choice != null) {
                            items(choice.options, key = { it.entityId }) { card ->
                                LogCardRow(names.resolve(db, card.cardId)
                                    ?: stringResource(R.string.log_unknown_card), "", cardId = card.cardId)
                            }
                        }
                    }
                }
                1 -> {
                    item { LogHeading(stringResource(if (match.localPlayerIdentified) R.string.power_log_us else R.string.power_log_player_a)) }
                    logCards(match.friendly.graveyard, db)
                    item { LogHeading(stringResource(if (match.localPlayerIdentified) R.string.power_log_opponent else R.string.power_log_player_b)) }
                    logCards(match.opponent.graveyard, db)
                }
            }
        }
    }
}


@Composable
internal fun PowerLogDeckPanel(
    match: PowerLogSnapshot,
    db: CardDatabase,
    session: PowerLogSessionState,
    decks: List<Deck>,
    onSelectDeck: (Deck?) -> Unit,
) {
    var tab by remember(session.revision) { mutableIntStateOf(0) }
    var choosingDeck by remember(session.revision) { mutableStateOf(false) }
    val container = LocalContext.current.appContainer
    val names = container.logCardNames
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val selected = session.selectedDeck
    val available = remember(decks, match.format, match.localHeroClass()) {
        decks.filter { it.matchesPowerLog(match) }
            .sortedWith(compareByDescending<Deck> { it.favorite }.thenByDescending { it.updatedAt })
    }
    val estimate = remember(selected, match.friendly.deckChanges, match.friendly.deckCount, match.localPlayerIdentified, db, names) {
        selected?.takeIf { match.localPlayerIdentified }?.let { estimatePowerLogDeck(it, match.friendly, db, names::dbfId) }
    }
    Column {
        if (match.localPlayerIdentified) {
            Text(
                stringResource(R.string.log_board_pair, match.friendly.boardAttack, match.opponent.boardAttack),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
        LogTabs(tab, listOf(R.string.log_deck, R.string.log_opponent, R.string.log_secret_reveal)) { tab = it }
        val unknownCard = stringResource(R.string.log_unknown_card)
        val candidates = if (tab == 2) remember(
            db, settings.formatRules, match.format, match.result, match.localPlayerIdentified,
            match.opponent.heroClass, match.opponent.secretCount, match.opponent.secretObservations,
        ) {
            activeOpponentSecretCandidates(match, db, settings.formatRules)
        } else emptyList()
        LogRows(session.revision, tab) {
            when (tab) {
                0 -> {
                    if (!match.localPlayerIdentified && match.openingChoice == null) {
                        item { LogNote(stringResource(R.string.power_log_local_unknown)) }
                    } else {
                        if (match.localPlayerIdentified) {
                            item {
                                LogHeading(stringResource(R.string.log_deck_count, match.friendly.deckCount?.toString() ?: "—"))
                            }
                        }
                        if (match.result == null && available.isNotEmpty()) {
                            item {
                                DeckChoice(
                                    selected?.name ?: stringResource(R.string.log_choose_deck),
                                    if (choosingDeck) "▴" else "▾",
                                ) { choosingDeck = !choosingDeck }
                            }
                        }
                        if (choosingDeck && match.result == null && available.isNotEmpty()) {
                            items(available, key = { it.id }) { deck ->
                                DeckChoice(deck.name, deck.cardCount.toString()) {
                                    onSelectDeck(deck)
                                    choosingDeck = false
                                }
                            }
                            if (selected != null) {
                                item {
                                    DeckChoice(stringResource(R.string.log_clear_deck), "") {
                                        onSelectDeck(null)
                                        choosingDeck = false
                                    }
                                }
                            }
                        } else if (selected != null && estimate != null) {
                            item {
                                LogNote(stringResource(
                                    if (estimate.hasUnresolvedChanges) R.string.log_deck_unresolved else R.string.log_deck_estimate,
                                ))
                            }
                            val entries = estimate.cards.map { (id, count) ->
                                val card = db.byDbfId(id)
                                val full = names.byDbfId(id)
                                val cardId = card?.id ?: full?.cardId
                                val name = names.resolve(db, cardId) ?: unknownCard
                                DeckListRow(id, cardId, card?.cost ?: full?.cost, name, count)
                            }.sortedWith(compareBy({ it.cost ?: Int.MAX_VALUE }, { it.name }, { it.dbfId }))
                            items(entries, key = { it.dbfId }) { row ->
                                val label = row.cost?.let { "$it  ${row.name}" } ?: row.name
                                LogCardRow(label, "×${row.count}", cardId = row.cardId)
                            }
                            val uncatalogued = estimate.uncataloguedCards.entries.sortedBy { names.resolve(db, it.key) ?: unknownCard }
                            items(uncatalogued, key = { it.key }) { (cardId, count) ->
                                LogCardRow(names.resolve(db, cardId) ?: unknownCard, "×$count", cardId = cardId)
                            }
                        } else if (available.isEmpty() && match.result == null) {
                            item { LogNote(stringResource(R.string.log_deck_import)) }
                        }
                    }
                }
                1 -> {
                    if (!match.localPlayerIdentified) {
                        item { LogNote(stringResource(R.string.power_log_local_unknown)) }
                    } else {
                        item { LogHeading(stringResource(R.string.log_opponent_hand, match.opponent.handCount)) }
                        items(match.opponent.handMarks, key = { it.entityId }) { mark ->
                            LogOpponentHandRow(mark, db, names)
                        }
                        if (match.opponent.played.isNotEmpty()) {
                            item { LogHeading(stringResource(R.string.power_log_public_plays)) }
                            logCards(match.opponent.played, db)
                        }
                    }
                }
                2 -> {
                    when {
                        !match.localPlayerIdentified -> item { LogNote(stringResource(R.string.power_log_local_unknown)) }
                        match.opponent.secretCount == 0 -> item { LogNote(stringResource(R.string.log_no_active_secrets)) }
                        candidates.isEmpty() -> item { LogNote(stringResource(R.string.log_secret_candidates_unavailable)) }
                        else -> items(candidates, key = { it.id }) { card ->
                            Column(Modifier.padding(bottom = 4.dp)) {
                                LogCardRow(names.resolve(db, card.id, card.name) ?: unknownCard, "", cardId = card.id)
                                if (card.plainText.isNotBlank()) LogNote(card.plainText)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DeckListRow(val dbfId: Int, val cardId: String?, val cost: Int?, val name: String, val count: Int)

@Composable
private fun LogTabs(selected: Int, labels: List<Int>, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        labels.forEachIndexed { index, title ->
            Tab(selected = index == selected, onClick = { onSelect(index) }) {
                Box(Modifier.fillMaxWidth().heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(title),
                        fontSize = if (title == R.string.log_secret_reveal) 9.sp else 10.sp,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogRows(revision: Long, tab: Int, decision: PredictionKey? = null, content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = remember(revision, tab, decision) { LazyListState() },
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun DeckChoice(name: String, tail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(tail, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LogHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LogNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LogCardRow(name: String, value: String, cardId: String? = null, tint: Color = Color.Unspecified) {
    val tile = cardId != null || tint != Color.Unspecified
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val background = if (tint == Color.Unspecified) surface else tint.compositeOver(surface)
    val tileModifier = if (tile) Modifier.clip(MaterialTheme.shapes.extraSmall).background(background) else Modifier
    Box(Modifier.fillMaxWidth().then(tileModifier)) {
        LogCardBackdrop(cardId, Modifier.matchParentSize(), background)
        val padding = if (tile) Modifier.padding(horizontal = 4.dp, vertical = 3.dp) else Modifier
        Row(Modifier.fillMaxWidth().then(padding), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun AttackLines(side: PowerLogSide) {
    LogCardRow(stringResource(R.string.log_on_board), side.boardAttack.toString())
    val low = side.availableAttack
    val high = side.availableAttackUpperBound
    if (low != null) {
        val value = when {
            high == null -> "≥$low"
            high > low -> "$low–$high"
            else -> low.toString()
        }
        LogCardRow(stringResource(R.string.log_ready_attack), value)
    } else if (side.isCurrentTurn != false) {
        LogCardRow(stringResource(R.string.log_ready_attack), "—")
    }
    side.heroEffectiveHealth?.let { LogCardRow(stringResource(R.string.log_effective_health), it.toString()) }
    side.heroAttack?.takeIf { it > 0 }?.let { LogCardRow(stringResource(R.string.log_hero_attack), it.toString()) }
    side.weaponAttack?.let { attack ->
        LogCardRow(stringResource(R.string.log_weapon), "$attack / ${side.weaponDurability ?: "—"}")
    }
}

private fun LazyListScope.logCards(cards: List<PowerLogCard>, db: CardDatabase) {
    items(cards) { card ->
        val name = LocalContext.current.appContainer.logCardNames.resolve(db, card.cardId)
            ?: stringResource(R.string.log_unknown_card)
        LogCardRow(name, "", cardId = card.cardId)
    }
}

@Composable
private fun LogOpponentHandRow(mark: PowerLogHandMark, db: CardDatabase, names: LogCardNames) {
    val position = mark.position?.toString() ?: "—"
    val cardName = if (mark.cardId == "GAME_005" || mark.origin == PowerLogHandOrigin.COIN) {
        stringResource(R.string.cloud_coin_card)
    } else names.resolve(db, mark.cardId)
    if (mark.origin == PowerLogHandOrigin.GENERATED) {
        val card = cardName ?: stringResource(R.string.log_unknown_card)
        val creator = names.resolve(db, mark.creatorCardId)
        val label = if (creator == null) stringResource(R.string.log_hand_generated, card)
        else stringResource(R.string.log_hand_generated_by, creator, card)
        LogCardRow(label, position, cardId = mark.cardId)
    } else if (mark.origin == PowerLogHandOrigin.COIN) {
        LogCardRow(cardName.orEmpty(), position, cardId = mark.cardId ?: "GAME_005")
    } else {
        val origin = stringResource(mark.origin.message())
        val entered = mark.enteredTurn?.takeIf { it > 0 && mark.origin != PowerLogHandOrigin.OPENING }
            ?.let { stringResource(R.string.log_hand_turn, origin, (it + 1) / 2) } ?: origin
        LogCardRow(cardName ?: entered, position, cardId = mark.cardId)
        if (cardName != null) LogNote(entered)
    }
}

@StringRes
private fun PowerLogHandOrigin.message(): Int = when (this) {
    PowerLogHandOrigin.OPENING -> R.string.log_hand_open
    PowerLogHandOrigin.DRAWN -> R.string.log_hand_draw
    PowerLogHandOrigin.RETURNED -> R.string.log_hand_return
    PowerLogHandOrigin.OTHER -> R.string.log_hand_added
    PowerLogHandOrigin.UNKNOWN -> R.string.log_hand_unknown
    PowerLogHandOrigin.COIN -> R.string.cloud_coin_card
    PowerLogHandOrigin.GENERATED -> R.string.log_hand_added
}
