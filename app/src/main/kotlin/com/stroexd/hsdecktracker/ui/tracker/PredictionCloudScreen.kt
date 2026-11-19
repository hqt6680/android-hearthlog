@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.tracker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.cloud.CloudAction
import com.stroexd.hsdecktracker.cloud.CloudPredictionState
import com.stroexd.hsdecktracker.cloud.PredictionStage
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.powerlog.PowerLogOpeningCard
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import com.stroexd.hsdecktracker.core.prediction.PowerLogPredictionKind
import com.stroexd.hsdecktracker.powerlog.LogCardNames
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import java.text.NumberFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun PredictionScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val cloud = container.cloudPrediction
    val replay by cloud.replayState.collectAsStateWithLifecycle()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cards by container.cards.state.collectAsStateWithLifecycle()
    val completeDecks = remember(decks) { decks.filter { it.cardCount in setOf(20, 30, 40) }.sortedByDescending { it.updatedAt } }
    var deckId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseDeck by rememberSaveable { mutableStateOf(false) }
    var rawTurn by rememberSaveable { mutableStateOf("") }
    var optionsId by rememberSaveable { mutableStateOf("") }
    val deck = completeDecks.firstOrNull { it.id == deckId }
    val openReplay = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && deck != null) {
            cloud.submitReplay(deck, rawTurn.toIntOrNull(), optionsId.toIntOrNull()) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.device_prediction)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (cloud.canPredict) {
                Text(stringResource(R.string.device_prediction_enabled),
                    style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.cloud_replay), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.cloud_replay_instructions), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        if (completeDecks.isEmpty()) {
                            Text(stringResource(R.string.cloud_replay_deck_empty), style = MaterialTheme.typography.bodySmall)
                        } else {
                            OutlinedButton(onClick = { chooseDeck = true }) {
                                Text(deck?.name ?: stringResource(R.string.cloud_replay_deck))
                            }
                        }
                    }
                    item {
                        OutlinedTextField(rawTurn, { rawTurn = it.filter(Char::isDigit).take(6) },
                            Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cloud_replay_turn)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    }
                    item {
                        OutlinedTextField(optionsId, { optionsId = it.filter(Char::isDigit).take(6) },
                            Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cloud_replay_options)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    }
                    item {
                        Button(onClick = { openReplay.launch(arrayOf("*/*")) },
                            enabled = deck != null && replay.stage != PredictionStage.PREDICTING &&
                                (rawTurn.isEmpty() || (rawTurn.toIntOrNull() ?: 0) > 0) &&
                                (optionsId.isEmpty() || (optionsId.toIntOrNull() ?: 0) > 0)) {
                            Text(stringResource(R.string.cloud_replay_open))
                        }
                    }
                    item { CloudPredictionResult(replay, cards.db, includeAnchor = true) }
                }
            } else {
                Text(stringResource(R.string.device_prediction_unavailable), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (chooseDeck && cloud.canPredict) {
        AlertDialog(onDismissRequest = { chooseDeck = false },
            title = { Text(stringResource(R.string.cloud_replay_deck)) },
            text = {
                LazyColumn {
                    items(completeDecks, key = { it.id }) { candidate ->
                        ListItem(headlineContent = { Text(candidate.name) },
                            modifier = Modifier.clickable {
                                deckId = candidate.id
                                chooseDeck = false
                            })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chooseDeck = false }) { Text(stringResource(R.string.back)) } })
    }
}

@Composable
internal fun CloudPredictionResult(
    state: CloudPredictionState,
    cards: CardDatabase,
    includeAnchor: Boolean = false,
    match: PowerLogSnapshot? = null,
    showWinRate: Boolean = true,
) {
    val result = state.result
    val opponentTurn = state.key?.kind == PowerLogPredictionKind.OPPONENT
    val app = LocalContext.current.appContainer
    val refreshing = opponentTurn && result?.winRatePercent != null &&
        state.stage in setOf(PredictionStage.WAITING, PredictionStage.PREDICTING)
    val message = if (refreshing) R.string.cloud_refreshing_win_rate else when (state.stage) {
        PredictionStage.IDLE, PredictionStage.READY -> null
        PredictionStage.NEED_DECK -> R.string.cloud_need_deck
        PredictionStage.LOG_TOO_LARGE -> R.string.cloud_log_large
        PredictionStage.NO_DECISION -> R.string.cloud_no_decision
        PredictionStage.WAITING -> R.string.cloud_waiting_decision
        PredictionStage.PREDICTING -> R.string.device_prediction_running
        PredictionStage.FAILED -> when (state.errorCode) {
            "timeout", "expired" -> R.string.device_prediction_timeout
            "connection", "busy", "predictor_auth_required" -> R.string.device_prediction_unavailable
            "log_too_large" -> R.string.cloud_log_large
            else -> R.string.device_prediction_failure
        }
    }
    val steps = result?.recommendations.orEmpty().takeUnless { opponentTurn }.orEmpty()
    val emptyRecommendation = state.stage == PredictionStage.READY &&
        state.key?.kind == PowerLogPredictionKind.ACTION && steps.isEmpty()
    if (message == null && !emptyRecommendation && steps.isEmpty() &&
        !(showWinRate && result?.winRatePercent != null) && !(includeAnchor && state.key != null)
    ) return
    val names = app.logCardNames
    val entityCards = remember(match) { visibleEntityCards(match) }
    val attackers = remember(match) { attackSlots(match) }
    Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (includeAnchor) state.key?.let {
            Text(stringResource(R.string.cloud_turn_options, it.rawTurn, it.optionsId), style = MaterialTheme.typography.labelMedium)
        }
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        if (showWinRate) CloudPredictionWinRate(state)
        if (emptyRecommendation) {
            Text(stringResource(R.string.cloud_no_recommendation), style = MaterialTheme.typography.bodySmall)
        }
        steps.forEach { action ->
            val shape = MaterialTheme.shapes.small
            val surface = MaterialTheme.colorScheme.surfaceContainerHigh
            val cardId = action.card.visibleCardId(entityCards)
                ?: action.subOption.visibleCardId(entityCards)
                ?: action.target.takeIf { action.action == "choose" || action.action == "choice" }.visibleCardId(entityCards)
            Box(Modifier.fillMaxWidth().clip(shape).background(surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
                LogCardBackdrop(cardId, Modifier.matchParentSize(), surface)
                Text(
                    text = action.describe(cards, names, entityCards, attackers),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
internal fun CloudPredictionWinRate(state: CloudPredictionState?, modifier: Modifier = Modifier) {
    val rate = state?.result?.winRatePercent ?: return
    val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 2 }.format(rate / 100)
    Text(stringResource(R.string.cloud_win_rate, percent), modifier, style = MaterialTheme.typography.titleSmall)
}

internal fun mulliganMarks(cards: List<PowerLogOpeningCard>, steps: List<CloudAction>): Map<Int, Boolean> {
    val replacements = steps.filter { it.action == "replace" }
    val positions = replacements.mapNotNull { recommendation ->
        val reference = recommendation.card
        val entity = reference.entityId()
        val card = if (entity != null) cards.firstOrNull { it.entityId == entity }
            else reference.cardId()?.let { id -> cards.singleOrNull { it.cardId == id } }
        card?.position
    }.toSet()
    val complete = if (replacements.isEmpty()) steps.any { it.action == "keep_all" } else positions.size == replacements.size
    return buildMap {
        cards.forEach { card ->
            if (card.position in positions) put(card.position, true)
            else if (complete) put(card.position, false)
        }
    }
}

private fun visibleEntityCards(match: PowerLogSnapshot?): Map<Int, String> = buildMap {
    if (match == null) return@buildMap
    for (side in listOf(match.friendly, match.opponent)) {
        for (card in side.hand + side.board + side.graveyard) {
            card.entityId?.let { put(it, card.cardId) }
        }
        side.heroEntityId?.let { id -> side.heroCardId?.let { put(id, it) } }
        side.weaponEntityId?.let { id -> side.weaponCardId?.let { put(id, it) } }
        side.heroPower?.let { power -> power.cardId?.let { put(power.entityId, it) } }
    }
    match.openingChoice?.cards?.forEach { card -> card.entityId?.let { put(it, card.cardId) } }
    match.generalChoice?.options?.forEach { card -> card.cardId?.let { put(card.entityId, it) } }
}

private data class AttackSlot(val id: Int?, val cardId: String?, val label: Int, val position: Int? = null)

private fun attackSlots(match: PowerLogSnapshot?): List<AttackSlot> = buildList {
    if (match == null || !match.localPlayerIdentified) return@buildList
    listOf(match.friendly, match.opponent).forEachIndexed { index, side ->
        val friendly = index == 0
        val hero = if (friendly) R.string.cloud_friendly_hero else R.string.cloud_enemy_hero
        add(AttackSlot(side.heroEntityId, side.heroCardId, hero))
        add(AttackSlot(side.weaponEntityId, side.weaponCardId, hero))
        side.board.forEach { card ->
            val position = card.combatState?.position?.takeIf { it > 0 }
            val label = when {
                friendly && position != null -> R.string.cloud_friendly_board_position
                position != null -> R.string.cloud_enemy_board_position
                friendly -> R.string.cloud_friendly_minion
                else -> R.string.cloud_enemy_minion
            }
            add(AttackSlot(card.entityId, card.cardId, label, position))
        }
    }
}

private fun JsonElement?.attackSlot(slots: List<AttackSlot>): AttackSlot? {
    val id = entityId()
    return if (id != null) slots.singleOrNull { it.id == id }
        else cardId()?.let { code -> slots.singleOrNull { it.cardId == code } }
}

@Composable
private fun AttackSlot.label(): String = position?.let { stringResource(label, it) } ?: stringResource(label)

@Composable
private fun CloudAction.describe(
    cards: CardDatabase,
    names: LogCardNames,
    entityCards: Map<Int, String>,
    slots: List<AttackSlot>,
): String {
    val kind = action.lowercase().replace('-', '_')
    val targets = listOf(target, opponentTarget, targetHero, opponentTargetHero)
    if (kind in setOf("attack", "minion_attack", "hero_attack", "weapon_attack")) {
        val subject = card.attackSlot(slots)?.label() ?: stringResource(
            if (kind == "hero_attack" || kind == "weapon_attack") R.string.cloud_friendly_hero else R.string.cloud_friendly_minion,
        )
        val aim = targets.firstNotNullOfOrNull { it.attackSlot(slots) }?.label()
            ?: stringResource(R.string.cloud_enemy_target)
        return stringResource(R.string.cloud_attack_action, subject, aim)
    }
    val label = when (kind) {
        "play", "play_card", "play_minion", "play_spell", "play_weapon", "play_location", "play_hero", "play_special" ->
            stringResource(R.string.cloud_play_action)
        "end_turn", "endturn" -> stringResource(R.string.cloud_end_action)
        "use_hero_power", "hero_power", "hero_skill" -> stringResource(R.string.cloud_power_action)
        "use_location", "location", "location_power" -> stringResource(R.string.cloud_location_action)
        "trade", "trade_card" -> stringResource(R.string.cloud_trade_action)
        "choice", "choose" -> stringResource(R.string.cloud_choice_action)
        "replace" -> stringResource(R.string.cloud_replace_action)
        "keep_all" -> stringResource(R.string.cloud_keep_all_action)
        else -> action
    }
    val chosenCard = kind == "choice" || kind == "choose" || kind == "replace"
    val sources = if (chosenCard) listOf(card, subOption) + targets else listOf(card)
    val subject = sources.displayCard(cards, names, entityCards)
    val aim = if (chosenCard) null else targets.displayCard(cards, names, entityCards)
    val placement = position?.takeIf { it >= 0 && (kind == "play_minion" || kind == "play_location") }
    return listOfNotNull(
        listOfNotNull(label, subject).joinToString(" "),
        aim?.let { stringResource(R.string.cloud_target, it) },
        placement?.let { stringResource(R.string.cloud_position, it) },
    ).joinToString(" · ")
}

private fun JsonElement?.entityId(): Int? = listOf("ENTITY_ID", "entityId", "_ENTITY_ID").firstNotNullOfOrNull {
    ((this as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { id -> id > 0 }
} ?: (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }

private fun JsonElement?.cardId(): String? = listOf("cardId", "_CARD_ID", "CARD_ID").firstNotNullOfOrNull {
    ((this as? JsonObject)?.get(it) as? JsonPrimitive)?.contentOrNull?.takeIf { code -> code.isNotBlank() }
} ?: (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }

private fun JsonElement?.visibleCardId(entityCards: Map<Int, String>): String? =
    entityId()?.let(entityCards::get) ?: cardId()

@Composable
private fun List<JsonElement?>.displayCard(cards: CardDatabase, names: LogCardNames, entityCards: Map<Int, String>): String? {
    for (reference in this) {
        val id = reference.visibleCardId(entityCards) ?: continue
        names.resolve(cards, id)?.let { return it }
        if (id == "GAME_005" || id == "TTN_COIN2") return stringResource(R.string.cloud_coin_card)
    }
    return if (any { it.entityId() != null || it.cardId() != null }) stringResource(R.string.log_unknown_card) else null
}
