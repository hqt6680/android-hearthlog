package com.stroexd.hsdecktracker.ui.tracker

import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.powerlog.PowerLogCard
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSide
import com.stroexd.hsdecktracker.core.powerlog.PowerLogSnapshot
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.powerlog.GameLogSetupResult
import com.stroexd.hsdecktracker.powerlog.PowerLogSourceState
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.label
import kotlinx.coroutines.launch

@Composable
fun PowerLogStartControl(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val source = LocalAppContainer.current.powerLogSource
    val sourceState by source.state.collectAsStateWithLifecycle()
    val mayAsk = sourceState == PowerLogSourceState.PERMISSION_REQUIRED ||
        sourceState == PowerLogSourceState.PERMISSION_DENIED
    val mayStart = sourceState == PowerLogSourceState.READY
    val scope = rememberCoroutineScope()
    var enabling by remember { mutableStateOf(false) }
    var setupResult by remember { mutableStateOf<GameLogSetupResult?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.power_log_start), style = MaterialTheme.typography.titleMedium)
        val message = if (mayStart) R.string.power_log_setup_ready else sourceState.message()
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
        if (mayStart) {
            OutlinedButton(
                enabled = !enabling,
                onClick = {
                    enabling = true
                    scope.launch {
                        try {
                            setupResult = source.enableGameLogging()
                        } finally {
                            enabling = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (enabling) R.string.power_log_setup_in_progress else R.string.power_log_setup_enable))
            }
            setupResult?.let { result ->
                Text(stringResource(result.message()), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mayAsk || mayStart) {
            Button(enabled = !enabling, onClick = {
                when {
                    mayAsk -> source.requestPermission()
                    !OverlayLauncher.canDrawOverlays(context) -> OverlayLauncher.requestOverlayPermission(context)
                    else -> OverlayLauncher.startLog(context)
                }
            }) {
                Text(stringResource(if (mayAsk) R.string.power_log_authorize else R.string.power_log_start))
            }
        }
    }
}

@StringRes
private fun GameLogSetupResult.message(): Int = when (this) {
    GameLogSetupResult.UPDATED -> R.string.power_log_setup_updated
    GameLogSetupResult.ALREADY_ENABLED -> R.string.power_log_setup_already_enabled
    GameLogSetupResult.GAME_FILES_MISSING -> R.string.power_log_setup_game_files_missing
    GameLogSetupResult.FAILED -> R.string.power_log_setup_failed
}

@Composable
fun PowerLogWaiting(state: PowerLogSourceState) {
    Text(
        stringResource(state.message()),
        modifier = Modifier.padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@StringRes
private fun PowerLogSourceState.message(): Int = when (this) {
    PowerLogSourceState.UNAVAILABLE -> R.string.power_log_shizuku_off
    PowerLogSourceState.SHELL_REQUIRED -> R.string.power_log_shell_required
    PowerLogSourceState.PERMISSION_REQUIRED, PowerLogSourceState.PERMISSION_DENIED -> R.string.power_log_permission
    PowerLogSourceState.REQUESTING_PERMISSION, PowerLogSourceState.CONNECTING -> R.string.power_log_connecting
    PowerLogSourceState.READY -> R.string.power_log_ready
    PowerLogSourceState.WAITING_FOR_LOG -> R.string.power_log_no_file
    PowerLogSourceState.READING -> R.string.power_log_waiting_match
    PowerLogSourceState.ERROR -> R.string.power_log_read_error
}

@Composable
fun PowerLogPanel(match: PowerLogSnapshot, db: CardDatabase, compact: Boolean, modifier: Modifier = Modifier) {
    val container = LocalContext.current.appContainer
    val session by container.powerLog.state.collectAsStateWithLifecycle()
    val cloud by container.cloudPrediction.liveState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable(match.gameNumber) { mutableIntStateOf(0) }
    val firstName = stringResource(if (match.localPlayerIdentified) R.string.power_log_us else R.string.power_log_player_a)
    val secondName = stringResource(if (match.localPlayerIdentified) R.string.power_log_opponent else R.string.power_log_player_b)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(match.format.label(), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.power_log_turn, match.turn), style = MaterialTheme.typography.labelMedium)
        }
        val tabs = listOf(R.string.power_log_hand, R.string.power_log_graveyard, R.string.power_log_board)
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                if (compact) {
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(title), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(stringResource(title), fontSize = 14.sp, maxLines = 1) },
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f, fill = !compact),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (selectedTab) {
                0 -> {
                    if (cloud.key != null && cloud.key == session.cloudDecision) {
                        item { CloudPredictionResult(cloud, db, match = match) }
                    }
                    item { SideStatus(firstName, match.friendly) }
                    if (match.localPlayerIdentified) cards(match.friendly.hand, db)
                    else item { Text(stringResource(R.string.power_log_local_unknown), style = MaterialTheme.typography.bodySmall) }
                    if (match.friendly.secrets.isNotEmpty()) {
                        item { Section(stringResource(R.string.power_log_known_secrets)) }
                        cards(match.friendly.secrets, db)
                    }
                    item { SideStatus(secondName, match.opponent) }
                    if (match.opponent.played.isNotEmpty()) {
                        item { Section(stringResource(R.string.power_log_public_plays)) }
                        cards(match.opponent.played, db)
                    }
                }
                1 -> {
                    item { Section(firstName) }
                    cards(match.friendly.graveyard, db)
                    item { Section(secondName) }
                    cards(match.opponent.graveyard, db)
                }
                2 -> {
                    item { BoardAttack(firstName, match.friendly) }
                    cards(match.friendly.board, db, showAttack = true)
                    item { BoardAttack(secondName, match.opponent) }
                    cards(match.opponent.board, db, showAttack = true)
                }
            }
        }
    }
}

@Composable
private fun SideStatus(name: String, side: PowerLogSide) {
    Column {
        Section(name)
        Text(
            stringResource(R.string.power_log_counts, side.handCount, side.deckCount?.toString() ?: "—", side.secretCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoardAttack(name: String, side: PowerLogSide) {
    Column {
        Section(name)
        AttackLines(side)
    }
}

@Composable
private fun Section(name: String) {
    Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

private fun LazyListScope.cards(cards: List<PowerLogCard>, db: CardDatabase, showAttack: Boolean = false) {
    val entries = cards.groupingBy { it }.eachCount().entries.toList()
    items(entries) { (card, copies) ->
        val attack = card.attack
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = LocalContext.current.appContainer.logCardNames.resolve(db, card.cardId)
                ?: stringResource(R.string.log_unknown_card)
            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (showAttack && attack != null) {
                Text(stringResource(R.string.power_log_attack_short, attack), style = MaterialTheme.typography.bodySmall)
            }
            if (copies > 1) Text("×$copies", style = MaterialTheme.typography.bodySmall)
        }
    }
}
