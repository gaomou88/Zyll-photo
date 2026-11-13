package app.zyll.feature.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zyll.feature.gallery.LocalMediaFlags
import app.zyll.media.MediaItem
import app.zyll.media.isCloud
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/**
 * 通用媒体网格：按天/月分组，双指捏合切换密度，长按进入多选，右侧拖动快速定位。
 * 时间线、相簿详情、搜索结果、回收站都用它。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    density: Density,
    onDensityChange: (Density) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    grouped: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyGridState = rememberLazyGridState(),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    // 分组在后台线程算（见 GridLayouts）；同一个列表算过的直接用，没算好之前先显示这个网格上一次的布局
    val hasHeader = header != null
    val layout by produceState(
        initialValue = GridLayouts.peek(items, density.byMonth, grouped, hasHeader) ?: GridPositions.last(state),
        items, density.byMonth, grouped, hasHeader,
    ) {
        value = GridLayouts.peek(items, density.byMonth, grouped, hasHeader)
            ?: withContext(Dispatchers.Default) { GridLayouts.compute(items, density.byMonth, grouped, hasHeader) }
    }
    val sections = layout?.sections.orEmpty()
    // 网格里每个位置对应的照片（分组标题处为 null），给快速滚动条显示日期用
    val flat = layout?.flat.orEmpty()
    layout?.let { l -> SideEffect { GridPositions.attach(state, l) } }
    val selecting = selection.isNotEmpty()
    // 照片在网格里的先后顺序，长按拖动时按这个顺序选一整段
    val order = remember(layout) { layout?.let { it.ids to it.indexOf } ?: (emptyList<Long>() to emptyMap()) }
    val latestSelection by rememberUpdatedState(selection)
    val latestChange by rememberUpdatedState(onSelectionChange)
    var autoScroll by remember { mutableFloatStateOf(0f) }
    // 长按开始多选的那次抬手不能再算一次“点按”，否则刚选中就被取消了
    val dragging = remember { DragFlag() }
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0f) return@LaunchedEffect
        while (true) {
            state.scrollBy(autoScroll)
            dragging.reselect?.invoke()
            delay(16)
        }
    }

    // 滚动时让后台建索引暂停，滚动更跟手
    val busy = LocalScrollBusy.current
    if (busy != null) {
        LaunchedEffect(state, busy) {
            snapshotFlow { state.isScrollInProgress }.collect { busy.value = it }
        }
        DisposableEffect(busy) { onDispose { busy.value = false } }
    }

    // 顶栏换成选择栏时留白会变几帧；手势里读最新值，不让正在进行的拖动多选被重启
    val latestPadding by rememberUpdatedState(contentPadding)
    // 长按进入多选比系统默认（约半秒）快一点
    val baseConfig = LocalViewConfiguration.current
    val quickPress = remember(baseConfig) { QuickLongPress(baseConfig) }
    Box(modifier.pinchToChangeDensity(density, onDensityChange)) {
        // 第一次的布局还没算出来：先不放网格。用空列表测量会把保存的滚动位置（含大图里请求的位置）清零
        if (layout == null) return@Box
        CompositionLocalProvider(LocalViewConfiguration provides quickPress) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(density.columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().dragToSelect(
                state = state,
                order = { order },
                selection = { latestSelection },
                onChange = { latestChange(it) },
                onAutoScroll = { autoScroll = it },
                flag = dragging,
                contentPadding = { latestPadding },
            ),
        ) {
            header?.invoke(this)
            sections.forEach { section ->
                if (section.title.isNotEmpty()) {
                    item(key = section.key, span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                        SectionHeader(section, selecting, selection, onSelectionChange)
                    }
                }
                items(section.items, key = { it.id }, contentType = { "tile" }) { item ->
                    Tile(
                        item = item,
                        selecting = selecting,
                        selected = item.id in selection,
                        compact = density.columns >= 6,
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = tween(180)),
                        onClick = {
                            when {
                                dragging.consumeTap() -> {}
                                selecting -> onSelectionChange(selection.toggle(item.id))
                                else -> onOpen(item)
                            }
                        },
                    )
                }
            }
        }
        }
        if (items.size > 60) FastScroller(state, flat, Modifier.align(Alignment.TopEnd).padding(contentPadding))
    }
}

/** 敏感照片打码：Android 12+ 用真正的模糊，更早的系统直接盖一层深色。 */
fun Modifier.sensitiveBlur(hidden: Boolean): Modifier = when {
    !hidden -> this
    android.os.Build.VERSION.SDK_INT >= 31 -> this.blur(28.dp)
    else -> this.drawWithContent { drawRect(Color(0xFF2A2F2D)) }
}

