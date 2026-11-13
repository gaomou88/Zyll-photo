package app.zyll.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zyll.backup.CloudProvider
import org.json.JSONObject
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 分享功能的存储位置（只对 Google 网盘有意义，见 spec/sharing.md 第 7 节）。
 *
 * 切换是马上做的：打开（或关闭）分享功能后，账号立刻改用新位置，新照片直接传到那里；
 * 旧位置里剩下的备份照样能看、能下载（按文件 id 读，和在哪个位置无关），等夜里再搬过去。
 *
 * - [wantVisible]：用户的选择。打开分享功能 = 想放到可见文件夹。
 * - [moving]：夜里已经开始搬旧备份的账号。一旦开始，开关锁住，直到搬完。
 * - [pending]：还没切过去（没联网、缺授权），或者已经切过去、旧备份还在等夜里搬。这时还能改回去。
 * - [needsScope]：要用可见文件夹、但这个账号还没授权 drive.file，需要重新连接一次。
 * - [leftover]：旧位置里还没搬的照片张数。搬完之前这些照片不能放进共享相册。
 */
data class StorageState(
    val wantVisible: Boolean = false,
    val moving: Set<CloudProvider> = emptySet(),
    val pending: Set<CloudProvider> = emptySet(),
    val needsScope: Set<CloudProvider> = emptySet(),
    /** 别的手机挂着“正在搬”、但很久没有进展的账号（这台手机只写目录、不传新照片）。 */
    val staleElsewhere: Set<CloudProvider> = emptySet(),
    /** 这台手机在搬、但读不到旧位置备份目录的账号。 */
    val stuck: Set<CloudProvider> = emptySet(),
    /** 当前搬到第几个文件、一共几个（只在搬的时候有）。 */
    val done: Int = 0,
    val total: Int = 0,
    /** 旧位置里还没搬的照片张数（所有账号加起来）。 */
    val leftover: Int = 0,
) {
    /** 开关能不能改：搬的过程中不能。 */
    val locked: Boolean get() = moving.isNotEmpty()
}

/** 存储位置相关的本机状态。 */
internal class StoragePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud-storage", Context.MODE_PRIVATE)

    var wantVisible: Boolean
        get() = prefs.getBoolean(KEY_WANT, false)
        set(v) { prefs.edit().putBoolean(KEY_WANT, v).apply() }

    fun space(p: CloudProvider): DriveSpace =
        if (prefs.getString(KEY_SPACE + p.name, null) == DriveSpace.Visible.name) DriveSpace.Visible else DriveSpace.Hidden

    fun setSpace(p: CloudProvider, s: DriveSpace) { prefs.edit().putString(KEY_SPACE + p.name, s.name).commit() }

    /** 正在往哪边搬；没在搬为 null。 */
    fun moving(p: CloudProvider): DriveSpace? = prefs.getString(KEY_MOVING + p.name, null)?.let { n -> DriveSpace.entries.firstOrNull { it.name == n } }

    fun setMoving(p: CloudProvider, to: DriveSpace?) {
        prefs.edit().apply { if (to == null) remove(KEY_MOVING + p.name) else putString(KEY_MOVING + p.name, to.name) }.commit()
    }

    /** 开始搬（旧位置挂上标记）的时间。 */
    fun movingSince(p: CloudProvider): Long = prefs.getLong(KEY_SINCE + p.name, 0)

    fun setMovingSince(p: CloudProvider, at: Long) { prefs.edit().putLong(KEY_SINCE + p.name, at).commit() }

    /** 切换到新位置的时间（清理前判断旧位置的固定文件是不是切换后才改的）。 */
    fun switchedAt(p: CloudProvider): Long = prefs.getLong(KEY_SWITCHED + p.name, 0)

    fun setSwitchedAt(p: CloudProvider, at: Long) { prefs.edit().putLong(KEY_SWITCHED + p.name, at).commit() }

    /** 用户选了不再等的那份别的手机的标记（标记时间）；没有为 0。 */
    fun ignoredMarker(p: CloudProvider): Long = prefs.getLong(KEY_IGNORED + p.name, 0)

    fun setIgnoredMarker(p: CloudProvider, at: Long) { prefs.edit().putLong(KEY_IGNORED + p.name, at).commit() }

    /** 旧位置里还没搬的照片张数（切换时数一次，搬完清零）。 */
    fun leftover(p: CloudProvider): Int = prefs.getInt(KEY_LEFTOVER + p.name, 0)

    fun setLeftover(p: CloudProvider, n: Int) { prefs.edit().putInt(KEY_LEFTOVER + p.name, n).commit() }

    /** 已经切换过去、旧位置还有文件要搬（或者正在清理）的账号：旧位置是哪边。 */
    fun cleanup(p: CloudProvider): DriveSpace? = prefs.getString(KEY_CLEANUP + p.name, null)?.let { n -> DriveSpace.entries.firstOrNull { it.name == n } }

    fun setCleanup(p: CloudProvider, from: DriveSpace?) {
        prefs.edit().apply { if (from == null) remove(KEY_CLEANUP + p.name) else putString(KEY_CLEANUP + p.name, from.name) }.commit()
    }

    fun clear(p: CloudProvider) {
        prefs.edit().remove(KEY_SPACE + p.name).remove(KEY_MOVING + p.name).remove(KEY_CLEANUP + p.name).remove(KEY_SINCE + p.name).remove(KEY_IGNORED + p.name).remove(KEY_SWITCHED + p.name).remove(KEY_LEFTOVER + p.name).commit()
    }

    private companion object {
        const val KEY_WANT = "wantVisible"
        const val KEY_SPACE = "space-"
        const val KEY_MOVING = "moving-"
        const val KEY_CLEANUP = "cleanup-"
        const val KEY_SINCE = "since-"
        const val KEY_IGNORED = "ignored-"
        const val KEY_SWITCHED = "switched-"
        const val KEY_LEFTOVER = "leftover-"
    }
}

