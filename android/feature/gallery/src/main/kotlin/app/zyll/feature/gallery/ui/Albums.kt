package app.zyll.feature.gallery.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.zyll.feature.gallery.LibraryState
import app.zyll.feature.gallery.LocalMediaFlags
import androidx.annotation.StringRes
import app.zyll.media.Album
import app.zyll.media.MediaItem
import app.zyll.media.isCloud
import coil3.compose.AsyncImage
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/** 相簿页顶部的快捷入口。用户可以挑 6 个常用的放在最上面。 */
enum class QuickCollection(@StringRes private val titleRes: Int, val icon: ImageVector) {
    Device(R.string.on_this_device, Icons.Outlined.PhoneAndroid),
    Favorites(R.string.quick_favorites, Icons.Outlined.FavoriteBorder),
    Videos(R.string.quick_videos, Icons.Outlined.Videocam),
    Screenshots(R.string.quick_screenshots, Icons.Outlined.Screenshot),
    Archive(R.string.action_archive, Icons.Outlined.Archive),
    Trash(R.string.quick_trash, Icons.Outlined.DeleteOutline),
    Recent(R.string.quick_recent, Icons.Outlined.Schedule),
    CloudOnly(R.string.quick_cloud_only, Icons.Outlined.Cloud);

    val title: String get() = tr(titleRes)
}

/** 网盘里的一个相簿（按原来的相簿名归在一起）。 */
data class CloudAlbum(val name: String, val cover: MediaItem, val count: Int)

private const val QUICK_SLOTS = 6
private const val KEY_QUICK = "quickCollections"

private fun loadQuick(context: Context, available: List<QuickCollection>): List<QuickCollection> {
    val saved = runCatching {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).getString(KEY_QUICK, null)
    }.getOrNull()
    val picked = saved?.split(',')?.mapNotNull { n -> available.firstOrNull { it.name == n } }
    return (picked ?: available).take(QUICK_SLOTS)
}

private fun saveQuick(context: Context, list: List<QuickCollection>) {
    context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit()
        .putString(KEY_QUICK, list.joinToString(",") { it.name }).apply()
}

/** 已备份（本机还在的）+ 只在网盘里的照片，按相簿名分组。 */
fun cloudAlbums(state: LibraryState, backedUp: Set<Long>): List<CloudAlbum> {
    val inCloud = state.local.filter { it.id in backedUp } + state.cloudOnly
    return inCloud.groupBy { it.bucketName.ifBlank { tr(R.string.album_other) } }
        .map { (name, list) -> CloudAlbum(name, list.maxBy { it.takenAt }, list.size) }
        .sortedByDescending { it.cover.takenAt }
}

/** 某个网盘相簿里的照片。 */
fun cloudAlbumItems(state: LibraryState, backedUp: Set<Long>, name: String): List<MediaItem> =
    (state.local.filter { it.id in backedUp } + state.cloudOnly)
        .filter { it.bucketName.ifBlank { tr(R.string.album_other) } == name }
        .sortedByDescending { it.takenAt }

