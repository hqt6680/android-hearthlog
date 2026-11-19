package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.vision.LogOverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.core.vision.ScreenBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayLayoutTest {
    // Measured on a 20:9 phone screenshot (2142×960): the right curtain starts at x≈1840 below the options button
    // (y≈80), the opponent's enlarged card reaches into the left one up to x≈330.
    private fun panel(side: OverlaySide, width: Int = 2142, height: Int = 960, cornerRadius: Int = 0, cutouts: List<ScreenBox> = emptyList()) =
        OverlayLayout.sidePanel(width, height, side, minWidth = 230, maxWidth = 480, gap = 8, cornerRadius = cornerRadius, cutouts = cutouts)

    @Test
    fun fitsIntoTheEmptyCurtains() {
        val right = panel(OverlaySide.RIGHT)
        assertTrue(right.left >= 1840 && right.right <= 2142, "$right")
        assertTrue(right.top >= 80, "below the options button: $right")
        val left = panel(OverlaySide.LEFT)
        assertTrue(left.left >= 0 && left.right < 330, "$left")
        assertEquals(952, left.bottom)
    }

    @Test
    fun roundedCornersCostHeight() {
        val box = panel(OverlaySide.LEFT, cornerRadius = 90)
        assertEquals(90, box.top)
        assertEquals(870, box.bottom)
    }

    @Test
    fun avoidsTheCamera() {
        val corner = ScreenBox(0, 20, 80, 110)
        assertEquals(118, panel(OverlaySide.LEFT, cutouts = listOf(corner)).top)
        val middle = ScreenBox(0, 430, 80, 530)
        val pushed = panel(OverlaySide.LEFT, cutouts = listOf(middle))
        assertEquals(88, pushed.left)
        assertTrue(pushed.width >= 230)
        // A camera on the other side changes nothing
        assertEquals(panel(OverlaySide.RIGHT), panel(OverlaySide.RIGHT, cutouts = listOf(middle)))
    }

    @Test
    fun narrowScreensKeepAUsableWidthAtTheEdge() {
        val box = panel(OverlaySide.RIGHT, width = 1920, height = 1080)
        assertEquals(230, box.width)
        assertEquals(1912, box.right)
    }

    @Test
    fun veryWideScreensCapTheWidthAtTheEdge() {
        val box = panel(OverlaySide.LEFT, width = 3000, height = 960)
        assertEquals(480, box.width)
        assertEquals(8, box.left)
    }

    @Test
    fun snapsToTheNearestSide() {
        assertEquals(OverlaySide.LEFT, OverlayLayout.nearestSide(300, 2142))
        assertEquals(OverlaySide.RIGHT, OverlayLayout.nearestSide(1500, 2142))
    }

    @Test
    fun logWindowsFitBothHalvesOnATabletAndPhone() {
        val displays = listOf(Triple(2880, 1800, 2), Triple(2670, 1200, 3), Triple(1200, 2670, 3))
        for ((width, height, density) in displays) {
            val gap = 4 * density
            val minimumHeight = 132 * density
            for (side in OverlaySide.entries) {
                val dock = OverlayLayout.sidePanel(width, height, side, 120 * density, 224 * density, gap)
                val inward = if (side == OverlaySide.LEFT) width.toFloat() else -width.toFloat()
                val expanded = LogOverlayLayout.place(dock, width, height, side, gap, null, minimumHeight, inward, height.toFloat())
                assertTrue(expanded.panelWidth >= 120 * density)
                assertEquals(minimumHeight, expanded.panelHeight)
                if (side == OverlaySide.LEFT) {
                    assertEquals(width / 2 - gap, expanded.x + expanded.panelWidth)
                } else {
                    assertEquals(width / 2 + gap, expanded.x)
                }
                val bubbleWidth = 44 * density
                val bubble = LogOverlayLayout.place(dock, width, height, side, gap, bubbleWidth, bubbleWidth, 0f, height.toFloat())
                val dockX = if (side == OverlaySide.LEFT) dock.left else dock.right - bubbleWidth
                assertEquals(dockX, bubble.x)
                assertEquals(dock.bottom - bubbleWidth, bubble.y)
            }
        }
    }

    @Test
    fun logWindowsRemainSeparatedEvenIfTheDockCrossesTheCenter() {
        val leftDock = ScreenBox(525, 10, 800, 590)
        val rightDock = ScreenBox(200, 60, 475, 590)
        val left = LogOverlayLayout.place(leftDock, 1000, 600, OverlaySide.LEFT, 8, null, 132, 0f, 0f)
        val right = LogOverlayLayout.place(rightDock, 1000, 600, OverlaySide.RIGHT, 8, null, 132, 0f, 0f)
        assertEquals(492, left.x + left.panelWidth)
        assertEquals(508, right.x)
        assertEquals(leftDock.width, left.panelWidth)
        assertEquals(rightDock.width, right.panelWidth)
    }

    @Test
    fun logWindowAccumulatesFractionalDragAndCanReverseAtTheBoundary() {
        val dock = ScreenBox(8, 8, 248, 792)
        val first = LogOverlayLayout.place(dock, 1000, 800, OverlaySide.LEFT, 8, null, 132, 0.4f, 0.4f)
        assertEquals(dock.left, first.x)
        assertEquals(dock.top, first.y)
        val second = LogOverlayLayout.place(dock, 1000, 800, OverlaySide.LEFT, 8, null, 132, first.offsetX + 0.4f, first.offsetY + 0.4f)
        assertEquals(dock.left + 1, second.x)
        assertEquals(dock.top + 1, second.y)
        val edge = LogOverlayLayout.place(dock, 1000, 800, OverlaySide.LEFT, 8, null, 132, 2000f, 2000f)
        val reverse = LogOverlayLayout.place(dock, 1000, 800, OverlaySide.LEFT, 8, null, 132, edge.offsetX - 1, edge.offsetY - 1)
        assertEquals(edge.x - 1, reverse.x)
        assertEquals(edge.y - 1, reverse.y)
    }

    @Test
    fun rightLogWindowCanMoveAboveTheStartingDock() {
        val side = OverlaySide.RIGHT
        val dock = panel(side, width = 1920, height = 1080)
        val start = LogOverlayLayout.place(dock, 1920, 1080, side, 8, null, 132, 0f, 0f)
        assertEquals(108, start.y)

        val raised = LogOverlayLayout.place(dock, 1920, 1080, side, 8, null, 132, 0f, -60f)
        assertEquals(48, raised.y)
        assertEquals(start.panelHeight + 60, raised.panelHeight)

        val raisedBubble = LogOverlayLayout.place(dock, 1920, 1080, side, 8, 44, 44, 0f, -500f)
        assertEquals(8, raisedBubble.y)
        assertEquals(-100f, raisedBubble.offsetY)
        assertEquals(1072, raisedBubble.y + raisedBubble.panelHeight)
    }

    @Test
    fun rightLogWindowUsesCutoutClearanceAtItsDraggedPosition() {
        val side = OverlaySide.RIGHT
        val cutouts = listOf(ScreenBox(1700, 0, 1780, 90), ScreenBox(1700, 1010, 1780, 1080))
        val dock = panel(side, width = 1920, height = 1080, cornerRadius = 50, cutouts = cutouts)
        val nearEdge = LogOverlayLayout.place(dock, 1920, 1080, side, 8, null, 132, 0f, -500f, 50, cutouts)
        assertEquals(98, nearEdge.y)
        assertEquals(1002, nearEdge.y + nearEdge.panelHeight)

        val movedInward = LogOverlayLayout.place(dock, 1920, 1080, side, 8, null, 132, -250f, -500f, 50, cutouts)
        assertTrue(movedInward.x + movedInward.panelWidth <= cutouts.first().left)
        assertEquals(50, movedInward.y)
        assertEquals(1030, movedInward.y + movedInward.panelHeight)
    }

    @Test
    fun officialResultReservationOnTheTabletPreservesDragOffsets() {
        val width = 2880
        val height = 1800
        val gap = 8
        for (side in OverlaySide.entries) {
            val dock = panel(side, width, height)
            val reserved = LogOverlayLayout.reserveOriginalResultArea(dock, width, height, side, gap)
            val dx = if (side == OverlaySide.LEFT) 60f else -60f
            val base = LogOverlayLayout.place(dock, width, height, side, gap, null, 264, dx, 40f)
            val moved = LogOverlayLayout.place(reserved, width, height, side, gap, null, 264, dx, 40f)
            assertEquals(base.offsetX, moved.offsetX)
            assertEquals(base.offsetY, moved.offsetY)
            assertEquals(dock.width, moved.panelWidth)
            if (side == OverlaySide.LEFT) {
                assertEquals(720 + 60, moved.x)
                assertEquals(base.y, moved.y)
            } else {
                assertEquals(base.x, moved.x)
                assertEquals(252 + 40, moved.y)
            }
        }
    }

    @Test
    fun officialResultReservationFitsNarrowLandscapeAndLeavesPortraitUnchanged() {
        val dock = ScreenBox(4, 4, 324, 572)
        val reserved = LogOverlayLayout.reserveOriginalResultArea(dock, 800, 576, OverlaySide.LEFT, 4)
        assertEquals(ScreenBox(200, 4, 396, 572), reserved)
        val bubble = LogOverlayLayout.place(reserved, 800, 576, OverlaySide.LEFT, 4, 88, 88, -80f, 0f)
        assertEquals(200, bubble.x)
        for (side in OverlaySide.entries) {
            assertEquals(dock, LogOverlayLayout.reserveOriginalResultArea(dock, 576, 800, side, 4))
        }
    }
}
