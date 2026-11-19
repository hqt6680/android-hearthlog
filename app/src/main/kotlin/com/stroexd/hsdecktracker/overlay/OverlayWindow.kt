package com.stroexd.hsdecktracker.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.vision.LogOverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.core.vision.ScreenBox
import com.stroexd.hsdecktracker.ui.ProvideAppLocale
import com.stroexd.hsdecktracker.ui.theme.HsTheme

/** Positions a tracker window beside the board and owns its Compose lifecycle. */
class OverlayWindow(
    private val context: Context,
    windowType: Int,
    private val onClose: () -> Unit,
    private val side: OverlaySide,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        // Absolute screen coordinates, including the area around the camera
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private var collapsed = false
    private var panelSize by mutableStateOf(DpSize.Zero)
    private var horizontalOffset = 0f
    private var verticalOffset = 0f

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = place()
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun show() {
        view?.let {
            it.visibility = View.VISIBLE
            place()
            return
        }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setContent {
                val locale by context.appContainer.appLocale.collectAsStateWithLifecycle()
                ProvideAppLocale(locale) {
                    HsTheme {
                        PowerLogOverlayContent(
                            side = side,
                            panelSize = panelSize,
                            onDrag = ::moveBy,
                            onCollapsedChange = ::updateCollapsed,
                            onClose = onClose,
                        )
                    }
                }
            }
        }
        view = composeView
        place()
        windowManager.addView(composeView, params)
        displayManager.registerDisplayListener(rotationListener, Handler(Looper.getMainLooper()))
    }

    fun destroy() {
        displayManager.unregisterDisplayListener(rotationListener)
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun place(dragging: Boolean = false) {
        val (width, height) = realDisplaySize()
        val (cornerRadius, cutouts) = screenEdges()
        val gap = px(GAP_DP)
        val bubbleWidth = px(BUBBLE_SIZE.value)
        val maxWidth = minOf(px(MAX_LOG_WIDTH_DP), (width / 2 - gap).coerceAtLeast(1))
        val minWidth = minOf(px(MIN_LOG_WIDTH_DP), maxWidth)
        val panel = OverlayLayout.sidePanel(
            width,
            height,
            side,
            minWidth = minWidth,
            maxWidth = maxWidth,
            gap = gap,
            cornerRadius = cornerRadius,
            cutouts = cutouts,
        )
        val logPlacement = LogOverlayLayout.place(
            panel = panel,
            screenWidth = width,
            screenHeight = height,
            side = side,
            gap = gap,
            collapsedWidth = bubbleWidth.takeIf { collapsed },
            minimumHeight = if (collapsed) bubbleWidth else px(MIN_DRAG_HEIGHT_DP),
            offsetX = horizontalOffset,
            offsetY = verticalOffset,
            cornerRadius = cornerRadius,
            cutouts = cutouts,
        )
        if (dragging) {
            horizontalOffset = logPlacement.offsetX
            verticalOffset = logPlacement.offsetY
        }
        val x = logPlacement.x
        val y = logPlacement.y
        panelSize = DpSize(
            (logPlacement.panelWidth / density).dp,
            (logPlacement.panelHeight / density).dp,
        )
        // Display changes also fire for refresh rate switches, so only move when something changed
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            view?.takeIf { it.isAttachedToWindow }?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun updateCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        place()
    }

    private fun screenEdges(): Pair<Int, List<ScreenBox>> {
        if (Build.VERSION.SDK_INT < 30) return 0 to emptyList()
        val insets = windowManager.maximumWindowMetrics.windowInsets
        val cutouts = insets.displayCutout?.boundingRects.orEmpty().map { ScreenBox(it.left, it.top, it.right, it.bottom) }
        val cornerRadius = if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT,
                RoundedCorner.POSITION_BOTTOM_RIGHT,
            ).maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
        } else {
            0
        }
        return cornerRadius to cutouts
    }

    private fun realDisplaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        if (view == null) return
        horizontalOffset += dx
        verticalOffset += dy
        place(dragging = true)
    }

    private fun px(dp: Float): Int = (dp * density).toInt()

    private companion object {
        const val MIN_LOG_WIDTH_DP = 120f
        const val MAX_LOG_WIDTH_DP = 224f
        const val MIN_DRAG_HEIGHT_DP = 132f
        const val GAP_DP = 4f
    }
}
