package ru.wayfinder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.wayfinder.app.data.LogStore
import ru.wayfinder.app.runner.ProbeRunner

/**
 * Фоновый сбор: foreground-сервис с постоянным уведомлением, снимает замер
 * каждые INTERVAL_MS (по умолчанию 3 минуты) и пишет в общий LogStore.
 *
 * Почему foreground: без него Android усыпляет процесс (Doze) и фоновые сокеты
 * не отрабатывают. Уведомление в шторке — плата за то, чтобы сбор шёл всю прогулку.
 */
class SamplerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var store: LogStore
    private lateinit var runner: ProbeRunner

    override fun onCreate() {
        super.onCreate()
        store = LogStore.get(applicationContext)
        runner = ProbeRunner(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Сбор запущен"))
        if (job?.isActive != true) {
            job = scope.launch {
                while (isActive) {
                    try {
                        val snap = runner.runOnce(source = "background")
                        store.add(snap)
                        updateNotification("Замеров: ${store.count()} · ${snap.verdict}")
                    } catch (_: Exception) {
                        updateNotification("Ошибка замера, продолжаю")
                    }
                    delay(INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wayfinder — сбор данных")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Сбор данных Wayfinder", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "wayfinder_sampler"
        private const val NOTIF_ID = 1001
        // Интервал фонового замера. Крутится тут — поменять легко.
        const val INTERVAL_MS = 3 * 60 * 1000L

        fun start(ctx: Context) {
            val i = Intent(ctx, SamplerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SamplerService::class.java))
        }
    }
}