/** 由外层提供：列表滚动时置为 true（用来暂停后台的 AI 索引）。 */
val LocalScrollBusy = staticCompositionLocalOf<MutableStateFlow<Boolean>?> { null }

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@Composable
private fun SectionHeader(section: Section, selecting: Boolean, selection: Set<Long>, onSelectionChange: (Set<Long>) -> Unit) {
    val ids = remember(section) { section.items.map { it.id } }
    val all = selecting && ids.all { it in selection }
    // 高度固定：进不进多选都一样高，长按进多选时网格不会往下跳
    Row(
        Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(section.title, style = SectionTitle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
        section.subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 9.dp))
        }
        Spacer(Modifier.weight(1f))
        if (selecting) {
            Icon(
                if (all) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (all) tr(R.string.grid_deselect_group) else tr(R.string.grid_select_group),
                tint = if (all) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                // 图标保持 22dp，可点的范围扩到 44dp，好点中
                modifier = Modifier.clip(CircleShape).combinedClickable(onClick = {
                    onSelectionChange(if (all) selection - ids.toSet() else selection + ids)
                }).padding(11.dp).size(22.dp),
            )
        }
    }
}

/** 分组标题的高度：原来的上下留白加文字，刚好放得下 44dp 的勾选点击区。 */
private val HEADER_HEIGHT = 52.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    item: MediaItem,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // 每个格子只盯自己这一张的标记，别的照片备份完成不会让它重组
    val flags = LocalMediaFlags.current
    val hidden = flags?.let { f -> remember(item.id, f) { derivedStateOf { item.id in f.hidden.value } }.value } == true
    val notBackedUp = flags?.let { f ->
        remember(item.id, f) { derivedStateOf { f.showBackup.value && item.id !in f.backedUp.value } }.value
    } == true
    val motion = !item.isVideo && flags?.motion?.let { m -> remember(item.id, m) { derivedStateOf { item.id in m.value } }.value } == true
    // 选中动画只改绘制层（缩放 + 圆角），不触发重新布局
    val scale by animateFloatAsState(if (selected) 0.84f else 1f, label = "scale")
    val corner by animateDpAsState(if (selected) 10.dp else 0.dp, label = "corner")
    Box(
        modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 长按交给外层的拖动多选处理，这里只管点按
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = thumbModel(item),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            // 网盘照片没有宽高：从缩略图量出来，打开大图时的放大动画要用
            onSuccess = { s -> MediaAspects.record(item, s.result.image.width, s.result.image.height) },
            modifier = Modifier.fillMaxSize().sharedMedia(item.id).sensitiveBlur(hidden).graphicsLayer {
                scaleX = scale
                scaleY = scale
                val c = corner
                if (c > 0.dp) {
                    shape = RoundedCornerShape(c)
                    clip = true
                } else {
                    clip = false
                }
            },
        )
        if (item.isVideo && !compact) {
            Row(
                Modifier.align(Alignment.BottomEnd).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(formatDuration(item.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (hidden) {
            Icon(
                Icons.Outlined.VisibilityOff, tr(R.string.sensitive_maybe), tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(if (compact) 14.dp else 22.dp),
            )
        }
        if (item.isCloud && !selecting) {
            Icon(
                Icons.Outlined.Cloud, tr(R.string.grid_cloud_only), tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(if (compact) 11.dp else 15.dp),
            )
        } else if (notBackedUp && !compact && !selecting) {
            Icon(
                Icons.Outlined.CloudOff, tr(R.string.grid_not_backed_up), tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp),
            )
        }
        if (!compact) {
            // 收藏 / 取消收藏时角上的小心形淡入淡出
            androidx.compose.animation.AnimatedVisibility(
                visible = shownFavorite(item),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.6f),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Icon(
                    Icons.Filled.Favorite, tr(R.string.viewer_favorited), tint = Color.White,
                    modifier = Modifier.padding(5.dp).size(14.dp),
                )
            }
        }
        if (motion && !compact && !selecting) {
            Icon(
                Icons.Outlined.MotionPhotosOn, tr(R.string.motion_photo), tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(15.dp),
            )
        }
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 长按一张照片开始多选，手指不抬起继续拖动，经过的照片按顺序整段选中（往回拖会取消多选的部分）；
 * 拖到屏幕上下边缘时自动滚动。长按的是已选中的照片时，拖过的照片改为取消选择。
 */
private class DragFlag {
    /** 这次手势是长按多选：手指抬起时格子收到的点按要忽略。 */
    var active = false

    fun consumeTap(): Boolean = active.also { active = false }

    /** 按手指最后停的位置重新算一次选区（列表自动滚动后调用：手指不动也要把滚过来的照片选上）。 */
    var reselect: (() -> Unit)? = null
}

private fun Modifier.dragToSelect(
    state: LazyGridState,
    order: () -> Pair<List<Long>, Map<Long, Int>>,
    selection: () -> Set<Long>,
    onChange: (Set<Long>) -> Unit,
    onAutoScroll: (Float) -> Unit,
    flag: DragFlag,
    contentPadding: () -> PaddingValues,
): Modifier = pointerInput(state) {
    // 格子的位置是从内容区算起的，不含列表顶部留白（比如相簿页的标题栏高度），要先把手指位置换算过去
    fun keyAt(pos: Offset): Long? {
        val info = state.layoutInfo
        val p = Offset(pos.x - contentPadding().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr).toPx(), pos.y - info.beforeContentPadding)
        return info.visibleItemsInfo.firstOrNull { item ->
            p.x >= item.offset.x && p.x < item.offset.x + item.size.width &&
                p.y >= item.offset.y && p.y < item.offset.y + item.size.height
        }?.key as? Long
    }

    var anchor = -1
    var last = -1
    var base = emptySet<Long>()
    var adding = true
    var start = Offset.Zero
    var ranging = false
    val edge = 64.dp.toPx()
    // 手指移动超过这个距离才开始连选，长按后手指轻微抖动不会误选旁边的照片
    val slop = 28.dp.toPx()
    fun apply(current: Int) {
        val (ids, _) = order()
        if (anchor < 0 || current < 0 || current == last) return
        last = current
        val range = ids.subList(minOf(anchor, current), maxOf(anchor, current) + 1)
        onChange(if (adding) base + range else base - range.toSet())
    }
    detectDragGesturesAfterLongPress(
        onDragStart = { pos ->
            val id = keyAt(pos) ?: return@detectDragGesturesAfterLongPress
            val index = order().second[id] ?: return@detectDragGesturesAfterLongPress
            base = selection()
            adding = id !in base
            anchor = index
            last = -1
            start = pos
            ranging = false
            flag.active = true
            apply(index)
        },
        // 抬手时格子的点按先于这里收到；没被格子用掉（手指移动过）的标记在这里清掉
        onDragEnd = { anchor = -1; flag.active = false; flag.reselect = null; onAutoScroll(0f) },
        onDragCancel = { anchor = -1; flag.active = false; flag.reselect = null; onAutoScroll(0f) },
    ) { change, _ ->
        if (anchor < 0) return@detectDragGesturesAfterLongPress
        change.consume()
        if (!ranging) {
            if ((change.position - start).getDistance() < slop) return@detectDragGesturesAfterLongPress
            ranging = true
        }
        // 感应区从内容区的上下边缘算起（扣掉标题栏、底部导航栏占的留白）
        val y = change.position.y
        val top = state.layoutInfo.beforeContentPadding.toFloat()
        val bottom = size.height - state.layoutInfo.afterContentPadding.toFloat()
        onAutoScroll(
            when {
                y < top + edge -> -(top + edge - y).coerceAtMost(edge) / 3f
                y > bottom - edge -> (y - (bottom - edge)).coerceAtMost(edge) / 3f
                else -> 0f
            },
        )
        val at = change.position
        keyAt(at)?.let { id -> order().second[id]?.let(::apply) }
        flag.reselect = { if (anchor >= 0) keyAt(at)?.let { id -> order().second[id]?.let(::apply) } }
    }
}

private class QuickLongPress(private val base: ViewConfiguration) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = minOf(base.longPressTimeoutMillis, 320L)
}

