package com.stroexd.hsdecktracker.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.ui.tracker.PowerLogAdvicePanel
import com.stroexd.hsdecktracker.ui.tracker.PowerLogDeckPanel
import com.stroexd.hsdecktracker.ui.tracker.PowerLogWaiting

internal val BUBBLE_SIZE = 44.dp

@Composable
internal fun PowerLogOverlayContent(
    side: OverlaySide,
    panelSize: DpSize,
    onDrag: (Float, Float) -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val session by container.powerLog.state.collectAsStateWithLifecycle()
    val source by container.powerLogSource.state.collectAsStateWithLifecycle()
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val match = session.snapshot
    val isAdvice = side == OverlaySide.LEFT
    val title = stringResource(if (isAdvice) R.string.log_advice else R.string.log_deck)
    var collapsed by remember { mutableStateOf(true) }
    val matchInProgress = match != null && match.result == null
    LaunchedEffect(session.revision, matchInProgress) { collapsed = !matchInProgress }
    LaunchedEffect(collapsed) { onCollapsedChange(collapsed) }

    val drag by rememberUpdatedState(onDrag)
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { change, amount ->
            change.consume()
            drag(amount.x, amount.y)
        }
    }

    if (collapsed) {
        val discLabel = if (isAdvice) "AI" else
            match?.takeIf { it.localPlayerIdentified }?.friendly?.deckCount?.toString()
                ?: stringResource(R.string.log_deck_bubble)
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.alpha(settings.overlayOpacity).then(dragModifier).semantics { contentDescription = title },
            onClick = { collapsed = false },
        ) {
            Box(Modifier.size(BUBBLE_SIZE), contentAlignment = Alignment.Center) {
                Text(discLabel, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.width(panelSize.width).heightIn(max = panelSize.height).alpha(settings.overlayOpacity),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .then(dragModifier).padding(start = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { collapsed = true }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Filled.Remove, stringResource(R.string.minimize), Modifier.size(16.dp))
                }
                if (isAdvice) {
                    IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.close), Modifier.size(16.dp))
                    }
                } else {
                    Spacer(Modifier.size(2.dp))
                }
            }
            when {
                match == null -> PowerLogWaiting(source)
                isAdvice -> PowerLogAdvicePanel(match, cardState.db, session)
                else -> {
                    val decks by container.decks.decks.collectAsStateWithLifecycle()
                    PowerLogDeckPanel(match, cardState.db, session, decks) {
                        container.powerLog.selectDeck(session.revision, it)
                    }
                }
            }
        }
    }
}
