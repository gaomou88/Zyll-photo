package app.zyll.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.zyll.media.tr
import app.zyll.core.cloud.R

/** 备份任务：真正的逻辑在 [CloudBackup.runBackup]，这里负责通知栏进度。 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var lastNotify = 0L
    private var foregroundFailed = false

    override suspend fun doWork(): Result {
        val cloud = CloudBackup.get(applicationContext)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val picked = inputData.getBoolean(KEY_PICKED, false)
        // 省电模式下自动备份先不跑，等下次定时或有新照片时再说；手动点的照常
        if (!manual && cloud.isPowerSave()) return Result.success()
        var ok = false
        var uploaded = 0
        var first = true
        try {
            do {
                ok = cloud.runBackup({ isStopped }, manual && first, picked) { s -> notify(s) }
                first = false
                uploaded += cloud.status.value.lastRunUploaded
                // 设置改了、或者条件没了：不在这个任务里接着跑（它的网络、充电条件是旧的），结束后按现在的设置重新排
            } while (ok && !isStopped && !cloud.pendingRequeue() && cloud.consumeRerun())
            // 这次传了照片就马上把备份目录也传上去：旧手机突然丢了，新手机也能看到刚备份的照片
            if (uploaded > 0) cloud.uploadIndexNow()
            cloud.requeueIfNeeded()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没料到的错误不让整个任务崩掉（崩了 WorkManager 会把它当失败，不再重试）：稍后再试
            android.util.Log.w("ZyllBackup", "backup failed", e)
            return Result.retry()
        }
        if (ok && uploaded > 0) notifyFinished(applicationContext, uploaded, cloud.status.value.backedUp)
        return if (ok) Result.success() else Result.retry()
    }

    private suspend fun notify(s: BackupStatus) {
        if (foregroundFailed || s.total == 0) return
        if (s.phase != BackupPhase.Uploading && s.phase != BackupPhase.Encrypting && s.phase != BackupPhase.Decrypting) return
        val now = SystemClock.uptimeMillis()
        if (now - lastNotify < 1000 && s.done != s.total) return
        lastNotify = now
        try {
            // 前台任务：上传大量照片时不会被系统中途杀掉，通知栏里能看到进度
            setForeground(foregroundInfo(applicationContext, s))
        } catch (_: Exception) {
            // 后台启动前台服务被系统拒绝（Android 12+ 的限制）时，照常在后台跑
            foregroundFailed = true
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext, CloudBackup.get(applicationContext).status.value)

    companion object {
        const val KEY_MANUAL = "manual"
        /** 用户手动选了几张照片要备份（[CloudBackup.backupItems]）：只有那几张不看仅 Wi‑Fi / 仅充电。 */
        const val KEY_PICKED = "picked"
    }
}

/**
 * 改了密码或换了恢复密钥以后，有账号当时没传上新的密钥包：联网后补传，失败了隔一段再试。
 * 和自动备份开没开无关：网盘里留着旧的密钥包时，旧密码和旧恢复密钥在那里仍然能用。
 */
class KeySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        // 一直做不成（比如某个账号的目录解不开）时不要在任意网络下无限重试：试几次就交给下一轮备份
        if (CloudBackup.get(applicationContext).syncKeyBundle() || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ZyllBackup", "keybundle sync failed", e)
        if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}

/** 外链到期时撤销；撤销到一半的接着撤（spec/share-link.md 第 4 节）。 */
class ShareLinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        // sweep 会按剩下的链接重新排下一次
        CloudBackup.get(applicationContext).shareLinks.sweep()
        Result.success()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ZyllLinks", "share link sweep failed", e)
        Result.retry()
    }

    companion object {
        private const val NAME = "zyll-share-links"

        /** 排到 [at]（毫秒时间戳）联网后运行；null = 没有要到期的，取消。 */
        fun schedule(context: Context, at: Long?) {
            val wm = androidx.work.WorkManager.getInstance(context)
            if (at == null) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            val request = androidx.work.OneTimeWorkRequestBuilder<ShareLinkWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** 系统媒体库有变化时被唤醒：安排一轮备份，再重新等待下一次变化。 */
class NewMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = CloudBackup.get(applicationContext)
        if (cloud.settings.value.enabled) cloud.backupNow()
        cloud.armNewMediaTrigger(ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

private const val CHANNEL = "backup"
private const val CHANNEL_DONE = "backup-done"
private const val NOTIFICATION_ID = 4101
private const val NOTIFICATION_DONE_ID = 4102

/** 备份完成：一条安静的通知，带一点小彩蛋；十分钟后自己消失。 */
internal fun notifyFinished(context: Context, uploaded: Int, total: Int) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) return
    if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, tr(R.string.notif_channel_done), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr(R.string.notif_channel_done_desc)
            setShowBadge(false)
        })
    }
    val milestone = listOf(100_000, 50_000, 20_000, 10_000, 5_000, 2_000, 1_000, 500, 100).firstOrNull { total >= it && total - uploaded < it }
    val title = if (milestone != null) tr(R.string.notif_milestone, milestone) else tr(R.string.notif_done)
    val lines = listOf(
        tr(R.string.notif_line1, uploaded),
        tr(R.string.notif_line2, uploaded),
        tr(R.string.notif_line3, uploaded),
        tr(R.string.notif_line4, uploaded),
    )
    val text = if (milestone != null) tr(R.string.notif_milestone_body, total, uploaded) else lines[(System.currentTimeMillis() / 1000 % lines.size).toInt()]
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
    }
    val n = NotificationCompat.Builder(context, CHANNEL_DONE)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setSilent(true)
        .setTimeoutAfter(10 * 60_000L)
        .setContentIntent(open)
        .build()
    try {
        nm.notify(NOTIFICATION_DONE_ID, n)
    } catch (_: SecurityException) {
        // 没有通知权限
    }
}

private fun foregroundInfo(context: Context, s: BackupStatus): ForegroundInfo {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.settings_cloud_backup), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr(R.string.notif_channel_progress_desc)
            setShowBadge(false)
        })
    }
    val title = when (s.phase) {
        BackupPhase.Encrypting -> tr(R.string.notif_encrypting)
        BackupPhase.Decrypting -> tr(R.string.notif_decrypting)
        else -> tr(R.string.notif_backing_up_n, (s.total - s.done).coerceAtLeast(0))
    }
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title)
        .setContentText(s.current ?: "${s.done} / ${s.total}")
        .setProgress(s.total.coerceAtLeast(1), s.done, s.total == 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()
    return if (Build.VERSION.SDK_INT >= 29) {
        ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(NOTIFICATION_ID, n)
    }
}