/** 两指捏合：放大 → 更少列，缩小 → 更多列（一次手势只切一档）。 */
private fun Modifier.pinchToChangeDensity(density: Density, onChange: (Density) -> Unit) = pointerInput(density) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        var done = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (!done && zoom > 1.25f) {
                    onChange(density.looser()); done = true
                } else if (!done && zoom < 0.8f) {
                    onChange(density.denser()); done = true
                }
                // 双指时不让网格跟着滚动
                event.changes.forEach { it.consume() }
            }
        }
    }
}

@Composable
private fun FastScroller(state: LazyGridState, flat: List<MediaItem?>, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val total = flat.size.coerceAtLeast(1)
    // 位置只在布局阶段读取（见下面的 offset），滚动时不用每格都重组
    // 分母扣掉一屏能显示的格子数：滚到底时滑块也到底
    val fraction = {
        if (dragging) dragFraction else {
            val shown = state.layoutInfo.visibleItemsInfo.size
            (state.firstVisibleItemIndex / (total - shown).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        }
    }
    val visible = dragging || state.isScrollInProgress
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scroller")

    // 只有滑块本身接收触摸，不挡住网格右侧的照片
    BoxWithConstraints(modifier.fillMaxHeight()) {
        val trackPx = with(LocalDensity.current) { (maxHeight - 48.dp).toPx() }.coerceAtLeast(1f)
        if (alpha > 0f) {
            Row(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction().coerceIn(0f, 1f) * trackPx).roundToInt()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(dragging, enter = fadeIn(), exit = fadeOut()) {
                    val index = (dragFraction * (total - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(0)).roundToInt()
                    // 标题位置往后找第一张照片（不复制列表，拖动时每帧都会算）
                    val label = flat.getOrNull(index) ?: (index until flat.size).firstNotNullOfOrNull { flat[it] }
                    if (label != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            Text(
                                scrubLabel(label),
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .pointerInput(total, trackPx) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragFraction = fraction()
                                    dragging = true
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                dragFraction = (dragFraction + dy / trackPx).coerceIn(0f, 1f)
                                scope.launch { state.scrollToItem((dragFraction * (total - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(0)).roundToInt()) }
                            }
                        }
                        .padding(start = 16.dp, end = 3.dp)
                        .width(6.dp).height(48.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = alpha)),
                )
            }
        }
    }
}
