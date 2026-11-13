package app.zyll.feature.gallery

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import app.zyll.media.MediaItem
import kotlinx.coroutines.flow.StateFlow

/** 相册界面要显示的备份状态，由 App 用网盘模块的数据提供。 */
interface BackupIndicator {
    /** 开了自动备份并且连着网盘：这时才在照片上标“未备份”。 */
    val active: StateFlow<Boolean>
    val backedUpIds: StateFlow<Set<Long>>
    val progress: StateFlow<BackupProgress>

    /** 只在网盘里的照片（本机已删除），会和本机照片一起显示在时间线上。 */
    val cloudItems: StateFlow<List<MediaItem>>

    /** 手动备份这些照片：马上上传，不等 Wi‑Fi、不等充电。返回 false 表示没有连接网盘。 */
    fun backup(items: List<MediaItem>): Boolean

    /** 现在用的是按流量计费的网络（移动数据、热点）。手动备份大量照片前用来提醒一次。 */
    fun isMetered(): Boolean = false

    // ---- 分享面板（创建链接、添加到共享相册）。没有网盘模块或还没接上时用默认值：按钮变灰 ----

    /** 现在不能创建分享链接的原因（没连网盘、没开加密、照片还没备份等）；null 表示可以。 */
    fun shareLinkUnavailableReason(items: List<MediaItem>): String? = ""

    /** 用这些照片创建一条带密码的分享链接。失败时抛出带说明的异常。 */
    suspend fun createShareLink(
        items: List<MediaItem>, title: String, password: String, expiresAt: Long?, allowOriginals: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): app.zyll.feature.gallery.ui.ShareLinkInfo =
        throw UnsupportedOperationException()

    /** [reason]（[shareLinkUnavailableReason] 给的）是不是只因为照片还没备份：是的话备份完就能建。 */
    fun shareLinkNeedsBackup(reason: String): Boolean = false

    /** 创建链接前预先看一下：这些照片里有几张能放进链接、几张没备份、几张还在等搬。null = 没法预先检查。 */
    suspend fun checkShareLink(items: List<MediaItem>): app.zyll.feature.gallery.ui.ShareLinkCheck? = null

    /** 可以往里加照片的共享相册（自己建的、或有“可管理”权限的）。 */
    fun shareTargets(): List<app.zyll.feature.gallery.ui.ShareTarget> = emptyList()

    /** 把照片加进共享相册，返回加进去的张数（没备份到那个网盘的照片加不进去）。 */
    suspend fun addToSharedAlbum(target: app.zyll.feature.gallery.ui.ShareTarget, items: List<MediaItem>): Int = 0

    /** 这些照片里已经完整备份、可以放心从本机删掉的。 */
    fun safelyBackedUp(items: List<MediaItem>): List<MediaItem>

    /** 真要从本机删之前，逐张去网盘确认备份还在（[onProgress] 报告已核对 / 总数）。 */
    suspend fun verifyForFree(
        items: List<MediaItem>,
        onProgress: (Int, Int) -> Unit,
        onChecked: (MediaItem, Int) -> Unit = { _, _ -> },
    ): app.zyll.feature.gallery.ui.FreeCheckResult

    /** 下载回手机相册，返回成功数。 */
    suspend fun restore(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int

    /** 从网盘里永久删除，返回成功数。 */
    suspend fun deleteFromCloud(items: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int

    /** 修改网盘照片的名称 / 时间 / 位置（会重新上传）。 */
    suspend fun editCloud(item: MediaItem, name: String?, takenAt: Long?, location: Pair<Double, Double>?, clearLocation: Boolean)

    /** 设为“不备份”的相簿。 */
    val skippedAlbums: StateFlow<Set<Long>>

    fun setAlbumSynced(bucketId: Long, synced: Boolean)
}

data class BackupProgress(
    val running: Boolean = false,
    /** 还剩多少项。 */
    val remaining: Int = 0,
    /** 0~1；不知道总数时为 null（转圈）。 */
    val fraction: Float? = null,
    /** 顶部横幅上的文字，比如“正在备份 3 项”。 */
    val label: String? = null,
    /** 需要用户处理的问题，比如“网盘空间不足”。 */
    val problem: String? = null,
)

/**
 * 每张照片的标记（是否打码、是否已备份）。传的是 State 本身而不是值：
 * 每个格子用 derivedStateOf 只盯自己那一张，备份进度变化时不会让整个网格重组。
 */
class MediaFlags(
    val hidden: State<Set<Long>>,
    val backedUp: State<Set<Long>>,
    val showBackup: State<Boolean>,
    val onReveal: (Long) -> Unit,
    val onPause: () -> Unit,
    val onDisable: () -> Unit,
    /** 离开大图查看器时调用：清掉“仍要查看”的临时放行。 */
    val onLeaveViewer: () -> Unit = {},
    /** 动态照片（含 iPhone 实况照片）：格子上打标记。 */
    val motion: State<Set<Long>>? = null,
    /** iPhone 实况照片配对的 MOV。 */
    val livePair: (Long) -> MediaItem? = { null },
)

val LocalMediaFlags = staticCompositionLocalOf<MediaFlags?> { null }
