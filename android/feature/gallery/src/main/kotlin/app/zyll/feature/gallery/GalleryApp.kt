package app.zyll.feature.gallery

import app.zyll.feature.gallery.ui.displayTitle
import app.zyll.feature.gallery.ui.dayTitle
import androidx.annotation.StringRes
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zyll.ai.ModelState
import app.zyll.feature.gallery.ui.AlbumsScreen
import app.zyll.feature.gallery.ui.DeviceAlbumsScreen
import app.zyll.feature.gallery.ui.cloudAlbumItems
import app.zyll.feature.gallery.ui.Density
import app.zyll.feature.gallery.ui.DiscoverScreen
import app.zyll.feature.gallery.ui.EmptyState
import app.zyll.feature.gallery.ui.LocalMediaTransition
import app.zyll.feature.gallery.ui.LocalScrollBusy
import app.zyll.feature.gallery.ui.MediaTransition
import app.zyll.feature.gallery.ui.PAGE_MS
import app.zyll.feature.gallery.ui.pageTween
import app.zyll.feature.gallery.ui.MediaGrid
import app.zyll.feature.gallery.ui.QuickCollection
import app.zyll.feature.gallery.ui.TimelineScreen
import app.zyll.feature.gallery.ui.ViewerActions
import app.zyll.feature.gallery.ui.ViewerScreen
import app.zyll.feature.gallery.ui.localDate
import app.zyll.media.MediaItem
import app.zyll.media.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.MobileOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.zyll.feature.gallery.ui.thumbModel
import app.zyll.media.Album
import app.zyll.media.isCloud
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.Switch
import androidx.core.content.FileProvider
import app.zyll.feature.gallery.ui.GalleryImages
import java.text.SimpleDateFormat
import java.util.Locale
import app.zyll.media.tr
import app.zyll.feature.gallery.R

/** 不同发行版之间有差别的部分，由 App 传进来。 */
class GalleryHooks(
    /** 打开编辑器；GitHub 版为 null（不显示编辑按钮）。 */
    val onEdit: ((MediaItem) -> Unit)?,
    /** “我的”页内容（设置、保险库、系统选择器接入等）。 */
    val settings: @Composable (PaddingValues) -> Unit,
    /** 网盘备份状态；没有时不显示备份相关的标记。 */
    val backup: BackupIndicator? = null,
    /** 打开网盘备份设置（App 会把“我的”页切到备份页）。 */
    val onOpenBackup: () -> Unit = {},
    /** 外部要求切到“我的”页（比如从系统照片选择器点进来）。 */
    val openMe: kotlinx.coroutines.flow.Flow<Unit>? = null,
    /** 别的 App 用“打开方式”交给 Zyll 的图片 / 视频。 */
    val openUri: kotlinx.coroutines.flow.Flow<Uri>? = null,
    /** 看完别的 App 交过来的文件后（回到那个 App）。 */
    val onExternalDone: () -> Unit = {},
    /** 端侧 AI；GitHub 版为 null（没有 AI 功能，不显示模型和智能分类）。 */
    val ai: AiAccess? = AiAccess.Open,
)

/** 端侧 AI 能不能用。Play 免费版 [locked] 为 true，显示解锁入口。 */
class AiAccess(
    val locked: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val onUnlock: () -> Unit,
) {
    companion object {
        val Open = AiAccess(kotlinx.coroutines.flow.MutableStateFlow(false), {})
    }
}

