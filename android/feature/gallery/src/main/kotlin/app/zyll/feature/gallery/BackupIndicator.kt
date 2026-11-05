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

    /** 手动备份这些照片。返回 false 表示没有连接网盘。 */
    fun backup(items: List<MediaItem>): Boolean

    /** 这些照片里已经完整备份、可以放心从本机删掉的。 */
    fun safelyBackedUp(items: List<MediaItem>): List<MediaItem>

    /** 下载回手机相册，返回成功数。 */
    suspend fun restore(items: List<MediaItem>): Int

    /** 从网盘里永久删除，返回成功数。 */
    suspend fun deleteFromCloud(items: List<MediaItem>): Int

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
)

val LocalMediaFlags = staticCompositionLocalOf<MediaFlags?> { null }
