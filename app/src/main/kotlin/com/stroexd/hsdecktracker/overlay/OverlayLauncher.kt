package com.stroexd.hsdecktracker.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.powerlog.PowerLogSourceState
import com.stroexd.hsdecktracker.ui.toast

object OverlayLauncher {
    private const val HEARTHSTONE_PACKAGE = "com.blizzard.wtcg.hearthstone"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, OverlayService::class.java))
    }

    fun startLog(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_LOGS))
    }

    fun launchHearthstone(context: Context): Boolean {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(HEARTHSTONE_PACKAGE)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

@Composable
fun rememberPlayAndTrack(onLogSetupRequired: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val openLogSettings by rememberUpdatedState(onLogSetupRequired)
    return {
        val container = context.appContainer
        val active = container.powerLog.state.value.active
        if (!OverlayLauncher.canDrawOverlays(context) ||
            (!active && container.powerLogSource.state.value != PowerLogSourceState.READY)
        ) {
            openLogSettings()
        } else {
            if (!active) OverlayLauncher.startLog(context)
            if (!OverlayLauncher.launchHearthstone(context)) {
                context.toast(context.getString(R.string.hearthstone_not_installed))
            }
        }
    }
}