private enum class Tab(@StringRes private val titleRes: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    Timeline(R.string.tab_timeline, Icons.Outlined.GridView, Icons.Filled.GridView),
    Albums(R.string.tab_albums, Icons.Outlined.Collections, Icons.Filled.Collections),
    Discover(R.string.tab_discover, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    Me(R.string.tab_me, Icons.Outlined.Person, Icons.Filled.Person);

    val title: String get() = tr(titleRes)
}

sealed interface Source {
    data class Album(val bucketId: Long) : Source
    data object Favorites : Source
    data object Videos : Source
    data object Screenshots : Source
    data object Trash : Source
    data object Archive : Source
    data object CloudOnly : Source
    data object Recent : Source
    data class CloudAlbum(val name: String) : Source
    data class Category(val key: String) : Source
    data class Search(val query: String) : Source
    data class Fixed(val ids: List<Long>) : Source
}

private sealed interface Route {
    data class Collection(val title: String, val source: Source) : Route
    data class Viewer(val ids: List<Long>, val startId: Long, val inTrash: Boolean) : Route
    data object DeviceAlbums : Route
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryApp(controller: GalleryController, hooks: GalleryHooks) {
    val context = LocalContext.current
    val repo = controller.repo
    var granted by remember { mutableStateOf(repo.hasFullAccess() || repo.hasPartialAccess()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = repo.hasFullAccess() || repo.hasPartialAccess()
        if (granted) controller.start()
    }
    LaunchedEffect(granted) { if (granted) controller.start() }

    // 从别的 App“打开方式”进来：直接看这一张，关掉就回去
    var external by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(hooks.openUri) {
        hooks.openUri?.collect { uri -> external = externalItem(context, uri) }
    }
    external?.let { item ->
        ViewerScreen(
            items = listOf(item),
            startId = item.id,
            repo = repo,
            inTrash = false,
            onClose = { external = null; hooks.onExternalDone() },
            actions = ViewerActions(
                onShare = { share(context, listOf(it), listOf(it.uri)) },
                onToggleFavorite = null,
                onEdit = hooks.onEdit,
                onTrash = null,
            ),
        )
        return
    }

    if (!granted) {
        PermissionScreen { permLauncher.launch(mediaPermissions()) }
        return
    }

    val library by controller.library.collectAsState()
    val noIds = remember { MutableStateFlow(emptySet<Long>()) }
    val noFlag = remember { MutableStateFlow(false) }
    val noProgress = remember { MutableStateFlow(BackupProgress()) }
    val hiddenState = controller.sensitive.hidden.collectAsState()
    val backedState = (hooks.backup?.backedUpIds ?: noIds).collectAsState()
    val backupActiveState = (hooks.backup?.active ?: noFlag).collectAsState()
    val aiLockedState = (hooks.ai?.locked ?: noFlag).collectAsState()
    val backupProgress by (hooks.backup?.progress ?: noProgress).collectAsState()
    val flags = remember(hiddenState, backedState, backupActiveState) {
        MediaFlags(
            hidden = hiddenState,
            backedUp = backedState,
            showBackup = backupActiveState,
            onReveal = controller.sensitive::reveal,
            onPause = { controller.sensitive.pauseFor(10) },
            onDisable = { controller.sensitive.setEnabled(false) },
            onLeaveViewer = controller.sensitive::clearReveals,
        )
    }
    val stack = remember { mutableStateListOf<Route>() }
    var tab by rememberSaveable { mutableStateOf(Tab.Timeline) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var timelineDensity by rememberSaveable { mutableStateOf(Density.Comfortable) }
    var collectionDensity by rememberSaveable { mutableStateOf(Density.Comfortable) }
    var trashItems by remember { mutableStateOf(emptyList<MediaItem>()) }
    var trashVersion by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var albumPicker by remember { mutableStateOf<List<MediaItem>?>(null) }
    var locationEditor by remember { mutableStateOf<List<MediaItem>?>(null) }
    var freeUp by remember { mutableStateOf<Pair<List<MediaItem>, Int>?>(null) }
    var cloudDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var trashConfirm by remember { mutableStateOf<TrashAsk?>(null) }
    var infoEditor by remember { mutableStateOf<MediaItem?>(null) }
    var busy by remember { mutableStateOf<BusyTask?>(null) }
    val uiScope = rememberCoroutineScope()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    LaunchedEffect(trashVersion, library) { trashItems = controller.trash() }
    LaunchedEffect(hooks.openMe) {
        hooks.openMe?.collect { stack.clear(); selection = emptySet(); tab = Tab.Me }
    }

    // ---- 系统确认弹窗（收藏 / 回收站 / 删除）----
    var afterWrite by remember { mutableStateOf<(() -> Unit)?>(null) }
    var offerManageAfter by remember { mutableStateOf(false) }
    var manageMediaOffer by remember { mutableStateOf<(() -> Unit)?>(null) }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        controller.refresh()
        trashVersion++
        if (res.resultCode == Activity.RESULT_OK) afterWrite?.invoke()
        afterWrite = null
        // 这次操作做完了，再问要不要开“媒体管理”，不插在确认框中间
        if (offerManageAfter) {
            offerManageAfter = false
            manageMediaOffer = {}
        }
    }
    val prefs = remember { context.getSharedPreferences("gallery", Context.MODE_PRIVATE) }
    var pendingAfterSettings by remember { mutableStateOf<(() -> Unit)?>(null) }
    val manageMediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // 从系统设置回来后继续刚才的操作；开了权限的话这次就不会再弹确认框
        pendingAfterSettings?.invoke()
        pendingAfterSettings = null
    }
    fun perform(result: WriteResult, onDone: () -> Unit = {}) {
        when (result) {
            WriteResult.Done -> {
                controller.refresh(); trashVersion++; onDone()
            }
            is WriteResult.NeedsConfirmation -> {
                val launch = {
                    afterWrite = onDone
                    writeLauncher.launch(IntentSenderRequest.Builder(result.intent).build())
                }
                // 第一次需要系统确认时，等这次做完再问一次要不要开“媒体管理”，开了以后就不用每次确认
                if (repo.supportsManageMedia && !repo.canManageMedia() && !prefs.getBoolean(KEY_ASKED_MANAGE_MEDIA, false)) {
                    prefs.edit().putBoolean(KEY_ASKED_MANAGE_MEDIA, true).apply()
                    offerManageAfter = true
                }
                launch()
            }
            is WriteResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }
    // ---- 多选和大图里的各种操作 ----
    fun moveToAlbum(items: List<MediaItem>, path: String, label: String) {
        if (Build.VERSION.SDK_INT < 29) return toast(tr(R.string.move_unsupported_os))
        perform(repo.requestWrite(items)) {
            uiScope.launch {
                val n = repo.moveTo(items, path)
                toast(if (n == items.size) tr(R.string.move_done, label) else tr(R.string.move_partial, n, items.size - n))
                controller.refresh()
                selection = emptySet()
            }
        }
    }
    fun setLocation(items: List<MediaItem>, latLng: Pair<Double, Double>?) {
        perform(repo.requestWrite(items)) {
            uiScope.launch {
                val n = repo.setLocation(items, latLng)
                toast(
                    when {
                        n == 0 -> tr(R.string.location_unsupported)
                        latLng == null -> tr(R.string.location_removed, n)
                        else -> tr(R.string.location_changed, n)
                    },
                )
                controller.refresh()
                selection = emptySet()
            }
        }
    }
    fun backupSelected(items: List<MediaItem>) {
        val b = hooks.backup ?: return
        if (b.backup(items)) toast(tr(R.string.backup_started_n, items.size)) else toast(tr(R.string.backup_connect_first))
        selection = emptySet()
    }
    fun deleteFromDevice(items: List<MediaItem>) {
        val safe = hooks.backup?.safelyBackedUp(items).orEmpty()
        if (safe.isEmpty()) return toast(tr(R.string.delete_device_not_backed_up))
        freeUp = safe to (items.size - safe.size)
    }
    fun archive(items: List<MediaItem>, archived: Boolean) {
        controller.setArchived(items.map { it.id }, archived)
        toast(if (archived) tr(R.string.archive_done, items.size) else tr(R.string.unarchive_done, items.size))
        selection = emptySet()
    }
    fun restoreFromCloud(items: List<MediaItem>) {
        val b = hooks.backup ?: return
        toast(tr(R.string.restore_downloading, items.size))
        selection = emptySet()
        uiScope.launch {
            val n = runCatching { b.restore(items) }.getOrElse { e -> toast(e.message ?: tr(R.string.download_failed)); return@launch }
            toast(tr(R.string.restore_done, n))
        }
    }

    // ---- 网盘照片：先下载（可取消），再分享 / 修图 / 改信息 ----
    fun runBusy(label: String, total: Int, block: suspend (BusyTask) -> Unit) {
        val task = BusyTask(label, total)
        busy = task
        task.job = uiScope.launch {
            try {
                block(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: tr(R.string.action_failed_network))
            } finally {
                if (busy === task) busy = null
            }
        }
    }
    /** 把网盘照片下载到本机缓存，拿到可以交给别的 App 的 uri；本机照片直接用原来的 uri。 */
    fun withFiles(items: List<MediaItem>, onReady: (List<Uri>) -> Unit) {
        val cloud = items.filter { it.isCloud }
        if (cloud.isEmpty()) return onReady(items.map { it.uri })
        val files = GalleryImages.cloudFiles ?: return toast(tr(R.string.cloud_unavailable))
        runBusy(tr(R.string.downloading_media), cloud.size) { task ->
            val got = HashMap<Long, Uri>()
            for (c in cloud) {
                val f = files.file(c.uri, thumb = false)
                got[c.id] = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                task.done++
            }
            busy = null
            onReady(items.map { got[it.id] ?: it.uri })
        }
    }
    fun shareItems(items: List<MediaItem>) {
        if (items.isEmpty()) return
        withFiles(items) { uris -> share(context, items, uris); selection = emptySet() }
    }
    val editAction: ((MediaItem) -> Unit)? = hooks.onEdit?.let { edit ->
        { item: MediaItem -> withFiles(listOf(item)) { uris -> edit(item.copy(uri = uris[0])) } }
    }
    fun saveInfo(item: MediaItem, name: String?, takenAt: Long?, location: Pair<Double, Double>?, clearLocation: Boolean) {
        if (name == null && takenAt == null && location == null && !clearLocation) return
        if (item.isCloud) {
            val b = hooks.backup ?: return
            val heavy = takenAt != null || location != null || clearLocation
            runBusy(if (heavy) tr(R.string.downloading_media) else tr(R.string.saving), 1) { task ->
                b.editCloud(item, name, takenAt, location, clearLocation)
                task.done = 1
                toast(if (heavy) tr(R.string.saved_reuploaded) else tr(R.string.saved))
            }
            return
        }
        perform(repo.requestWrite(listOf(item))) {
            uiScope.launch {
                var ok = true
                if (name != null) ok = repo.rename(item, name) && ok
                if (takenAt != null) ok = repo.setTakenAt(listOf(item), takenAt) > 0 && ok
                if (location != null || clearLocation) ok = repo.setLocation(listOf(item), location) > 0 && ok
                toast(if (ok) tr(R.string.saved) else tr(R.string.info_partly_unsupported))
                controller.refresh()
            }
        }
    }

    /** 开着备份时，这些照片里有几项还没有完整备份（备份后又改过的也算没备份）。 */
    fun notBackedUp(items: List<MediaItem>): Int {
        val b = hooks.backup ?: return 0
        if (!backupActiveState.value) return 0
        return items.size - b.safelyBackedUp(items).size
    }
    // Android 11+ 没开“媒体管理”时系统会自己弹确认框：这时只在有没备份的照片时多提醒一次，免得确认两次
    fun systemWillAsk() = Build.VERSION.SDK_INT >= 30 && !repo.canManageMedia()
    fun deleteForever(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (systemWillAsk() && notBackedUp(items) == 0) perform(repo.delete(items)) { selection = emptySet() } else confirmDelete = items
    }

    fun moveToTrash(items: List<MediaItem>, cloudSkipped: Int = 0) {
        // Android 10 及以下没有回收站，删除就是永久的，先在 App 里确认一次
        if (items.isEmpty()) return toast(tr(R.string.use_cloud_delete))
        when {
            !repo.supportsFavoritesAndTrash -> confirmDelete = items
            systemWillAsk() && notBackedUp(items) == 0 && cloudSkipped == 0 -> perform(repo.trash(items)) { selection = emptySet() }
            else -> trashConfirm = TrashAsk(items, cloudSkipped)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.ai.importFiles(uris)
    }

    val favoriteAction: ((List<MediaItem>) -> Unit)? = if (repo.supportsFavoritesAndTrash) {
        { list -> perform(repo.favorite(list, !list.all { it.isFavorite })) }
    } else null

    BackHandler(enabled = stack.isNotEmpty() || selection.isNotEmpty() || tab != Tab.Timeline) {
        when {
            selection.isNotEmpty() -> selection = emptySet()
            stack.isNotEmpty() -> stack.removeAt(stack.lastIndex)
            else -> tab = Tab.Timeline
        }
    }

    fun openViewer(item: MediaItem, list: List<MediaItem>, inTrash: Boolean = false) {
        stack += Route.Viewer(list.map { it.id }, item.id, inTrash)
    }
    // 只弹出自己：退场动画进行中的旧页面再收到返回键时不会误关上一层
    fun pop(route: Route) {
        if (stack.lastOrNull() == route) stack.removeAt(stack.lastIndex)
    }

    // 列表滚动位置放在外面，页面切走再回来时还停在原处，返回动画才对得上格子
    val timelineGrid = rememberLazyGridState()
    val albumsGrid = rememberLazyGridState()
    val discoverGrid = rememberLazyGridState()
    val collectionGrids = remember { mutableMapOf<Route, LazyGridState>() }
    LaunchedEffect(stack.size) { collectionGrids.keys.retainAll(stack.toSet()) }

    fun selectionBarFor(inTrashView: Boolean, inArchive: Boolean = false): @Composable () -> Unit = {
        val all = { if (inTrashView) trashItems.filter { it.id in selection } else controller.byIds(selection.toList()) }
        val local = { all().filter { !it.isCloud } }
        val cloud = { all().filter { it.isCloud } }
        val hasCloud = !inTrashView && selection.any { it < 0 } && all().any { it.isCloud }
        val onlyCloud = hasCloud && all().all { it.isCloud }
        val withBackup = hooks.backup != null
        if (inTrashView) {
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                actions = listOf(
                    BarAction(Icons.Outlined.RestoreFromTrash, tr(R.string.action_restore)) { perform(repo.trash(all(), trash = false)) { selection = emptySet() } },
                    BarAction(Icons.Outlined.DeleteForever, tr(R.string.action_delete_forever)) { deleteForever(all()) },
                ),
            )
        } else if (onlyCloud) {
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                actions = listOf(
                    BarAction(Icons.Outlined.Share, tr(R.string.action_share)) { shareItems(cloud()) },
                    BarAction(Icons.Outlined.Download, tr(R.string.action_restore_to_phone)) { restoreFromCloud(cloud()) },
                    BarAction(Icons.Outlined.DeleteForever, tr(R.string.action_delete_from_cloud)) { cloudDelete = cloud() },
                ),
            )
        } else {
            SelectionBar(
                count = selection.size,
                onClear = { selection = emptySet() },
                actions = listOf(
                    BarAction(Icons.Outlined.Share, tr(R.string.action_share)) { shareItems(all()) },
                    BarAction(Icons.AutoMirrored.Outlined.PlaylistAdd, tr(R.string.action_add_to_album)) { albumPicker = local() },
                    BarAction(Icons.Outlined.DeleteOutline, tr(R.string.action_move_to_trash)) { moveToTrash(local(), cloudSkipped = cloud().size) },
                ),
                more = listOfNotNull(
                    favoriteAction?.let { f -> BarAction(Icons.Outlined.FavoriteBorder, tr(R.string.action_favorite)) { f(local()) } },
                    if (withBackup) BarAction(Icons.Outlined.CloudUpload, tr(R.string.action_backup)) { backupSelected(local()) } else null,
                    if (inArchive) BarAction(Icons.Outlined.Unarchive, tr(R.string.action_unarchive)) { archive(local(), false) }
                    else BarAction(Icons.Outlined.Archive, tr(R.string.action_archive)) { archive(local(), true) },
                    if (withBackup) BarAction(Icons.Outlined.MobileOff, tr(R.string.action_delete_from_device)) { deleteFromDevice(local()) } else null,
                    BarAction(Icons.Outlined.EditLocationAlt, tr(R.string.action_edit_location)) { locationEditor = local().filter { !it.isVideo } },
                    if (hasCloud) BarAction(Icons.Outlined.Download, tr(R.string.action_restore_cloud_to_phone)) { restoreFromCloud(cloud()) } else null,
                ),
            )
        }
    }

    SharedTransitionLayout(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = stack.toList(),
            contentKey = { it.size to it.lastOrNull() },
            transitionSpec = { routeTransition(initialState, targetState) },
            label = "route",
        ) { routes ->
            val route = routes.lastOrNull()
            // 正在退场的页面（比如关大图的动画中）不再接收点按，免得点到它上面的按钮
            val exiting = transition.targetState == EnterExitState.PostExit
            Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalMediaTransition provides MediaTransition(this@SharedTransitionLayout, this@AnimatedContent),
                LocalScrollBusy provides controller.ai.uiBusy,
                LocalMediaFlags provides flags,
            ) {
                when (route) {
                    is Route.Viewer -> {
                        val items = remember(route, library, trashItems) {
                            if (route.inTrash) route.ids.toSet().let { ids -> trashItems.filter { it.id in ids } } else controller.byIds(route.ids)
                        }
                        ViewerScreen(
                            items = items,
                            startId = route.startId,
                            repo = repo,
                            inTrash = route.inTrash,
                            onClose = { pop(route) },
                            actions = ViewerActions(
                                onShare = { shareItems(listOf(it)) },
                                onToggleFavorite = favoriteAction?.let { f -> { item: MediaItem -> f(listOf(item)) } },
                                onEdit = editAction,
                                onTrash = { moveToTrash(listOf(it)) },
                                onRestore = { perform(repo.trash(listOf(it), trash = false)) },
                                onDeleteForever = { deleteForever(listOf(it)) },
                                onRestoreFromCloud = hooks.backup?.let { { item: MediaItem -> restoreFromCloud(listOf(item)) } },
                                onDeleteFromCloud = hooks.backup?.let { { item: MediaItem -> cloudDelete = listOf(item) } },
                                onEditInfo = { infoEditor = it },
                            ),
                        )
                    }
                    Route.DeviceAlbums -> {
                        val skipped = hooks.backup?.skippedAlbums?.collectAsState()?.value
                        Scaffold(
                            topBar = {
                                Row(
                                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { pop(route) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
                                    Column {
                                        Text(tr(R.string.on_this_device), style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            tr(R.string.albums_n, library.albums.size) + if (skipped != null) tr(R.string.albums_cloud_hint) else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                        ) { padding ->
                            DeviceAlbumsScreen(
                                albums = library.albums,
                                skipped = skipped,
                                onOpenAlbum = { stack += Route.Collection(it.name, Source.Album(it.bucketId)) },
                                contentPadding = padding,
                            )
                        }
                    }
                    is Route.Collection -> CollectionScreen(
                        aiOn = hooks.ai != null && !aiLockedState.value,
                        route = route,
                        controller = controller,
                        library = library,
                        trashItems = trashItems,
                        density = collectionDensity,
                        onDensityChange = { collectionDensity = it },
                        selection = selection,
                        onSelectionChange = { selection = it },
                        selectionBar = selectionBarFor(route.source == Source.Trash, inArchive = route.source == Source.Archive),
                        gridState = remember(route) { collectionGrids.getOrPut(route) { LazyGridState() } },
                        onBack = { pop(route) },
                        onOpen = { item, list -> openViewer(item, list, inTrash = route.source == Source.Trash) },
                        backup = hooks.backup,
                    )
                    null -> HomeScreen(
                        tab = tab,
                        onTab = { tab = it; selection = emptySet() },
                        library = library,
                        selecting = selection.isNotEmpty(),
                        selectionBar = selectionBarFor(false),
                        partialAccess = repo.hasPartialAccess(),
                        onManageAccess = { permLauncher.launch(mediaPermissions()) },
                        backup = if (hooks.backup != null) backupProgress else null,
                        backupActive = backupActiveState.value,
                        onOpenBackup = { tab = Tab.Me; selection = emptySet(); hooks.onOpenBackup() },
                    ) { t, padding ->
                        when (t) {
                            Tab.Timeline -> TimelineScreen(
                                state = library,
                                density = timelineDensity,
                                onDensityChange = { timelineDensity = it },
                                selection = selection,
                                onSelectionChange = { selection = it },
                                onOpen = { item, list -> openViewer(item, list) },
                                onOpenMemory = { title, list -> stack += Route.Collection(title, Source.Fixed(list.map { it.id })) },
                                contentPadding = padding,
                                gridState = timelineGrid,
                            )
                            Tab.Albums -> AlbumsScreen(
                                state = library,
                                showFavoritesAndTrash = repo.supportsFavoritesAndTrash,
                                onOpenQuick = { q ->
                                    val source = when (q) {
                                        QuickCollection.Device -> null
                                        QuickCollection.Favorites -> Source.Favorites
                                        QuickCollection.Videos -> Source.Videos
                                        QuickCollection.Screenshots -> Source.Screenshots
                                        QuickCollection.Trash -> Source.Trash
                                        QuickCollection.Archive -> Source.Archive
                                        QuickCollection.Recent -> Source.Recent
                                        QuickCollection.CloudOnly -> Source.CloudOnly
                                    }
                                    stack += if (source == null) Route.DeviceAlbums else Route.Collection(q.title, source)
                                },
                                onOpenCloudAlbum = { stack += Route.Collection(it, Source.CloudAlbum(it)) },
                                onOpenBackup = hooks.backup?.let { { tab = Tab.Me; hooks.onOpenBackup() } },
                                contentPadding = padding,
                                gridState = albumsGrid,
                            )
                            Tab.Discover -> DiscoverScreen(
                                ai = controller.ai,
                                access = hooks.ai,
                                library = library,
                                onSearch = { stack += Route.Collection("“$it”", Source.Search(it)) },
                                onOpenCategory = { stack += Route.Collection(it.displayTitle, Source.Category(it.key)) },
                                onImportModel = { importLauncher.launch(arrayOf("*/*")) },
                                contentPadding = padding,
                                state = discoverGrid,
                            )
                            Tab.Me -> hooks.settings(padding)
                        }
                    }
                }
            }
            if (exiting) Box(Modifier.matchParentSize().blockTouches())
            }
        }
    }

    manageMediaOffer?.let { proceed ->
        AlertDialog(
            onDismissRequest = { manageMediaOffer = null; proceed() },
            title = { Text(tr(R.string.manage_media_offer_title)) },
            text = { Text(tr(R.string.manage_media_offer_body)) },
            confirmButton = {
                TextButton(onClick = {
                    manageMediaOffer = null
                    val intent = repo.manageMediaSettingsIntent()
                    if (intent != null) {
                        pendingAfterSettings = proceed
                        manageMediaLauncher.launch(intent)
                    } else {
                        proceed()
                    }
                }) { Text(tr(R.string.manage_media_go)) }
            },
            dismissButton = { TextButton(onClick = { manageMediaOffer = null; proceed() }) { Text(tr(R.string.manage_media_later)) } },
        )
    }

    albumPicker?.let { items ->
        AlbumPickerDialog(
            albums = library.albums,
            onDismiss = { albumPicker = null },
            onPick = { path, label -> albumPicker = null; moveToAlbum(items, path, label) },
        )
    }

    locationEditor?.let { items ->
        LocationDialog(
            count = items.size,
            onDismiss = { locationEditor = null },
            onSave = { latLng -> locationEditor = null; setLocation(items, latLng) },
        )
    }

    freeUp?.let { (items, skipped) ->
        AlertDialog(
            onDismissRequest = { freeUp = null },
            title = { Text(tr(R.string.free_device_title, items.size)) },
            text = {
                Text(
                    tr(R.string.free_device_body) +
                        if (skipped > 0) tr(R.string.free_device_skipped, skipped) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    freeUp = null
                    perform(repo.delete(items)) { selection = emptySet(); toast(tr(R.string.freed_size, formatSize(items.sumOf { it.sizeBytes }))) }
                }) { Text(tr(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { freeUp = null }) { Text(tr(R.string.common_cancel)) } },
        )
    }

    infoEditor?.let { item ->
        InfoEditDialog(
            item = item,
            loadLocation = { if (item.isCloud) null else runCatching { repo.details(item).latLng }.getOrNull() },
            onDismiss = { infoEditor = null },
            onSave = { name, takenAt, loc, clear -> infoEditor = null; saveInfo(item, name, takenAt, loc, clear) },
        )
    }

    busy?.let { task ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(task.label) },
            text = {
                Column {
                    if (task.total > 1) {
                        LinearProgressIndicator(progress = { task.done / task.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text("${task.done} / ${task.total}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { task.job?.cancel(); busy = null }) { Text(tr(R.string.common_cancel)) } },
        )
    }

    trashConfirm?.let { ask ->
        val items = ask.items
        val unsafe = remember(ask) { notBackedUp(items) }
        val cloudNote = if (ask.cloudSkipped > 0) tr(R.string.trash_cloud_note, ask.cloudSkipped) else ""
        AlertDialog(
            onDismissRequest = { trashConfirm = null },
            title = { Text(if (unsafe > 0) tr(R.string.delete_careful) else tr(R.string.trash_title)) },
            text = {
                Text(
                    when {
                        !backupActiveState.value -> tr(R.string.trash_body_plain, items.size)
                        unsafe == items.size -> tr(R.string.trash_body_all_unsafe, items.size)
                        unsafe > 0 -> tr(R.string.trash_body_some_unsafe, items.size, unsafe)
                        else -> tr(R.string.trash_body_backed_up, items.size)
                    } + cloudNote,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    trashConfirm = null
                    perform(repo.trash(items)) { selection = emptySet() }
                }) { Text(tr(R.string.action_move_to_trash), color = if (unsafe > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = { TextButton(onClick = { trashConfirm = null }) { Text(tr(R.string.common_cancel)) } },
        )
    }

    cloudDelete?.let { items ->
        AlertDialog(
            onDismissRequest = { cloudDelete = null },
            title = { Text(tr(R.string.cloud_delete_title)) },
            text = { Text(tr(R.string.cloud_delete_body, items.size)) },
            confirmButton = {
                TextButton(onClick = {
                    cloudDelete = null
                    selection = emptySet()
                    val b = hooks.backup ?: return@TextButton
                    uiScope.launch {
                        val n = b.deleteFromCloud(items)
                        toast(if (n > 0) tr(R.string.cloud_deleted_n, n) else tr(R.string.delete_failed_network))
                    }
                }) { Text(tr(R.string.action_delete_forever), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { cloudDelete = null }) { Text(tr(R.string.common_cancel)) } },
        )
    }

    confirmDelete?.let { items ->
        val unsafe = remember(items) { notBackedUp(items) }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (unsafe > 0) tr(R.string.delete_careful) else tr(R.string.delete_forever_title, items.size)) },
            text = {
                Text(
                    (when {
                        unsafe > 0 -> tr(R.string.delete_forever_unsafe, unsafe)
                        !repo.supportsFavoritesAndTrash -> tr(R.string.delete_forever_no_trash)
                        else -> tr(R.string.delete_forever_body)
                    }) +
                        if (backupActiveState.value && unsafe == 0) tr(R.string.delete_forever_cloud_ok) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    perform(repo.delete(items)) { selection = emptySet() }
                }) { Text(tr(R.string.action_delete_forever), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun CollectionScreen(
    aiOn: Boolean,
    route: Route.Collection,
    controller: GalleryController,
    library: LibraryState,
    trashItems: List<MediaItem>,
    density: Density,
    onDensityChange: (Density) -> Unit,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    selectionBar: @Composable () -> Unit,
    gridState: LazyGridState,
    onBack: () -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    backup: BackupIndicator?,
) {
    val ai = controller.ai
    val backedUp = LocalMediaFlags.current?.backedUp?.value ?: emptySet()
    val model by ai.modelState.collectAsState()
    val progress by ai.indexProgress.collectAsState()
    val source = route.source

    // AI 结果：索引每多 200 张重新算一次，边建索引边变准
    val aiIds by produceState<List<Long>?>(null, source, model, aiOn, progress.done / 200, progress.running) {
        value = when (source) {
            is Source.Category -> if (aiOn && model == ModelState.Ready) ai.category(source.key).map { it.id } else emptyList()
            is Source.Search -> if (aiOn && model == ModelState.Ready) ai.search(source.query).map { it.id } else null
            else -> null
        }
    }
    // 只在数据真的变了时重新筛选，否则每次重组都会生成新列表、网格整体重排
    val items: List<MediaItem> = remember(source, library, trashItems, aiIds, if (source is Source.CloudAlbum) backedUp else null) { when (source) {
        is Source.Album -> library.local.filter { it.bucketId == source.bucketId }
        Source.Favorites -> library.local.filter { it.isFavorite }
        Source.Videos -> library.items.filter { it.isVideo }
        Source.Screenshots -> library.local.filter { it.isScreenshot }
        Source.Trash -> trashItems
        Source.Archive -> library.archived
        Source.CloudOnly -> library.cloudOnly
        Source.Recent -> library.local.filter { it.modifiedAt > System.currentTimeMillis() - RECENT_MS }.sortedByDescending { it.modifiedAt }
        is Source.CloudAlbum -> cloudAlbumItems(library, backedUp, source.name)
        is Source.Fixed -> controller.byIds(source.ids)
        is Source.Category -> controller.byIds(aiIds.orEmpty())
        is Source.Search -> aiIds?.let { controller.byIds(it) } ?: keywordSearch(library.items, source.query)
    } }
    val ranked = source is Source.Category || (source is Source.Search && aiIds != null)

    Scaffold(
        topBar = {
            AnimatedContent(selection.isNotEmpty(), transitionSpec = { barFade() }, label = "collectionBar") { selecting ->
            if (selecting) selectionBar() else Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(R.string.common_back)) }
                Column(Modifier.weight(1f)) {
                    Text(route.title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(
                        tr(R.string.common_items_n, items.size) + when {
                            source == Source.Trash -> tr(R.string.collection_trash_note)
                            source == Source.Archive -> tr(R.string.collection_archive_note)
                            source == Source.CloudOnly -> tr(R.string.collection_cloud_only_note)
                            source == Source.Recent -> tr(R.string.collection_recent_note)
                            source is Source.CloudAlbum -> tr(R.string.collection_cloud_note)
                            ranked && progress.running -> tr(R.string.collection_ranking_running)
                            ranked -> tr(R.string.collection_ranked)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 本机相簿：是否同步到网盘（和“我的 → 网盘备份”里的相簿设置是同一份）
                if (source is Source.Album && backup != null) {
                    val skipped by backup.skippedAlbums.collectAsState()
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 8.dp)) {
                        Switch(source.bucketId !in skipped, { backup.setAlbumSynced(source.bucketId, it) })
                        Text(tr(R.string.album_sync), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                when (source) {
                    Source.Trash -> tr(R.string.empty_trash)
                    Source.Archive -> tr(R.string.empty_archive)
                    Source.CloudOnly -> tr(R.string.empty_cloud_only)
                    is Source.Search, is Source.Category -> tr(R.string.empty_not_found)
                    else -> tr(R.string.empty_nothing_here)
                },
                when (source) {
                    is Source.Search -> if (model == ModelState.Ready) tr(R.string.empty_search_hint) else tr(R.string.empty_search_enable_ai)
                    is Source.Category -> if (progress.running) tr(R.string.empty_category_running) else ""
                    else -> ""
                },
                Modifier.padding(padding),
            )
        } else {
            MediaGrid(
                items = items,
                density = density,
                onDensityChange = onDensityChange,
                selection = selection,
                onSelectionChange = onSelectionChange,
                onOpen = { onOpen(it, items) },
                grouped = !ranked,
                contentPadding = padding,
                state = gridState,
            )
        }
    }
}

/** 首页：顶栏 + 四个标签页 + 底部导航。标签之间淡入淡出，选择模式时顶栏换成操作栏。 */
@Composable
private fun HomeScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    library: LibraryState,
    selecting: Boolean,
    selectionBar: @Composable () -> Unit,
    partialAccess: Boolean,
    onManageAccess: () -> Unit,
    backup: BackupProgress?,
    backupActive: Boolean,
    onOpenBackup: () -> Unit,
    content: @Composable (Tab, PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            AnimatedContent(if (selecting) null else tab, transitionSpec = { barFade() }, label = "homeBar") { t ->
                if (t == null) selectionBar() else HomeTopBar(
                    title = t.title,
                    subtitle = when (t) {
                        Tab.Timeline -> if (library.loaded) tr(R.string.common_items_n, library.items.size) else null
                        Tab.Albums -> null
                        else -> null
                    },
                    onSearch = if (t == Tab.Timeline || t == Tab.Albums) ({ onTab(Tab.Discover) }) else null,
                    backup = if (t != Tab.Me) backup else null,
                    backupActive = backupActive,
                    onOpenBackup = onOpenBackup,
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { scaffoldPadding ->
        // 顶栏占的高度给外层 Column，底部导航栏的高度交给各页面的列表（内容可以滚到导航栏后面）
        val padding = PaddingValues(bottom = scaffoldPadding.calculateBottomPadding())
        Column(Modifier.fillMaxSize().padding(top = scaffoldPadding.calculateTopPadding())) {
            AnimatedVisibility(partialAccess, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                PartialAccessBanner(onManageAccess)
            }
            // 正在备份 / 有问题时，在时间线顶部提示，点一下进备份设置
            val banner = backup?.takeIf { tab == Tab.Timeline && (it.running || it.problem != null) }
            AnimatedVisibility(banner != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                BackupBanner(banner ?: BackupProgress(), onOpenBackup)
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(220, delayMillis = 60), initialScale = 0.985f)) togetherWith
                        fadeOut(tween(90))
                },
                modifier = Modifier.weight(1f),
                label = "tab",
            ) { t ->
                Box(Modifier.fillMaxSize()) { content(t, padding) }
            }
        }
    }
}

/** 吞掉所有触摸。 */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

private fun barFade(): ContentTransform = fadeIn(tween(180)) togetherWith fadeOut(tween(120))

/**
 * 页面栈的切换动画：
 * - 打开大图：底下的网格不动，大图层淡入，照片本身由共享元素从格子放大；返回时反过来。
 * - 打开相簿 / 搜索结果：新页面从右侧滑入，旧页面略微左移并淡出；返回时反向。
 */
private fun routeTransition(from: List<Route>, to: List<Route>): ContentTransform {
    val push = to.size > from.size
    val viewer = (if (push) to.lastOrNull() else from.lastOrNull()) is Route.Viewer
    return when {
        // 底下的网格保持可见（几乎不透明），等大图层淡入完成
        viewer && push -> fadeIn(tween(260)) togetherWith fadeOut(tween(300), targetAlpha = 0.99f)
        viewer -> (EnterTransition.None togetherWith fadeOut(tween(260))).apply { targetContentZIndex = -1f }
        push -> (slideInHorizontally(pageTween()) { it / 3 } + fadeIn(pageTween())) togetherWith
            (slideOutHorizontally(pageTween()) { -it / 8 } + fadeOut(tween(PAGE_MS / 2)))
        else -> ((slideInHorizontally(pageTween()) { -it / 8 } + fadeIn(pageTween())) togetherWith
            (slideOutHorizontally(pageTween()) { it / 3 } + fadeOut(tween(PAGE_MS / 2)))).apply { targetContentZIndex = -1f }
    }
}

/** 没有 AI 模型时的退路：按文件名、相簿名、日期找。 */
private fun keywordSearch(items: List<MediaItem>, q: String): List<MediaItem> {
    val k = q.trim().lowercase()
    if (k.isEmpty()) return emptyList()
    // 中文的日期和类型关键词总能用，另外再认当前语言的写法
    val videoWords = setOf("视频", tr(R.string.quick_videos).lowercase())
    val screenshotWords = setOf("截图", "截屏", tr(R.string.quick_screenshots).lowercase())
    return items.filter {
        val d = it.localDate()
        it.displayName.lowercase().contains(k) || it.bucketName.lowercase().contains(k) ||
            "${d.year}年${d.monthValue}月${d.dayOfMonth}日".contains(k) || d.toString().contains(k) ||
            dayTitle(d).lowercase().contains(k) ||
            (k in videoWords && it.isVideo) || (k in screenshotWords && it.isScreenshot)
    }
}

private class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, actions: List<BarAction>, more: List<BarAction> = emptyList()) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, tr(R.string.selection_clear)) }
            Text(tr(R.string.selection_count, count), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            actions.forEach { a -> IconButton(onClick = a.onClick) { Icon(a.icon, a.label) } }
            if (more.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, tr(R.string.common_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        more.forEach { a ->
                            DropdownMenuItem(
                                text = { Text(a.label) },
                                leadingIcon = { Icon(a.icon, null) },
                                onClick = { menu = false; a.onClick() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 选一个相簿（或新建一个）把照片移过去。 */
@Composable
private fun AlbumPickerDialog(albums: List<Album>, onDismiss: () -> Unit, onPick: (path: String, label: String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.action_add_to_album)) },
        text = {
            Column {
                OutlinedTextField(
                    newName, { newName = it.replace("/", "") },
                    label = { Text(tr(R.string.album_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr(R.string.album_move_existing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(albums, key = { it.bucketId }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(a.relativePath, a.name) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = thumbModel(a.cover),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(tr(R.string.common_items_n, a.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onPick("Pictures/${newName.trim()}", newName.trim()) }) { Text(tr(R.string.album_create_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
    )
}

/** 修改拍摄地点：输入“纬度, 经度”，或者删除位置信息。 */
@Composable
private fun LocationDialog(count: Int, onDismiss: () -> Unit, onSave: (Pair<Double, Double>?) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) {
        val parts = text.replace('，', ',').split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        if (parts.size == 2 && lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.location_edit_title, count)) },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(tr(R.string.location_lat_lng)) },
                    placeholder = { Text(tr(R.string.location_example)) },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr(R.string.location_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { onSave(null) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(tr(R.string.location_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onSave(parsed) }) { Text(tr(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
    )
}

@Composable
private fun InfoEditDialog(
    item: MediaItem,
    loadLocation: suspend () -> Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onSave: (name: String?, takenAt: Long?, location: Pair<Double, Double>?, clearLocation: Boolean) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val ext = item.displayName.substringAfterLast('.', "")
    val baseName = if (ext.isEmpty()) item.displayName else item.displayName.removeSuffix(".$ext")
    val oldTime = remember(item) { fmt.format(java.util.Date(item.takenAt)) }
    var name by remember(item) { mutableStateOf(baseName) }
    var time by remember(item) { mutableStateOf(oldTime) }
    var loc by remember(item) { mutableStateOf("") }
    var oldLoc by remember(item) { mutableStateOf("") }
    var clearLoc by remember(item) { mutableStateOf(false) }
    val canLocate = !item.isVideo
    LaunchedEffect(item) {
        if (canLocate) loadLocation()?.let { (lat, lng) ->
            oldLoc = "%.6f, %.6f".format(Locale.US, lat, lng)
            if (loc.isEmpty()) loc = oldLoc
        }
    }
    val parsedTime = remember(time) { runCatching { fmt.parse(time.trim())?.time }.getOrNull() }
    val parsedLoc = remember(loc) {
        val parts = loc.replace('，', ',').split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        if (parts.size == 2 && lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) lat to lng else null
    }
    val locBad = canLocate && !clearLoc && loc.isNotBlank() && parsedLoc == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.info_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text(tr(R.string.info_name)) },
                    suffix = if (ext.isNotEmpty()) ({ Text(".$ext") }) else null,
                    singleLine = true,
                    isError = name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    time, { time = it },
                    label = { Text(tr(R.string.info_taken)) },
                    placeholder = { Text("2024-05-01 18:30") },
                    singleLine = true,
                    isError = parsedTime == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (canLocate) {
                    OutlinedTextField(
                        loc, { loc = it },
                        label = { Text(tr(R.string.info_location)) },
                        placeholder = { Text(tr(R.string.location_example)) },
                        singleLine = true,
                        enabled = !clearLoc,
                        isError = locBad,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.location_remove), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(clearLoc, { clearLoc = it })
                    }
                    Text(
                        tr(R.string.info_location_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && parsedTime != null && !locBad,
                onClick = {
                    val newName = name.trim().takeIf { it != baseName }
                    val newTime = parsedTime?.takeIf { time.trim() != oldTime }
                    val newLoc = if (canLocate && !clearLoc && loc.trim() != oldLoc) parsedLoc else null
                    onSave(newName, newTime, newLoc, canLocate && clearLoc)
                },
            ) { Text(tr(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.common_cancel)) } },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

@Composable
private fun HomeTopBar(
    title: String,
    subtitle: String?,
    onSearch: (() -> Unit)?,
    backup: BackupProgress? = null,
    backupActive: Boolean = false,
    onOpenBackup: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp),
            )
        }
        Box(Modifier.weight(1f))
        onSearch?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Search, tr(R.string.common_search)) } }
        if (backup != null) BackupButton(backup, backupActive, onOpenBackup)
    }
}

/** 顶栏右侧的云朵：备份中外圈转进度，备份完成打勾，有问题变红，没开备份是划掉的云。 */
@Composable
private fun BackupButton(p: BackupProgress, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(contentAlignment = Alignment.Center) {
            if (p.running) {
                val f = p.fraction
                if (f != null) {
                    CircularProgressIndicator(progress = { f }, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                }
            }
            Icon(
                when {
                    p.problem != null -> Icons.Outlined.CloudOff
                    p.running -> Icons.Outlined.CloudUpload
                    active -> Icons.Outlined.CloudDone
                    else -> Icons.Outlined.CloudOff
                },
                contentDescription = when {
                    p.problem != null -> p.problem
                    p.running -> p.label ?: tr(R.string.backup_bar_running)
                    active -> tr(R.string.backup_bar_done)
                    else -> tr(R.string.backup_bar_off)
                },
                tint = when {
                    p.problem != null -> MaterialTheme.colorScheme.error
                    active || p.running -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(if (p.running) 18.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun BackupBanner(p: BackupProgress, onClick: () -> Unit) {
    Surface(
        color = if (p.problem != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (p.problem != null) Icons.Outlined.CloudOff else Icons.Outlined.CloudUpload, null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    p.problem ?: p.label ?: tr(R.string.backup_bar_running),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            if (p.running && p.problem == null) {
                val f = p.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(onManage: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.partial_access), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onManage) { Text(tr(R.string.common_manage)) }
        }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        Text(tr(R.string.permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            tr(R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onRequest) { Text(tr(R.string.permission_allow)) }
    }
}

private const val KEY_ASKED_MANAGE_MEDIA = "askedManageMedia"
private const val RECENT_MS = 30L * 24 * 3600 * 1000

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/** 把别的 App 给的 uri 包装成一张照片（读一下名称、大小、类型）。 */
private suspend fun externalItem(context: Context, uri: Uri): MediaItem = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment ?: tr(R.string.common_photo)
    var size = 0L
    runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val mime = runCatching { resolver.getType(uri) }.getOrNull()
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "image/*"
    val now = System.currentTimeMillis()
    MediaItem(
        id = Long.MIN_VALUE + 1, uri = uri, isVideo = mime.startsWith("video/"), takenAt = now, modifiedAt = now,
        width = 0, height = 0, durationMs = 0, mimeType = mime, displayName = name, bucketId = 0, bucketName = "",
        sizeBytes = size, isFavorite = false, relativePath = "",
    )
}

private class TrashAsk(val items: List<MediaItem>, val cloudSkipped: Int)

private class BusyTask(val label: String, val total: Int) {
    var done by mutableIntStateOf(0)
    var job: Job? = null
}

private fun share(context: Context, items: List<MediaItem>, shareUris: List<Uri>) {
    if (items.isEmpty()) return
    val uris = ArrayList(shareUris)
    val mime = when {
        items.all { !it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // 多个文件时把 uri 放进 ClipData，系统才会把读取权限一起交给目标 App
    intent.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(intent, tr(R.string.share_to)))
}
