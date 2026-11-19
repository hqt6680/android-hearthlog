package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.stroexd.hsdecktracker.core.data.CardRepository

@Composable
internal fun LogCardBackdrop(
    cardId: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val id = cardId?.takeIf { cardArtId.matches(it) && it.any(Char::isLetter) } ?: return
    Box(modifier) {
        AsyncImage(
            model = CardRepository.tileUrl(id),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize().alpha(0.8f),
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to color,
            0.28f to color.copy(alpha = 0.86f),
            0.76f to color.copy(alpha = 0.50f),
            1f to color.copy(alpha = 0.63f),
        )))
    }
}

private val cardArtId = Regex("[A-Za-z0-9_-]{1,80}")
