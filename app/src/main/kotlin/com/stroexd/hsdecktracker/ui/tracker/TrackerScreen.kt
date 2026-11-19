@file:OptIn(ExperimentalMaterial3Api::class)

package com.stroexd.hsdecktracker.ui.tracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.overlay.OverlayLauncher
import com.stroexd.hsdecktracker.overlay.rememberPlayAndTrack
import com.stroexd.hsdecktracker.powerlog.PowerLogSourceState
import com.stroexd.hsdecktracker.ui.LocalAppContainer
import com.stroexd.hsdecktracker.ui.Routes

@Composable
fun TrackerScreen(navController: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val cardState by container.cards.state.collectAsStateWithLifecycle()
    val powerLog by container.powerLog.state.collectAsStateWithLifecycle()
    val logSource by container.powerLogSource.state.collectAsStateWithLifecycle()
    val startTracking = rememberPlayAndTrack {
        if (!OverlayLauncher.canDrawOverlays(context)) OverlayLauncher.requestOverlayPermission(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tracker)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.PREDICTION) }) {
                        Icon(Icons.Filled.Analytics, contentDescription = stringResource(R.string.device_prediction))
                    }
                    if (powerLog.active || logSource == PowerLogSourceState.READY) {
                        IconButton(onClick = startTracking) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.open_hearthstone))
                        }
                    }
                    if (powerLog.active) {
                        IconButton(onClick = { OverlayLauncher.stop(context) }) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.stop_tracker))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val match = powerLog.snapshot
            when {
                !powerLog.active -> PowerLogStartControl(Modifier.fillMaxWidth().padding(16.dp))
                match == null -> PowerLogWaiting(logSource)
                else -> PowerLogPanel(match, cardState.db, compact = false, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
