@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.decks

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.core.cards.GameFormat
import com.stroexd.hsdecktracker.core.deck.Deck
import com.stroexd.hsdecktracker.core.deck.DeckTextParser
import com.stroexd.hsdecktracker.overlay.rememberPlayAndTrack
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes
import com.stroexd.hsdecktracker.ui.components.Banner
import com.stroexd.hsdecktracker.ui.components.ChipRow
import com.stroexd.hsdecktracker.ui.components.ClassBadge
import com.stroexd.hsdecktracker.ui.components.ClassPickerDialog
import com.stroexd.hsdecktracker.ui.components.EmptyState
import com.stroexd.hsdecktracker.ui.defaultDeckName
import com.stroexd.hsdecktracker.ui.label
import com.stroexd.hsdecktracker.ui.labelRes
import com.stroexd.hsdecktracker.ui.message
import com.stroexd.hsdecktracker.ui.navigateTopLevel
import com.stroexd.hsdecktracker.ui.readClipboardText
import com.stroexd.hsdecktracker.ui.theme.HsColors
import com.stroexd.hsdecktracker.ui.theme.uiColor
import kotlinx.coroutines.launch

private enum class DeckSort(@StringRes val label: Int) {
    RECENT(R.string.sort_recent),
    NAME(R.string.sort_name),
}

@Composable
fun DecksScreen(
    navController: NavHostController,
    sharedText: String?,
    onSharedTextHandled: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val decks by container.decks.decks.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val logSession by container.powerLog.state.collectAsStateWithLifecycle()
    val startTracking = rememberPlayAndTrack {
        navController.navigate(Routes.TRACKER) { launchSingleTop = true }
    }

    var formatFilter by rememberSaveable { mutableStateOf<GameFormat?>(null) }
    var sort by rememberSaveable { mutableStateOf(DeckSort.RECENT) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var importInitialText by remember { mutableStateOf("") }
    var showClassPicker by rememberSaveable { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            importInitialText = sharedText
            showImport = true
            onSharedTextHandled()
        }
    }

    val visible = remember(decks, formatFilter, sort) {
        val filtered = decks.filter { formatFilter == null || it.format == formatFilter }
        when (sort) {
            DeckSort.RECENT -> filtered.sortedWith(compareByDescending<Deck> { it.favorite }.thenByDescending { it.updatedAt })
            DeckSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_decks)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.PREDICTION) }) {
                        Icon(Icons.Filled.Analytics, contentDescription = stringResource(R.string.device_prediction))
                    }
                    IconButton(onClick = { navController.navigate(Routes.TRACKER) }) {
                        Icon(Icons.Filled.Layers, contentDescription = stringResource(R.string.tracker))
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { showAddMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_deck)) },
                )
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_deck_code)) },
                        leadingIcon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            importInitialText = context.readClipboardText()?.takeIf { DeckTextParser.parseFirst(it) != null }.orEmpty()
                            showImport = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.build_new_deck)) },
                        leadingIcon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            showClassPicker = true
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                PlayCard(
                    title = stringResource(R.string.play_and_track_with_log),
                    subtitle = if (logSession.active) null else stringResource(R.string.power_log_ready),
                    onClick = startTracking,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            if (cardState.loading && cardState.db.isEmpty) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.loading_cards), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            cardState.error?.let { error ->
                item {
                    Banner(
                        error.message(),
                        isError = true,
                        actionLabel = stringResource(R.string.retry),
                        onAction = { container.refreshCards() },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (decks.isNotEmpty()) {
                item {
                    ChipRow(
                        options = listOf<GameFormat?>(null, GameFormat.STANDARD, GameFormat.WILD),
                        isSelected = { it == formatFilter },
                        label = { it?.let { format -> context.getString(format.labelRes()) } ?: context.getString(R.string.all) },
                        onClick = { formatFilter = it },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item {
                    ChipRow(
                        options = DeckSort.entries.toList(),
                        isSelected = { it == sort },
                        label = { "↕ " + context.getString(it.label) },
                        onClick = { sort = it },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (decks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Style,
                        title = stringResource(R.string.no_decks_title),
                        message = stringResource(R.string.no_decks_message),
                        actions = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    importInitialText = context.readClipboardText()?.takeIf { DeckTextParser.parseFirst(it) != null }.orEmpty()
                                    showImport = true
                                }) { Text(stringResource(R.string.import_deck_code)) }
                                OutlinedButton(onClick = { navController.navigateTopLevel(Routes.META) }) { Text(stringResource(R.string.browse_meta_decks)) }
                            }
                        },
                    )
                }
            }
            items(visible, key = { it.id }) { deck ->
                DeckListItem(
                    deck = deck,
                    onClick = { navController.navigate(Routes.deck(deck.id)) },
                )
            }
        }
    }

    if (showImport) {
        ImportDeckDialog(
            initialText = importInitialText,
            onDismiss = { showImport = false },
            onImport = { text ->
                scope.launch {
                    val imported = container.decks.importFromText(
                        text, container.cards.db, defaultDeckName(context), setOf(GameFormat.STANDARD, GameFormat.WILD),
                    )
                    showImport = false
                    snackbar.showSnackbar(
                        when (imported.size) {
                            0 -> context.getString(R.string.no_valid_deck_code)
                            1 -> context.getString(R.string.deck_imported, imported.first().name)
                            else -> context.resources.getQuantityString(R.plurals.decks_imported, imported.size, imported.size)
                        },
                    )
                }
            },
        )
    }
    if (showClassPicker) {
        ClassPickerDialog(
            title = stringResource(R.string.choose_class),
            onDismiss = { showClassPicker = false },
            onPick = { cls ->
                showClassPicker = false
                navController.navigate(Routes.builder(cls = cls, format = GameFormat.STANDARD))
            },
        )
    }
}

@Composable
private fun PlayCard(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = HsColors.Gold,
                modifier = Modifier.width(40.dp).height(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
private fun DeckListItem(deck: Deck, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(deck.heroClass.uiColor))
            Column(Modifier.weight(1f).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (deck.favorite) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = HsColors.Gold, modifier = Modifier.padding(end = 4.dp))
                    }
                    Text(
                        deck.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClassBadge(deck.heroClass)
                    Text(
                        "${deck.format.label()} · " + pluralStringResource(R.plurals.card_count, deck.cardCount, deck.cardCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun ImportDeckDialog(initialText: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    val found = remember(text) { DeckTextParser.parseAll(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_deck_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.import_deck_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text("AAECA…") },
                )
                TextButton(onClick = { context.readClipboardText()?.let { text = it } }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.paste_from_clipboard))
                }
                Text(
                    when {
                        found.isEmpty() -> if (text.isBlank()) "" else stringResource(R.string.no_valid_deck_code)
                        found.size == 1 && found.first().name != null -> stringResource(R.string.one_deck_found_named, found.first().name!!)
                        else -> pluralStringResource(R.plurals.decks_found, found.size, found.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (found.isEmpty()) HsColors.Warning else HsColors.Win,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(text) }, enabled = found.isNotEmpty()) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
