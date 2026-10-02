package io.github.ndev.flockeyes.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.ndev.flockeyes.App
import io.github.ndev.flockeyes.MainActivity
import io.github.ndev.flockeyes.R
import io.github.ndev.flockeyes.camera.CameraHost
import io.github.ndev.flockeyes.camera.CameraUse
import io.github.ndev.flockeyes.debug.DebugLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the camera and the AI running for a gate count while the screen is off or another app is open.
 * Android requires a notification while it runs; its Stop button ends the count.
 */
class BackgroundService : LifecycleService() {
    companion object {
        private const val ACTION_STOP = "io.github.ndev.flockeyes.STOP"
        const val NOTE_ID = 7

        /** Whether a gate count is being kept running in the background. */
        val running = MutableStateFlow(false)

        /** Starts background running. Call from the app while it's on screen. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BackgroundService::class.java))
            } catch (e: Exception) {
                DebugLog.error("background", "Couldn't start running in the background", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundService::class.java))
        }

        /** The gate count's use of the camera (one object, so the screen and the service agree it's the same). */
        fun use(app: App): CameraUse = gateUse ?: CameraUse(app.gate, CameraHost.GATE_RES, "gate").also { gateUse = it }

        private var gateUse: CameraUse? = null
    }

    private var wake: PowerManager.WakeLock? = null
    private var ticker: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = App.instance
        if (intent?.action == ACTION_STOP) {
            DebugLog.add("background", "Stopped from the notification")
            app.gate.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTE_ID, notification(),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (e: Exception) {
            DebugLog.error("background", "Android didn't allow running in the background", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true
        app.camera.keepRunning(use(app))
        if (wake == null) {
            // The phone mustn't doze off with the screen off: the camera and the AI need the processor.
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlockEyes:background")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                delay(5000)
                runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification()) }
            }
        }
        DebugLog.add("background", "Counting at the gate in the background")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        App.instance.camera.keepRunning(null)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        running.value = false
        DebugLog.add("background", "Stopped running in the background")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val app = App.instance
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackgroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val u = app.gate.ui.value
        val p = app.prefs
        val secs = if (u.started > 0) (System.currentTimeMillis() - u.started) / 1000 else 0
        val text = "%d:%02d:%02d · %s %d · %s %d".format(secs / 3600, secs / 60 % 60, secs % 60, p.dir1, u.n1, p.dir2, u.n2)
        return NotificationCompat.Builder(this, App.CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_flockeyes)
            .setContentTitle("Counting cows at the gate")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
