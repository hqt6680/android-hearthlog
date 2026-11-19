package com.stroexd.hsdecktracker.core.vision

import kotlin.math.max
import kotlin.math.min

/** A pixel rectangle on the screen. */
data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class OverlaySide { LEFT, RIGHT }

/**
 * Where the overlay fits next to Hearthstone's board. The board scales with the screen height and stays centered,
 * so wide phones keep empty curtains left and right. The overlay takes one of them, clear of camera cutouts, the
 * rounded display corners and the options button in the top right.
 */
object OverlayLayout {
    /** Board width per height, including where the opponent's played card appears (measured on a 20:9 phone). */
    const val BOARD_ASPECT = 1.64f

    /** Hearthstone's options button and battery indicator sit at the top of the right curtain. */
    private const val OPTIONS_BUTTON = 0.1f

    fun sidePanel(
        screenWidth: Int,
        screenHeight: Int,
        side: OverlaySide,
        minWidth: Int,
        maxWidth: Int,
        gap: Int,
        cornerRadius: Int = 0,
        cutouts: List<ScreenBox> = emptyList(),
    ): ScreenBox {
        val curtain = ((screenWidth - BOARD_ASPECT * screenHeight) / 2).toInt()
        var left = if (side == OverlaySide.LEFT) gap else screenWidth - curtain + gap
        var right = if (side == OverlaySide.LEFT) curtain - gap else screenWidth - gap
        var top = max(gap, cornerRadius)
        var bottom = screenHeight - max(gap, cornerRadius)
        if (side == OverlaySide.RIGHT) top = max(top, (screenHeight * OPTIONS_BUTTON).toInt())
        for (cutout in cutouts) {
            val dockWidth = (right - left).coerceIn(minWidth, maxWidth)
            val dockLeft = if (side == OverlaySide.LEFT) left else right - dockWidth
            if (!cutout.overlapsHorizontally(dockLeft, dockLeft + dockWidth)) continue
            // A camera near a corner only costs height; one in the middle of the edge pushes the panel inwards
            when {
                cutout.bottom <= screenHeight * 0.35f -> top = max(top, cutout.bottom + gap)
                cutout.top >= screenHeight * 0.65f -> bottom = min(bottom, cutout.top - gap)
                side == OverlaySide.LEFT -> left = max(left, cutout.right + gap)
                else -> right = min(right, cutout.left - gap)
            }
        }
        val width = (right - left).coerceIn(minWidth, maxWidth)
        return if (side == OverlaySide.LEFT) {
            ScreenBox(left, top, left + width, bottom)
        } else {
            ScreenBox(right - width, top, right, bottom)
        }
    }

    internal fun safeVerticalAreas(
        screenHeight: Int,
        left: Int,
        right: Int,
        gap: Int,
        cornerRadius: Int,
        cutouts: List<ScreenBox>,
    ): List<ScreenBox> {
        val inset = max(gap, cornerRadius).coerceIn(0, ((screenHeight - 1) / 2).coerceAtLeast(0))
        val bottom = screenHeight - inset
        var top = inset
        return buildList {
            for (cutout in cutouts.filter { it.overlapsHorizontally(left, right) }.sortedBy { it.top }) {
                val clearEnd = (cutout.top - gap).coerceAtMost(bottom)
                if (clearEnd > top) add(ScreenBox(left, top, right, clearEnd))
                top = max(top, (cutout.bottom + gap).coerceAtMost(bottom))
            }
            if (top < bottom) add(ScreenBox(left, top, right, bottom))
        }
    }

    private fun ScreenBox.overlapsHorizontally(left: Int, right: Int): Boolean =
        this.right > left && this.left < right

    fun nearestSide(centerX: Int, screenWidth: Int): OverlaySide =
        if (centerX < screenWidth / 2) OverlaySide.LEFT else OverlaySide.RIGHT
}
