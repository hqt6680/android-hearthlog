@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.stroexd.hsdecktracker.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stroexd.hsdecktracker.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.core.cards.CardDatabase
import com.stroexd.hsdecktracker.core.collection.CardCollection
import com.stroexd.hsdecktracker.core.collection.CraftAnalysis
import com.stroexd.hsdecktracker.core.collection.CraftingCalculator
import com.stroexd.hsdecktracker.core.data.AppSettings
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckAnalysis
import com.stroexd.hsdecktracker.core.deck.DeckIssue
import com.stroexd.hsdecktracker.core.deck.DeckSummary
import com.stroexd.hsdecktracker.overlay.rememberPlayAndTrack
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.DeckCardDialog
import com.stroexd.hsdecktracker.ui.components.ConfirmDialog
import com.stroexd.hsdecktracker.ui.components.DeckSummaryCard
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.components.IssuesCard
import com.stroexd.hsdecktracker.ui.components.SectionHeader
import com.stroexd.hsdecktracker.ui.components.deckCardItems
import com.stroexd.hsdecktracker.ui.copyToClipboard
import com.stroexd.hsdecktracker.ui.shareText
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.theme.HsColors
import kotlinx.coroutines.launch

@Composable
fun DeckDetailScreen(navController: NavHostController, deckId: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val collection by container.collection.collection.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val deck = decks.firstOrNull { it.id == deckId }
    val startTracking = rememberPlayAndTrack {
        navController.navigate(Routes.TRACKER) { launchSingleTop = true }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedCard by remember { mutableStateOf<Card?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deck?.name ?: stringResource(R.string.deck), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (deck != null) {
                        IconButton(onClick = { scope.launch { container.decks.upsert(deck.copy(favorite = !deck.favorite)) } }) {
                            Icon(
                                if (deck.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (deck.favorite) HsColors.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { context.copyToClipboard("Deck code", deck.deckCode(), context.getString(R.string.deck_code_copied_hint)) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy_deck_code))
                        }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; navController.navigate(Routes.builder(id = deck.id)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = { menuOpen = false; context.shareText(deck.name, DeckAnalysis.exportText(deck, cardState.db)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (deck == null) {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = stringResource(R.string.deck_not_found),
                message = stringResource(R.string.deck_not_found_message),
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        val data = rememberDeckDetailData(deck, cardState.db, collection, settings)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            deckDetailContent(
                deck = deck,
                db = cardState.db,
                data = data,
                collection = collection,
                onCardClick = { selectedCard = it },
                header = {
                    FilledTonalButton(onClick = startTracking) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.play_and_track_with_log))
                    }
                },
            )
        }
    }

    selectedCard?.let { card ->
        DeckCardDialog(
            card = card,
            onDismiss = { selectedCard = null },
            copiesInDeck = deck?.cards?.get(card.dbfId),
            deckSize = deck?.let { DeckAnalysis.expectedSize(it.cards, cardState.db) } ?: 30,
        )
    }

    if (confirmDelete && deck != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete_deck_title),
            message = stringResource(R.string.delete_deck_message, deck.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                scope.launch {
                    container.decks.delete(deck.id)
                    navController.popBackStack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

data class DeckDetailData(
    val analysis: CraftAnalysis,
    val summary: DeckSummary,
    val issues: List<DeckIssue>,
    val expectedSize: Int,
)

@Composable
fun rememberDeckDetailData(deck: Deck, db: CardDatabase, collection: CardCollection, settings: AppSettings): DeckDetailData =
    remember(deck, db, collection, settings.coreSetOwned, settings.standardSetOverrides) {
        DeckDetailData(
            analysis = CraftingCalculator.analyze(deck.cards, deck.sideboards, collection, db, settings.collectionOptions),
            summary = DeckAnalysis.summary(deck.cards, db),
            issues = DeckAnalysis.validate(deck, db, settings.formatRules),
            expectedSize = DeckAnalysis.expectedSize(deck.cards, db),
        )
    }

fun LazyListScope.deckDetailContent(
    deck: Deck,
    db: CardDatabase,
    data: DeckDetailData,
    collection: CardCollection,
    onCardClick: (Card) -> Unit,
    header: @Composable () -> Unit,
) {
    item(key = "header") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ClassBadge(deck.heroClass)
                Text(deck.format.label(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                deck.archetype?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            header()
        }
    }
    item(key = "details") {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IssuesCard(data.issues)
            DeckSummaryCard(data.summary, data.expectedSize)
        }
    }
    item(key = "cards-header") {
        SectionHeader(stringResource(R.string.cards), Modifier.padding(horizontal = 16.dp))
    }
    deckCardItems(deck.cards, deck.sideboards, db, data.analysis, onCardClick)
    if (deck.notes.isNotBlank()) {
        item(key = "notes") {
            Column(Modifier.padding(16.dp)) {
                SectionHeader(stringResource(R.string.notes))
                Text(deck.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

