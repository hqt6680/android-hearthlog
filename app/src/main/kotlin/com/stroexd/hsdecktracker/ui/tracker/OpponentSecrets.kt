package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.cards.opponentSecretCandidates
import com.stroexd.hsdecktracker.core.tracker.TrackerState

@Composable
fun OpponentSecrets(state: TrackerState, db: CardDatabase) {
    val format = state.format
    val opponent = state.opponentClass
    if (!state.autoTracked || db.isEmpty || !opponent.isPlayable ||
        (format != GameFormat.STANDARD && format != GameFormat.WILD)
    ) return

    var expanded by remember(state.startedAt, opponent, format) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.secret_candidates_hide else R.string.secret_candidates_show))
        }
        if (expanded) {
            val settings by LocalContext.current.appContainer.settings.settings.collectAsStateWithLifecycle()
            val candidates = remember(db, opponent, format, settings) {
                opponentSecretCandidates(db, opponent, format, settings.formatRules)
            }
            Text(
                stringResource(R.string.secret_candidates_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.secret_candidates_empty), style = MaterialTheme.typography.bodySmall)
            } else {
                candidates.forEach { card ->
                    Text(
                        stringResource(R.string.secret_candidate_row, card.cost, card.name),
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
