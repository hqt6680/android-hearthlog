package com.stroexd.hsdecktracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.core.cards.Card
import com.stroexd.hsdecktracker.ui.LocalAppContainer

@Composable
fun DeckCardDialog(
    card: Card,
    onDismiss: () -> Unit,
    copiesInDeck: Int? = null,
    deckSize: Int = 30,
) {
    val cardState by LocalAppContainer.current.cards.state.collectAsStateWithLifecycle()
    CardDetailDialog(
        card = card,
        locale = cardState.db.locale,
        setName = cardState.db.setName(card.set),
        onDismiss = onDismiss,
        copiesInDeck = copiesInDeck,
        deckSize = deckSize,
    )
}
