package com.stroexd.hsdecktracker.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stroexd.hsdecktracker.MainActivity
import com.stroexd.hsdecktracker.R
import com.stroexd.hsdecktracker.appContainer
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.ui.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hosts one log tracker session and its two windows. */
class OverlayService : Service() {
    private var logWindows = emptyList<OverlayWindow>()
    private var logJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_LOGS) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (logJob != null) return START_NOT_STICKY
        startInForeground()
        if (!Settings.canDrawOverlays(this) || !startLogTracking()) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startLogTracking(): Boolean {
        logWindows = OverlaySide.entries.map { side ->
            OverlayWindow(
                context = this,
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                onClose = { stopSelf() },
                side = side,
            )
        }
        try {
            logWindows.forEach { it.show() }
        } catch (failure: RuntimeException) {
            if (failure !is WindowManager.BadTokenException && failure !is SecurityException) throw failure
            destroyLogWindows()
            return false
        }
        appContainer.powerLog.start()
        logJob = serviceScope.launch {
            appContainer.powerLogSource.lines().collect { input ->
                withContext(Dispatchers.Default) { appContainer.powerLog.accept(input) }
            }
        }
        return true
    }

    private fun startInForeground() {
        val text = localized()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, text.getString(R.string.overlay_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TRACKER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracker)
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setContentText(text.getString(R.string.power_log_notification))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, text.getString(R.string.stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (logJob != null) appContainer.powerLog.stop()
        logJob = null
        destroyLogWindows()
        super.onDestroy()
    }

    private fun destroyLogWindows() {
        logWindows.forEach { it.destroy() }
        logWindows = emptyList()
    }

    companion object {
        private const val ACTION_STOP = "com.stroexd.hsdecktracker.STOP_OVERLAY"
        const val ACTION_LOGS = "com.stroexd.hsdecktracker.TRACK_LOGS"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42
    }
}
