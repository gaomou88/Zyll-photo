package app.zyll.gallery

import android.net.Uri
import app.zyll.cloud.BackupPhase
import app.zyll.cloud.CloudBackup
import app.zyll.cloud.CloudEntry
import app.zyll.cloud.CloudSettingsExtras
import app.zyll.feature.gallery.BackupIndicator
import app.zyll.feature.gallery.BackupProgress
import app.zyll.feature.gallery.GalleryController
import app.zyll.feature.gallery.ui.CloudFiles
import app.zyll.media.CLOUD_SCHEME
import app.zyll.media.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import app.zyll.backup.AlbumRule
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import app.zyll.media.tr
import app.zyll.gallery.R

/** 把网盘模块的状态翻译成相册界面要的样子。 */
class CloudBackupIndicator(
    private val cloud: CloudBackup,
    private val controller: GalleryController,
    private val scope: CoroutineScope,
) : BackupIndicator, CloudFiles {
    /** 网盘照片的 uri → 对应的备份。 */
    private val byUri = ConcurrentHashMap<String, CloudEntry>()

    override val active: StateFlow<Boolean> = combine(cloud.settings, cloud.driveStates) { s, d ->
        s.enabled && d.any { it.signedIn }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** 有备份记录、但备份之后照片又被改过（修改时间或大小对不上）的本机照片：算作没备份。 */
    private val staleIds: StateFlow<Set<Long>> = combine(cloud.entries, controller.localItems) { entries, local ->
        if (local == null) return@combine emptySet<Long>()
        val byMedia = entries.groupBy { it.mediaId }
        local.filterTo(ArrayList()) { i ->
            val es = byMedia[i.id] ?: return@filterTo false
            es.none { it.modified == i.modifiedAt && it.size == i.sizeBytes }
        }.mapTo(HashSet()) { it.id }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptySet())

    private fun current(ids: Set<Long>, stale: Set<Long>) = if (stale.isEmpty()) ids else ids - stale

    /**
     * 全 App 统一的“已备份”：有备份记录，而且修改时间、大小都和本机一致。
     * 备份进行中每传完一张就会变，这里最多约每秒推送一次，免得界面跟着一直重算。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override val backedUpIds: StateFlow<Set<Long>> = combine(cloud.backedUpIds, staleIds, ::current)
        .sample(800)
        .stateIn(scope, SharingStarted.Eagerly, current(cloud.backedUpIds.value, staleIds.value))

    override val progress: StateFlow<BackupProgress> =
        combine(cloud.status, cloud.driveStates, cloud.settings) { st, drives, s ->
            val reconnect = s.enabled && drives.any { it.needsReconnect } && drives.none { it.signedIn }
            val remaining = (st.total - st.done).coerceAtLeast(0)
            val checking = st.running && st.phase == BackupPhase.Checking && st.manual
            val busy = (st.running && st.phase != BackupPhase.Checking && st.total > 0) || checking
            BackupProgress(
                running = busy,
                remaining = remaining,
                fraction = if (busy && st.total > 0) st.done.toFloat() / st.total else null,
                label = when {
                    !busy -> null
                    checking -> tr(R.string.bridge_checking)
                    st.phase == BackupPhase.Encrypting -> tr(R.string.bridge_encrypting, remaining)
                    st.phase == BackupPhase.Decrypting -> tr(R.string.bridge_decrypting, remaining)
                    else -> tr(R.string.bridge_backing_up, remaining)
                },
                problem = when {
                    reconnect -> tr(R.string.bridge_reconnect)
                    // 关掉自动备份后，旧的错误提示不再挂着；手动点的那次照样提示
                    !st.running && (s.enabled || st.manual) -> st.message
                    else -> null
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, BackupProgress())

    /**
     * 本机已经没有、但网盘里还有的照片。判断“本机还有”看三样：备份时的 MediaStore id 还在，
     * 或者同一个网盘文件被本机别的照片引用着，或者本机有内容完全相同的照片。
     */
    override val cloudItems: StateFlow<List<MediaItem>> = combine(cloud.entries, controller.localItems) { entries, local ->
        if (local == null) return@combine emptyList<MediaItem>()
        val localIds = local.mapTo(HashSet()) { it.id }
        val linked = entries.filter { it.mediaId in localIds }
        val linkedRemote = linked.mapTo(HashSet()) { it.key }
        val linkedHash = linked.mapNotNullTo(HashSet()) { it.hash.ifEmpty { null } }
        entries.asSequence()
            .filter { it.mediaId !in localIds && it.key !in linkedRemote && (it.hash.isEmpty() || it.hash !in linkedHash) }
            .distinctBy { it.hash.ifEmpty { it.key } }
            .map(::toItem)
            .sortedByDescending { it.takenAt }
            .toList()
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun toItem(e: CloudEntry): MediaItem {
        val uri = Uri.Builder().scheme(CLOUD_SCHEME).authority(e.provider.name).appendPath(e.remoteId).build()
        byUri[uri.toString()] = e
        return MediaItem(
            id = if (e.mediaId > 0) -e.mediaId else e.mediaId,
            uri = uri,
            isVideo = e.isVideo,
            takenAt = e.takenAt,
            modifiedAt = e.takenAt,
            width = 0,
            height = 0,
            durationMs = 0,
            mimeType = e.mime,
            displayName = e.name,
            bucketId = 0,
            bucketName = e.album,
            sizeBytes = e.size,
            isFavorite = false,
            relativePath = "",
        )
    }

    private fun entryOf(item: MediaItem) = byUri[item.uri.toString()]

    override suspend fun file(uri: Uri, thumb: Boolean): File {
        val e = byUri[uri.toString()] ?: throw IOException(tr(R.string.cloud_photo_missing))
        return if (thumb) cloud.thumbnail(e) else cloud.original(e)
    }

    override fun backup(items: List<MediaItem>): Boolean {
        if (cloud.driveStates.value.none { it.signedIn }) return false
        cloud.backupItems(items.map { it.id })
        return true
    }

    override fun safelyBackedUp(items: List<MediaItem>): List<MediaItem> {
        // 和格子上的“已备份”标记同一个口径（用最新值，不等节流）
        val ok = current(cloud.backedUpIds.value, staleIds.value)
        return items.filter { it.id in ok }
    }

    override suspend fun restore(items: List<MediaItem>): Int = cloud.restore(items.mapNotNull(::entryOf), controller.repo)

    override suspend fun deleteFromCloud(items: List<MediaItem>): Int = cloud.deleteFromCloud(items.mapNotNull(::entryOf))

    override suspend fun editCloud(item: MediaItem, name: String?, takenAt: Long?, location: Pair<Double, Double>?, clearLocation: Boolean) {
        val e = entryOf(item) ?: throw IOException(tr(R.string.cloud_photo_missing))
        cloud.editCloud(e, name, takenAt, location, clearLocation)
    }

    override val skippedAlbums: StateFlow<Set<Long>> = cloud.settings
        .map { s -> s.albumRules.filterValues { it == AlbumRule.Skip }.keys }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun setAlbumSynced(bucketId: Long, synced: Boolean) = cloud.setAlbumSynced(bucketId, synced)

    init {
        // 新出现的相簿按“新相簿自动备份”的设置处理
        scope.launch(Dispatchers.Default) {
            controller.localItems.collect { list ->
                list?.let {
                    cloud.registerAlbums(it.map { i -> i.bucketId }.distinct())
                    // 换手机后从网盘恢复出来的记录，配上这台手机里同一张照片
                    cloud.linkRestoredLater(it)
                }
            }
        }
        // 相册的设置（归档、截图自动归档、快捷入口）跟着备份目录一起存到网盘，换手机能恢复
        cloud.extras = object : CloudSettingsExtras {
            override fun export() = controller.exportSettings()
            override fun import(o: org.json.JSONObject) = controller.importSettings(o)
        }
        // 端到端加密开着时，AI 整理结果也加密跟着网盘走，换手机不用重新整理
        cloud.aiResults = AiResultsBridge(controller.ai)
    }
}
