package com.tom.pharmascan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Rappel de péremption, une fois par jour, entièrement local : relit
 * l'historique et notifie les boîtes qui entrent dans la fenêtre « bientôt
 * périmée » ou qui viennent de périmer (voir [expiryNotice]). Rien n'est
 * envoyé nulle part.
 *
 * Une boîte n'est marquée « signalée » qu'une fois la notification
 * affichée : si les notifications sont refusées, le rappel attend qu'elles
 * soient permises.
 */
class ExpiryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.expiryRemindersEnabled) return Result.success()
        val entries = prefs.historyEntries()
        val notified = keepNotifiedKeys(entries, prefs.notifiedExpiryKeys)
        val notice = expiryNotice(
            entries,
            isoDate(Calendar.getInstance()),
            isoDate(Calendar.getInstance().apply { add(Calendar.MONTH, prefs.alertMonths) }),
            notified
        )
        if (notice.isEmpty) {
            prefs.notifiedExpiryKeys = notified
            return Result.success()
        }
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return Result.success()

        createChannel(applicationContext)
        val (title, text) = expiryNotificationText(notice)
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission retirée entre la vérification et l'envoi : on réessaiera demain.
            return Result.success()
        }
        prefs.notifiedExpiryKeys = notified + notice.keys
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "expiry_reminder"
        private const val CHANNEL_ID = "expiry"
        private const val NOTIFICATION_ID = 1

        /** À chaque lancement de l'appli : KEEP ne replanifie pas un rappel déjà en place. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, "Péremptions", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Boîtes de l'historique bientôt périmées ou périmées" }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        private fun isoDate(cal: Calendar) = String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
