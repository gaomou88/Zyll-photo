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
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.MotionPhotosPause
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first
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
    /** 单独备份这张（马上传，不等 Wi‑Fi、不等充电）。 */
    val onBackup: ((MediaItem) -> Unit)? = null,
    /** 正在手动备份、还没传完的照片：按钮显示“正在备份”并且不能再点。 */
    val backingUp: Set<Long> = emptySet(),
)

/** 往下拖到屏幕高度的这个比例时，背景淡到最浅、照片缩到最小。 */
private const val DRAG_FADE_FRACTION = 0.4f
private const val DRAG_MAX_FADE = 0.6f
private const val DRAG_MAX_SHRINK = 0.15f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    startId: Long,
    repo: MediaRepository,
    actions: ViewerActions,
    inTrash: Boolean,
    onClose: () -> Unit,
    onCurrentChange: (MediaItem) -> Unit = {},
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
    ViewerSystemBars(chrome)
    // 动态照片：每张照片打开后找一次视频（找到的才显示“动态”按钮）；正在播放哪一张
    val motionFound = remember { mutableStateMapOf<Long, MotionKind>() }
    var playMotion by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pager.currentPage) { playMotion = null }
    // 让下面的网格跟着滚到当前这张：关闭时缩略图在屏幕上，才有缩回去的动画
    val latestOnCurrent by rememberUpdatedState(onCurrentChange)
    // 刚打开的那张就是用户在网格里点的，不用滚；翻到别的照片后才跟
    val openedId = remember { shown.getOrNull(pager.currentPage)?.id }
    var paged by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage, shown) {
        val item = shown.getOrNull(pager.currentPage) ?: return@LaunchedEffect
        if (item.id != openedId) paged = true
        if (paged) latestOnCurrent(item)
    }
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

    // 打开动画进行中先只放缩略图，动画走完再解码原图（当前这张和左右两张）。
    // 原图几千万像素，和放大动画抢主线程时最容易掉帧
    val opening = LocalMediaTransition.current?.visibility?.transition
    var settled by remember { mutableStateOf(opening == null) }
    if (!settled && opening != null) {
        LaunchedEffect(opening) {
            // 进场时 currentState 是 PreEnter、targetState 是 Visible，两者相同而且不在跑才算走完
            snapshotFlow { opening.currentState == opening.targetState && !opening.isRunning }.first { it }
            settled = true
        }
    }

    // 往下拖关闭大图：照片跟着手指往下走，松手时拖得够远（或甩得够快）就关闭，照片沿着关闭动画缩回格子；不够就弹回。
    // 放大着时拖动交给缩放（平移照片），不会触发；播放动态照片时也不触发
    var dragY by remember { mutableFloatStateOf(0f) }
    var viewerHeight by remember { mutableIntStateOf(1) }
    val dragScope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtLeast(0f) }
    // 往下拖时背景跟着变淡、照片跟着缩小一点，让人看得出“往下拉能关”
    val dragFraction = (dragY / (viewerHeight * DRAG_FADE_FRACTION)).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - DRAG_MAX_FADE * dragFraction))
            .onSizeChanged { viewerHeight = it.height.coerceAtLeast(1) },
    ) {
        HorizontalPager(
            pager,
            key = { shown[it].id },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
                .offset { IntOffset(0, dragY.roundToInt()) }
                .graphicsLayer {
                    val sc = 1f - DRAG_MAX_SHRINK * dragFraction
                    scaleX = sc
                    scaleY = sc
                }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = playMotion == null && !pager.isScrollInProgress,
                    onDragStopped = { velocity ->
                        if (dragY > viewerHeight * DRAG_CLOSE_FRACTION || (velocity > DRAG_CLOSE_VELOCITY && dragY > 0f)) {
                            onClose()
                        } else {
                            dragScope.launch { animate(dragY, 0f, animationSpec = tween(200)) { v, _ -> dragY = v } }
                        }
                    },
                ),
        ) { page ->
            val item = shown[page]
            if (item.id in hiddenIds && flags != null) {
                SensitiveCover(item, shared = page == pager.currentPage, flags = flags)
            } else if (item.isVideo) {
                VideoPage(item, isCurrent = page == pager.currentPage, onTap = { chrome = !chrome })
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val zoom = rememberZoomableImageState()
                    val haptic = LocalHapticFeedback.current
                    // 播放动态照片前先把缩放复原：视频按整屏大小播，放大着播画面会突然一跳
                    val motionOn = playMotion == item.id
                    LaunchedEffect(motionOn) { if (motionOn) zoom.zoomableState.resetZoom() }
                    // 原图解码出来之前先垫一张缩略图；它也是打开 / 返回动画里飞来飞去的那张
                    MediaBackdrop(item, shared = page == pager.currentPage)
                    // 原图解码好后用很短的淡入盖上缩略图，不硬换（两者位置大小一致，看起来只是变清楚）
                    val fullAlpha = remember(item.id) { Animatable(0f) }
                    val displayed = zoom.isImageDisplayed
                    LaunchedEffect(displayed) { if (displayed) fullAlpha.animateTo(1f, tween(FULL_FADE_MS)) }
                    if (settled) ZoomableAsyncImage(
                        model = fullModel(item),
                        contentDescription = item.displayName,
                        state = zoom,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fullAlpha.value },
                        onClick = { chrome = !chrome },
                        // 长按播放动态照片（和系统相册一样）
                        onLongClick = {
                            if (motionFound[item.id] != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                playMotion = item.id
                            }
                        },
                    )
                    MotionLayer(
                        item = item,
                        isCurrent = page == pager.currentPage,
                        livePair = flags?.livePair(item.id),
                        // 格子上已经有标记（目录里的 mo、本机扫描、实况配对）：按钮先显示，点了再找视频
                        hinted = item.motion || flags?.motion?.value?.contains(item.id) == true,
                        playing = motionOn,
                        onFound = { kind -> if (kind == null) { motionFound.remove(item.id) } else { motionFound[item.id] = kind } },
                        onDone = { if (playMotion == item.id) playMotion = null },
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
                if (!current.isVideo && motionFound[current.id] != null) {
                    val on = playMotion == current.id
                    IconButton(onClick = { playMotion = if (on) null else current.id }) {
                        Icon(
                            if (on) Icons.Outlined.MotionPhotosPause else Icons.Outlined.MotionPhotosOn,
                            if (on) tr(R.string.motion_stop) else tr(R.string.motion_play),
                            tint = Color.White,
                        )
                    }
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
                    actions.onToggleFavorite?.let { FavoriteAction(current) { it(current) } }
                    if (canEdit) ViewerAction(Icons.Outlined.Tune, tr(R.string.viewer_edit)) { showEdit = true }
                    // 还没备份的照片：在大图里就能单独备份，不用回网格多选
                    val synced = current.id in (flags?.backedUp?.value ?: emptySet())
                    if (!synced) actions.onBackup?.let { backup ->
                        if (current.id in actions.backingUp) BackingUpAction() else ViewerAction(Icons.Outlined.CloudUpload, tr(R.string.action_backup)) { backup(current) }
                    }
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
                motion = when (motionFound[current.id]) {
                    MotionKind.Embedded -> tr(R.string.motion_embedded)
                    MotionKind.LivePair -> flags?.livePair(current.id)?.let { tr(R.string.motion_live_pair, it.displayName) } ?: tr(R.string.motion_photo)
                    null -> null
                },
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

/** 点了“备份”之后：转圈 + “正在备份”，不能再点。 */
@Composable
private fun BackingUpAction() {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
        Text(tr(R.string.viewer_backing_up), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun VideoPage(item: MediaItem, isCurrent: Boolean, onTap: () -> Unit) {
    var playing by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(isCurrent) { if (!isCurrent) playing = false }
    if (playing && isCurrent) {
        val context = LocalContext.current
        // 只在网盘里的视频先下载到缓存再播放；取不到时说明原因并给“重试”，不一直转圈
        var attempt by remember(item.id) { mutableStateOf(0) }
        var failed by remember(item.id) { mutableStateOf(false) }
        // 下载进度 0..1；还没开始或不知道大小时是 -1，转圈不带百分比
        var progress by remember(item.id) { mutableFloatStateOf(-1f) }
        val playUri by produceState<android.net.Uri?>(if (item.isCloud) null else item.uri, item.id, attempt) {
            if (item.isCloud) {
                value = null
                failed = false
                progress = -1f
                val file = try {
                    GalleryImages.cloudFiles?.file(item.uri, thumb = false) { progress = it }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                value = file?.let(android.net.Uri::fromFile)
                failed = file == null
            }
        }
        val uri = playUri
        if (uri == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MediaBackdrop(item, shared = isCurrent)
                if (failed) {
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xCC000000)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(tr(R.string.viewer_video_failed), color = Color.White, textAlign = TextAlign.Center)
                        TextButton(onClick = { attempt++ }) { Text(tr(R.string.common_retry)) }
                    }
                } else if (progress >= 0f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(progress = { progress }, color = Color.White)
                        Text("${(progress * 100).toInt()}%", color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            return
        }
        val player = rememberViewerPlayer(item.id, uri) { playWhenReady = true }
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
    // 网盘照片没有宽高时，用网格量过的；都没有就等这张缩略图加载完量出来
    var aspect by remember(item.id) { mutableStateOf(MediaAspects.of(item)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 和网格格子用同一档尺寸：直接命中网格已经解好的那张，动画第一帧就有图
    val request = remember(item.id, item.modifiedAt) {
        coil3.request.ImageRequest.Builder(context)
            .data(thumbModel(item))
            .size(BACKDROP_THUMB_PX)
            .precision(coil3.size.Precision.INEXACT)
            .build()
    }
    val a = aspect
    AsyncImage(
        model = request,
        onSuccess = { s ->
            if (aspect == null) {
                MediaAspects.record(item, s.result.image.width, s.result.image.height)
                aspect = MediaAspects.of(item)
            }
        },
        contentDescription = null,
        contentScale = if (a != null) ContentScale.Crop else ContentScale.Fit,
        modifier = (if (a != null) Modifier.aspectRatio(a) else Modifier.fillMaxSize())
            .sharedMedia(item.id, enabled = shared),
    )
}

@Composable
private fun DetailsSheet(item: MediaItem, details: MediaDetails?, backup: String?, motion: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatTaken(item.takenAt, full = true), style = MaterialTheme.typography.titleLarge)
        DetailRow(tr(R.string.detail_file), item.displayName)
        DetailRow(tr(R.string.detail_path), item.relativePath.ifBlank { item.bucketName })
        DetailRow(tr(R.string.detail_size), "${item.width} × ${item.height}" + (if (item.width > 0) "  ·  %.1f MP".format(item.width * item.height / 1e6) else "") + "  ·  ${formatBytes(item.sizeBytes)}")
        if (item.isVideo) DetailRow(tr(R.string.detail_duration), formatDuration(item.durationMs))
        motion?.let { DetailRow(tr(R.string.detail_motion), it) }
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

/**
 * 大图背景是黑的：状态栏和导航栏图标改成浅色，离开时恢复；隐藏工具栏时一起隐藏系统栏（沉浸模式），
 * 从屏幕边缘滑一下可以临时叫出来。
 */
@Composable
private fun ViewerSystemBars(chromeVisible: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val window = remember(view) {
        var c = view.context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.window
    }
    if (window == null) return
    val controller = remember(window, view) { androidx.core.view.WindowCompat.getInsetsController(window, view) }
    // 转屏、切深色模式时界面不重建（见 MainActivity 的 configChanges），系统那边可能把图标颜色改回去：按配置重新设一次
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val uiMode = config.uiMode
    val orientation = config.orientation
    // 按配置重设的只管浅色图标；显示系统栏、按主题恢复放在只跟 controller 的那个里，转屏时系统栏不会先闪出来
    DisposableEffect(controller, uiMode, orientation) {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {}
    }
    DisposableEffect(controller) {
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // 关闭时按当前主题重新算（和 enableEdgeToEdge 默认一致：浅色主题用深色图标），不恢复打开时的旧值
            val dark = (view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    LaunchedEffect(controller, chromeVisible, uiMode, orientation) {
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (chromeVisible) controller.show(bars) else controller.hide(bars)
    }
}

/** 原图盖上缩略图时的淡入时长。 */
private const val FULL_FADE_MS = 150

/** 往下拖超过屏幕高度的这个比例，松手就关闭大图。 */
private const val DRAG_CLOSE_FRACTION = 0.15f

/** 往下甩的速度超过这个值（像素 / 秒），拖得不远也关闭。 */
private const val DRAG_CLOSE_VELOCITY = 1500f
