package fr.halo720.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.halo720.app.HaloApp
import fr.halo720.app.MainActivity
import fr.halo720.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Service de premier plan : garde l'app vivante pendant la copie des RAW (écran éteint, app
 * fermée), avec la notification « HALO 720 · 3 RAW en cours ». S'arrête seul quand la file est vide.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HaloApp
        val n = build(this, app.sync.state.value)
        try {
            ServiceCompat.startForeground(this, ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (job == null) job = scope.launch {
            var idle = 0
            while (true) {
                delay(1500)
                val st = app.sync.state.value
                getSystemService(NotificationManager::class.java).notify(ID, build(this@SyncService, st))
                idle = if (st.busy && app.sync.autoSave) 0 else idle + 1
                if (idle >= 4) { running = false; stopSelf(); break }
            }
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 72
        private const val CHANNEL = "sync"
        @Volatile var running = false

        fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Copie des photos", NotificationManager.IMPORTANCE_LOW))
        }

        fun build(ctx: Context, st: SyncState): Notification {
            val txt = when {
                st.phoneFull -> "Téléphone plein · copie arrêtée"
                st.rawPending > 0 -> "${st.rawPending} RAW en cours" + (st.etaS?.let { " · ≈ ${fmtEta(it)}" } ?: "")
                st.jpgPending > 0 -> "${st.jpgPending} JPEG en cours"
                else -> "Copie terminée"
            }
            val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_halo)
                .setContentTitle("HALO 720 · $txt")
                .setOngoing(st.busy)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(pi)
                .apply { if (st.busy && st.progress > 0f) setProgress(100, (st.progress * 100).toInt(), false) }
                .build()
        }

        fun fmtEta(s: Int) = if (s < 60) "$s s" else "${(s + 30) / 60} min"

        fun ensure(ctx: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
                running = true
            } catch (_: Exception) {
                // démarrage refusé en arrière-plan : la copie continue tant que l'app est ouverte
            }
        }

        fun update(ctx: Context, st: SyncState) {}
    }
}
