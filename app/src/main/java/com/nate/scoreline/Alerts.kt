package com.nate.scoreline

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Alerts {
    const val CHANNEL = "scores"
    private const val WORK = "score-alerts"
    private const val STATE_PREFS = "alert_state"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Game alerts", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Kickoffs, scoring changes and finals for your favorite teams; F1 results"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /**
     * Android's minimum interval for periodic background work is 15 minutes, and Doze
     * can stretch it further while the phone is idle. Alerts are therefore "close to live",
     * not play-by-play. The in-app screens refresh every 30 s while you're watching.
     */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun statePrefs(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    internal fun post(context: Context, key: String, alert: AlertLogic.Alert) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            // Same id per game, so a newer update replaces the older one instead of stacking.
            NotificationManagerCompat.from(context).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }
}

class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val fav = Favorites.get(ctx)
        if (!fav.alertsEnabled) return Result.success()
        val store = Alerts.statePrefs(ctx)
        val edit = store.edit()
        val seen = mutableSetOf<String>()
        val failed = mutableSetOf<String>() // key prefixes whose fetch failed: keep their state

        for (league in League.entries) {
            val ids = fav.favTeamIds(league)
            if (ids.isEmpty()) continue
            val board = try {
                Espn.scoreboard(league)
            } catch (e: Exception) {
                failed += "g:${league.name}:"
                continue
            }
            for (g in board.games) {
                if (g.home.id !in ids && g.away.id !in ids) continue
                val key = "g:${league.name}:${g.id}"
                seen += key
                AlertLogic.evaluate(store.getString(key, null), g, fav.scoreAlerts)?.let { Alerts.post(ctx, key, it) }
                edit.putString(key, AlertLogic.snapshot(g))
            }
        }

        if (fav.f1Alerts) {
            try {
                for (w in F1.weekends()) for (s in w.sessions) {
                    val key = "f1:${w.id}:${s.id}"
                    seen += key
                    AlertLogic.f1Alert(store.getString(key, null), w.name, s)?.let { Alerts.post(ctx, key, it) }
                    edit.putString(key, s.state)
                }
            } catch (_: Exception) {
                failed += "f1:"
            }
        }

        // Forget games that are no longer on the current scoreboards, so the store stays small.
        // Skip sources that failed this run, or a network blip would erase state and miss a final.
        store.all.keys
            .filter { k -> k !in seen && failed.none { k.startsWith(it) } }
            .forEach { edit.remove(it) }
        edit.apply()
        return Result.success()
    }
}