@Composable
fun AlbumsScreen(
    state: LibraryState,
    showFavoritesAndTrash: Boolean,
    onOpenQuick: (QuickCollection) -> Unit,
    onOpenCloudAlbum: (String) -> Unit,
    onOpenBackup: (() -> Unit)?,
    contentPadding: PaddingValues,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val context = LocalContext.current
    val available = remember(showFavoritesAndTrash) {
        QuickCollection.entries.filter { showFavoritesAndTrash || (it != QuickCollection.Favorites && it != QuickCollection.Trash) }
    }
    var pinned by remember(available) { mutableStateOf(loadQuick(context, available)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val backedUp = LocalMediaFlags.current?.backedUp?.value ?: emptySet()
    // 图库大时分组比较费时，放到后台线程算；算好之前先显示上一次的结果
    val albums by produceState<List<CloudAlbum>?>(null, state, backedUp) {
        value = withContext(Dispatchers.Default) { cloudAlbums(state, backedUp) }
    }

    // 列表已经在最顶上时继续往下拉：展开全部快捷入口
    val pullThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val pull = remember(gridState) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && !gridState.canScrollBackward) {
                    pulled += available.y
                    if (pulled > pullThreshold && !expanded) expanded = true
                } else if (available.y < 0 || consumed.y < 0) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            // 松手时清零：没拉够的距离不留到下一次
            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = gridState,
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().nestedScroll(pull),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "quick") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickRows(pinned, editing = editing, selected = true) { q ->
                    if (!editing) onOpenQuick(q)
                    else if (pinned.size > 1) pinned = (pinned - q).also { saveQuick(context, it) }
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val rest = available - pinned.toSet()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (editing) tr(R.string.quick_edit_hint, QUICK_SLOTS) else tr(R.string.common_more),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = !editing }) { Text(if (editing) tr(R.string.common_done) else tr(R.string.common_edit)) }
                            if (!editing) TextButton(onClick = { expanded = false }) { Text(tr(R.string.common_collapse)) }
                        }
                        if (rest.isNotEmpty()) QuickRows(rest, editing = editing, selected = false) { q ->
                            if (!editing) onOpenQuick(q)
                            else if (pinned.size >= QUICK_SLOTS) Toast.makeText(context, tr(R.string.quick_full, QUICK_SLOTS), Toast.LENGTH_SHORT).show()
                            else pinned = (pinned + q).also { saveQuick(context, it) }
                        }
                    }
                }
                if (!expanded) {
                    Text(
                        tr(R.string.quick_pull_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).clickable { expanded = true }.padding(4.dp),
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
            Text(tr(R.string.albums_in_cloud), style = SectionTitle, modifier = Modifier.padding(top = 6.dp))
        }
        val list = albums.orEmpty()
        if (albums != null && list.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                            Text(tr(R.string.albums_cloud_empty), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                        }
                        Text(
                            tr(R.string.albums_cloud_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        onOpenBackup?.let { TextButton(onClick = it, modifier = Modifier.padding(top = 4.dp)) { Text(tr(R.string.albums_set_up_backup)) } }
                    }
                }
            }
        }
        items(list, key = { "cloud:" + it.name }) { album ->
            AlbumCard(album.name, album.cover, album.count, cloud = true) { onOpenCloudAlbum(album.name) }
        }
    }
}

/** “此设备上”：手机里的相簿。 */
@Composable
fun DeviceAlbumsScreen(
    albums: List<Album>,
    skipped: Set<Long>?,
    onOpenAlbum: (Album) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(
            start = 14.dp, end = 14.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.bucketId }) { album ->
            AlbumCard(album.name, album.cover, album.count, cloud = skipped != null && album.bucketId !in skipped) { onOpenAlbum(album) }
        }
    }
}

@Composable
private fun AlbumCard(name: String, cover: MediaItem, count: Int, cloud: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp))) {
            AsyncImage(
                model = thumbModel(cover),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (cloud) {
                Icon(
                    if (cover.isCloud) Icons.Outlined.Cloud else Icons.Outlined.CloudUpload, tr(R.string.album_syncing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                )
            }
        }
        Text(tr(R.string.common_items_n, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 一排排小方块（每排 3 个），和下面的相簿封面区分开。 */
@Composable
private fun QuickRows(list: List<QuickCollection>, editing: Boolean, selected: Boolean, onClick: (QuickCollection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { q ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = if (editing && selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.weight(1f).clickable { onClick(q) },
                    ) {
                        Box {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(q.icon, null, tint = MaterialTheme.colorScheme.primary)
                                Text(q.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                            }
                            if (editing) {
                                Icon(
                                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.AddCircleOutline, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
                                )
                            }
                        }
                    }
                }
                // 最后一行不满三个时留空，方块大小保持一致
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}
