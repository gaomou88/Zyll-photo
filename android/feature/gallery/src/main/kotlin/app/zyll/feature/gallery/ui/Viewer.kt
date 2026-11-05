package app.zyll.feature.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.CircularProgressIndicator
import app.zyll.media.isCloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.zyll.feature.gallery.LocalMediaFlags
import app.zyll.feature.gallery.MediaFlags
import app.zyll.media.MediaDetails
import app.zyll.media.MediaItem
import app.zyll.media.MediaRepository
import coil3.compose.AsyncImage
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/** 大图查看器里能做的操作，由外层决定具体怎么执行。 */
class ViewerActions(
    val onShare: (MediaItem) -> Unit,
    val onToggleFavorite: ((MediaItem) -> Unit)?,
    val onEdit: ((MediaItem) -> Unit)?,
    val onTrash: ((MediaItem) -> Unit)?,
    val onRestore: ((MediaItem) -> Unit)? = null,
    val onDeleteForever: ((MediaItem) -> Unit)? = null,
    /** 只在网盘里的照片：下载回手机 / 从网盘删除。 */
    val onRestoreFromCloud: ((MediaItem) -> Unit)? = null,
    val onDeleteFromCloud: ((MediaItem) -> Unit)? = null,
    /** 改名称、拍摄时间、位置。 */
    val onEditInfo: ((MediaItem) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startId: Long,
    repo: MediaRepository,
    actions: ViewerActions,
    inTrash: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // 实际显示的列表：删掉当前这张时先滑到相邻那张再更新；全部删完时保留最后一张，关闭动画才有内容可退
    var shown by remember { mutableStateOf(items) }
    if (items.isEmpty()) LaunchedEffect(Unit) { onClose() }
    if (shown.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val start = remember(shown, startId) { shown.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = start) { shown.size }
    LaunchedEffect(items) {
        if (items.isEmpty() || items === shown) return@LaunchedEffect
        val page = pager.currentPage
        val cur = shown.getOrNull(page)
        if (cur != null && items.none { it.id == cur.id } && shown.size > 1) {
            pager.animateScrollToPage(if (page < shown.size - 1) page + 1 else page - 1)
        }
        // 换列表后停在同一张照片上（前面的照片被删了，位置会往前挪）
        val keep = shown.getOrNull(pager.currentPage)?.id
        shown = items
        val idx = items.indexOfFirst { it.id == keep }
        if (idx >= 0 && idx != pager.currentPage) pager.scrollToPage(idx)
    }
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    val current = shown.getOrNull(pager.currentPage) ?: shown.last()
    val flags = LocalMediaFlags.current
    // 退出大图就清掉临时放行，返回网格和下次打开都会重新打码
    // 用 Unit 作 key：flags 因为别的状态（比如网盘刚登录）重建时不该触发清除，
    // 否则用户还在看大图就被重新打码。只在真正离开大图时清。
    val latestFlags = rememberUpdatedState(flags)
    DisposableEffect(Unit) { onDispose { latestFlags.value?.onLeaveViewer?.invoke() } }
    val hiddenIds = flags?.hidden?.value ?: emptySet()
    val showBackup = flags?.showBackup?.value == true
    val backedUp = current.isCloud || (showBackup && current.id in (flags?.backedUp?.value ?: emptySet()))
    val busy = LocalScrollBusy.current
    if (busy != null) {
        LaunchedEffect(pager, busy) { snapshotFlow { pager.isScrollInProgress }.collect { busy.value = it } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { shown[it].id }, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val item = shown[page]
            if (item.id in hiddenIds && flags != null) {
                SensitiveCover(item, shared = page == pager.currentPage, flags = flags)
            } else if (item.isVideo) {
                VideoPage(item, isCurrent = page == pager.currentPage, onTap = { chrome = !chrome })
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // 原图解码出来之前先垫一张缩略图；它也是打开 / 返回动画里飞来飞去的那张
                    MediaBackdrop(item, shared = page == pager.currentPage)
                    ZoomableAsyncImage(
                        model = fullModel(item),
                        contentDescription = item.displayName,
                        state = rememberZoomableImageState(),
                        modifier = Modifier.fillMaxSize(),
                        onClick = { chrome = !chrome },
                    )
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(formatTaken(current.takenAt), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${pager.currentPage + 1} / ${shown.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                if (showBackup || current.isCloud) {
                    Icon(
                        if (backedUp) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                        if (backedUp) tr(R.string.backup_bar_done) else tr(R.string.viewer_waiting_backup),
                        tint = Color.White.copy(alpha = if (backedUp) 1f else 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp).size(22.dp),
                    )
                }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, tr(R.string.viewer_details), tint = Color.White) }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val canEdit = (actions.onEdit != null && !current.isVideo) || actions.onEditInfo != null
                if (current.isCloud) {
                    ViewerAction(Icons.Outlined.Share, tr(R.string.action_share)) { actions.onShare(current) }
                    if (canEdit) ViewerAction(Icons.Outlined.Tune, tr(R.string.viewer_edit)) { showEdit = true }
                    actions.onRestoreFromCloud?.let { ViewerAction(Icons.Outlined.Download, tr(R.string.action_restore_to_phone)) { it(current) } }
                    actions.onDeleteFromCloud?.let { ViewerAction(Icons.Outlined.DeleteOutline, tr(R.string.common_delete)) { it(current) } }
                } else if (inTrash) {
                    actions.onRestore?.let { ViewerAction(Icons.Outlined.RestoreFromTrash, tr(R.string.action_restore)) { it(current) } }
                    actions.onDeleteForever?.let { ViewerAction(Icons.Outlined.DeleteForever, tr(R.string.action_delete_forever)) { it(current) } }
                } else {
                    ViewerAction(Icons.Outlined.Share, tr(R.string.action_share)) { actions.onShare(current) }
                    actions.onToggleFavorite?.let {
                        ViewerAction(if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (current.isFavorite) tr(R.string.viewer_favorited) else tr(R.string.action_favorite)) { it(current) }
                    }
                    if (canEdit) ViewerAction(Icons.Outlined.Tune, tr(R.string.viewer_edit)) { showEdit = true }
                    actions.onTrash?.let { ViewerAction(Icons.Outlined.DeleteOutline, tr(R.string.common_delete)) { it(current) } }
                }
            }
        }
    }

    if (showEdit) {
        ModalBottomSheet(onDismissRequest = { showEdit = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                if (!current.isVideo) actions.onEdit?.let { edit ->
                    EditOption(Icons.Outlined.Tune, tr(R.string.viewer_edit_photo), tr(R.string.viewer_edit_photo_hint) + if (current.isCloud) tr(R.string.viewer_edit_cloud_first) else "") {
                        showEdit = false
                        edit(current)
                    }
                }
                actions.onEditInfo?.let { info ->
                    EditOption(Icons.Outlined.EditNote, tr(R.string.info_edit_title), tr(R.string.viewer_edit_info_hint)) {
                        showEdit = false
                        info(current)
                    }
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            val details by produceState<MediaDetails?>(null, current) { value = repo.details(current) }
            DetailsSheet(
                current, details,
                backup = when {
                    current.isCloud -> tr(R.string.viewer_cloud_only)
                    showBackup -> if (backedUp) tr(R.string.viewer_backed_up) else tr(R.string.viewer_waiting_backup)
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun EditOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }
    if (playing && isCurrent) {
        val context = LocalContext.current
        // 只在网盘里的视频先下载到缓存再播放
        val playUri by produceState<android.net.Uri?>(if (item.isCloud) null else item.uri, item.id) {
            if (item.isCloud) {
                value = runCatching { GalleryImages.cloudFiles?.file(item.uri, thumb = false)?.let(android.net.Uri::fromFile) }.getOrNull()
            }
        }
        val uri = playUri
        if (uri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MediaBackdrop(item, shared = isCurrent)
                CircularProgressIndicator(color = Color.White)
            }
            return
        }
        val player = remember(item.id, uri) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(PlayerItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 64.dp),
        )
    } else {
        Box(Modifier.fillMaxSize().clickable(onClick = onTap), contentAlignment = Alignment.Center) {
            MediaBackdrop(item, shared = isCurrent)
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(Color(0x88000000)).clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, tr(R.string.viewer_play), tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** 敏感照片：先显示打码的缩略图，用户确认后才显示原图。 */
@Composable
private fun SensitiveCover(item: MediaItem, shared: Boolean, flags: MediaFlags) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = item.displayAspect
        AsyncImage(
            model = thumbModel(item),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = (if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
                .sharedMedia(item.id, enabled = shared).sensitiveBlur(true),
        )
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xCC111413)).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.VisibilityOff, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(tr(R.string.sensitive_maybe), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            Text(
                tr(R.string.sensitive_look_around),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            Button(onClick = { flags.onReveal(item.id) }) { Text(tr(R.string.sensitive_show_anyway)) }
            TextButton(onClick = flags.onPause) { Text(tr(R.string.sensitive_pause_10_viewer), color = Color.White) }
            TextButton(onClick = flags.onDisable) { Text(tr(R.string.sensitive_turn_off), color = Color.White.copy(alpha = 0.7f)) }
        }
    }
}

/** 按照片真实比例居中摆放的缩略图，大小和大图“适应屏幕”时完全一致。 */
@Composable
private fun MediaBackdrop(item: MediaItem, shared: Boolean) {
    val aspect = item.displayAspect
    AsyncImage(
        model = thumbModel(item),
        contentDescription = null,
        contentScale = if (aspect != null) ContentScale.Crop else ContentScale.Fit,
        modifier = (if (aspect != null) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
            .sharedMedia(item.id, enabled = shared),
    )
}

@Composable
private fun DetailsSheet(item: MediaItem, details: MediaDetails?, backup: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatTaken(item.takenAt, full = true), style = MaterialTheme.typography.titleLarge)
        DetailRow(tr(R.string.detail_file), item.displayName)
        DetailRow(tr(R.string.detail_path), item.relativePath.ifBlank { item.bucketName })
        DetailRow(tr(R.string.detail_size), "${item.width} × ${item.height}" + (if (item.width > 0) "  ·  %.1f MP".format(item.width * item.height / 1e6) else "") + "  ·  ${formatBytes(item.sizeBytes)}")
        if (item.isVideo) DetailRow(tr(R.string.detail_duration), formatDuration(item.durationMs))
        details?.camera?.let { DetailRow(tr(R.string.detail_camera), it) }
        val shot = listOfNotNull(details?.aperture, details?.exposure, details?.iso, details?.focalLength)
        if (shot.isNotEmpty()) DetailRow(tr(R.string.detail_settings), shot.joinToString("  ·  "))
        details?.lens?.let { DetailRow(tr(R.string.detail_lens), it) }
        details?.latLng?.let { (lat, lng) -> DetailRow(tr(R.string.detail_coordinates), "%.5f, %.5f".format(lat, lng)) }
        backup?.let { DetailRow(tr(R.string.detail_backup), it) }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val shortFmt: DateTimeFormatter get() = localizedFormat("yMMMdjmm")
private val fullFmt: DateTimeFormatter get() = localizedFormat("yMMMdEEEEjmm")

private fun localizedFormat(skeleton: String): DateTimeFormatter {
    val locale = java.util.Locale.getDefault()
    return DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

fun formatTaken(ms: Long, full: Boolean = false): String =
    (if (full) fullFmt else shortFmt).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
