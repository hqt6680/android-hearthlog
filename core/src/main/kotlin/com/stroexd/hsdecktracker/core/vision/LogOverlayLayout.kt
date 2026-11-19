package com.stroexd.hsdecktracker.core.vision

import kotlin.math.abs
import kotlin.math.roundToInt

data class LogOverlayPlacement(
    val x: Int,
    val y: Int,
    val panelWidth: Int,
    val panelHeight: Int,
    val offsetX: Float,
    val offsetY: Float,
)

object LogOverlayLayout {
    fun reserveOriginalResultArea(
        panel: ScreenBox,
        screenWidth: Int,
        screenHeight: Int,
        side: OverlaySide,
        gap: Int,
        opening: Boolean = false,
    ): ScreenBox {
        if (screenWidth <= screenHeight) return panel
        return when (side) {
            OverlaySide.LEFT -> {
                val reservedLeft = screenWidth / 4
                val limit = maxOf(reservedLeft + 1, screenWidth / 2 - gap)
                val width = panel.width.coerceIn(1, limit - reservedLeft)
                val left = panel.left.coerceIn(reservedLeft, limit - width)
                val top = if (opening) {
                    (screenHeight * 0.66f).roundToInt().coerceIn(panel.top, maxOf(panel.top, panel.bottom - 1))
                } else panel.top
                panel.copy(left = left, right = left + width, top = top)
            }
            OverlaySide.RIGHT -> {
                val reservedTop = (screenHeight * 0.14f).roundToInt()
                val top = reservedTop.coerceIn(panel.top, maxOf(panel.top, panel.bottom - 1))
                panel.copy(top = top)
            }
        }
    }

    fun place(
        panel: ScreenBox,
        screenWidth: Int,
        screenHeight: Int,
        side: OverlaySide,
        gap: Int,
        collapsedWidth: Int?,
        minimumHeight: Int,
        offsetX: Float,
        offsetY: Float,
        cornerRadius: Int = 0,
        cutouts: List<ScreenBox> = emptyList(),
    ): LogOverlayPlacement {
        val center = screenWidth / 2
        val gutter = gap.coerceIn(0, (center - 1).coerceAtLeast(0))
        val onLeft = side == OverlaySide.LEFT
        val left = if (onLeft) 0 else center + gutter
        val right = if (onLeft) center - gutter else screenWidth
        val panelWidth = minOf(panel.width, right - left).coerceAtLeast(1)
        val occupiedWidth = collapsedWidth?.coerceAtMost(panelWidth) ?: panelWidth
        val lastX = maxOf(left, right - occupiedWidth)
        val anchorX = (if (onLeft) panel.left else panel.right - occupiedWidth).coerceIn(left, lastX)
        val xOffset = offsetX.coerceIn(
            if (onLeft) 0f else (left - anchorX).toFloat(),
            if (onLeft) (lastX - anchorX).toFloat() else 0f,
        )
        val x = anchorX + xOffset.roundToInt()
        val areas = OverlayLayout.safeVerticalAreas(screenHeight, x, x + occupiedWidth, gap, cornerRadius, cutouts)
        val requestedY = panel.top + offsetY
        fun lastY(area: ScreenBox) = maxOf(area.top, area.bottom - minimumHeight)
        val area = areas.filter { it.height >= minimumHeight }.minByOrNull {
            abs(requestedY - requestedY.coerceIn(it.top.toFloat(), lastY(it).toFloat()))
        } ?: areas.maxByOrNull { it.height } ?: ScreenBox(x, 0, x + occupiedWidth, 1)
        val yOffset = offsetY.coerceIn((area.top - panel.top).toFloat(), (lastY(area) - panel.top).toFloat())
        val y = panel.top + yOffset.roundToInt()
        return LogOverlayPlacement(
            x = x,
            y = y,
            panelWidth = panelWidth,
            panelHeight = area.bottom - y,
            offsetX = xOffset,
            offsetY = yOffset,
        )
    }
}
