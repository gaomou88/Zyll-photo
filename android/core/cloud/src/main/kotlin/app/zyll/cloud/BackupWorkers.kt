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
        // 省电模式下自动备份先不跑，等下次定时或有新照片时再说；手动点的照常
        if (!manual && cloud.isPowerSave()) return Result.success()
        var ok: Boolean
        var uploaded = 0
        var first = true
        do {
            ok = cloud.runBackup({ isStopped }, manual && first) { s -> notify(s) }
            first = false
            uploaded += cloud.status.value.lastRunUploaded
        } while (ok && !isStopped && cloud.consumeRerun())
        // 这次传了照片就马上把备份目录也传上去：旧手机突然丢了，新手机也能看到刚备份的照片
        if (uploaded > 0) cloud.uploadIndexNow()
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