/**
 * 网盘里存放位置根目录下的 `storage.json`：告诉别的手机这个账号正在搬、或者已经搬走了。
 * 正在搬（[Marker.state] = migrating）时别的手机不往这个账号写；搬走了（moved）就跟着切换过去，从新位置重新读备份目录。
 */
internal object StorageMarker {
    val PATH = listOf("storage.json")

    class Marker(val state: String, val to: DriveSpace, val at: Long, val device: String)

    const val MIGRATING = "migrating"
    const val MOVED = "moved"

    fun encode(m: Marker): ByteArray = JSONObject()
        .put("format", "zyll-storage").put("version", 1)
        .put("state", m.state).put("to", m.to.name.lowercase()).put("at", m.at).put("device", m.device)
        .toString().toByteArray()

    fun decode(bytes: ByteArray): Marker? = runCatching {
        val o = JSONObject(String(bytes))
        if (o.optString("format") != "zyll-storage") return null
        val to = if (o.optString("to") == "visible") DriveSpace.Visible else DriveSpace.Hidden
        Marker(o.getString("state"), to, o.optLong("at"), o.optString("device"))
    }.getOrNull()
}

/** 当地时间 0:00–6:00 才搬。 */
internal object NightWindow {
    private val START: LocalTime = LocalTime.MIDNIGHT
    private val END: LocalTime = LocalTime.of(6, 0)

    fun inWindow(now: LocalTime = LocalTime.now()): Boolean = !now.isBefore(START) && now.isBefore(END)

    /** 距离下一次进入夜间窗口还有多久（已经在窗口里为 0）。 */
    fun delayUntilNext(now: ZonedDateTime = ZonedDateTime.now()): Duration {
        if (inWindow(now.toLocalTime())) return Duration.ZERO
        val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, next)
    }
}

/**
 * 把旧位置剩下的备份搬到新位置的后台任务。
 * - 夜里那份（[schedule]）：夜间、充电、不计流量的网络（Wi‑Fi）、手机空闲，搬全部账号。
 *   条件不满足就一直等，不提醒；搬的过程中任一条件消失（WorkManager 停掉任务、或者过了 6 点）就暂停，下次接着搬。
 * - 马上那份（[scheduleSmall]）：旧位置剩下的照片少于 [SMALL_LIMIT] 张的账号不用等夜里，连上 Wi‑Fi 就搬。
 */
class StorageMigrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cloud = CloudBackup.get(applicationContext)
        val small = inputData.getBoolean(KEY_SMALL, false)
        if (!small && !NightWindow.inWindow()) {
            // 条件凑齐时已经天亮了：排到下一个夜里
            schedule(applicationContext)
            return Result.success()
        }
        var done = false
        try {
            done = cloud.runStorageMigration(onlySmall = small) { isStopped || (!small && !NightWindow.inWindow()) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ZyllStorage", "storage migration failed", e)
        }
        // 还有没搬完的：按剩下的情况重新排（少的连着 Wi‑Fi 就搬、多的夜里）。
        // 白天这一轮没搬完（断了 Wi‑Fi、读写失败、密钥没解锁……）：隔一段再试，不马上重来，免得一直失败一直重跑
        cloud.scheduleMigration(retryLater = small && !done, fromWorker = if (small) CloudBackup.MigrationKind.Small else CloudBackup.MigrationKind.Night)
        return Result.success()
    }

    companion object {
        private const val NAME = "zyll-storage-migration"
        private const val NAME_SMALL = "zyll-storage-migration-small"
        private const val KEY_SMALL = "small"

        /** 旧位置剩下的照片少于这个数时不用等夜里。 */
        const val SMALL_LIMIT = 100

        /** 排一次：等到夜里，并且充电、Wi‑Fi、空闲时才会真正运行。 */
        fun schedule(context: Context, replace: Boolean = true) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
            val request = OneTimeWorkRequestBuilder<StorageMigrationWorker>()
                .setConstraints(c)
                .setInitialDelay(NightWindow.delayUntilNext().toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        /** 排一次：连着 Wi‑Fi 就跑，只搬剩下不多的账号。 */
        fun scheduleSmall(context: Context, retryLater: Boolean = false, replace: Boolean = true) {
            val c = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            val request = OneTimeWorkRequestBuilder<StorageMigrationWorker>()
                .setConstraints(c)
                .setInputData(androidx.work.workDataOf(KEY_SMALL to true))
                .apply { if (retryLater) setInitialDelay(RETRY_MINUTES, TimeUnit.MINUTES) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_SMALL, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        /** 白天搬少量照片没搬完时，隔多久再试。 */
        private const val RETRY_MINUTES = 30L

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        fun cancelSmall(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME_SMALL)
        }
    }
}
