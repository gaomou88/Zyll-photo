package app.zyll.gallery

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import app.zyll.feature.gallery.ui.thumbModel
import app.zyll.feature.gallery.ui.topBarInsets
import coil3.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.zyll.feature.gallery.LocalMediaFlags
import app.zyll.feature.gallery.MediaFlags
import app.zyll.feature.gallery.ui.Density
import app.zyll.feature.gallery.ui.GalleryImages
import app.zyll.feature.gallery.ui.MediaGrid
import app.zyll.feature.gallery.ui.ZyllTheme
import app.zyll.media.MediaItem
import app.zyll.media.isCloud
import app.zyll.media.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 别的 App“选择图片/视频”时的选图页（系统列表里的 Zyll）：本机照片 + 只在网盘里的照片，所有人都能用。
 *
 * - 本机照片直接交回媒体库的地址，并临时授权对方读取；
 * - 网盘照片先下载解密到 App 缓存（cloud-view/），再通过 FileProvider 临时授权对方读取。
 * 只授权读、只授权选中的这几项，对方拿不到别的文件。
 */
class PickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        GalleryImages.install(this)
        val controller = AppGraph.controller(this)
        // 先建好网盘部分（GalleryImages.cloudFiles 由它设置）
        AppGraph.backup(this)
        val req = intent
        val multiple = req.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        val accept = acceptedTypes(req)

        setContent {
            ZyllTheme {
                Surface(Modifier.fillMaxSize()) {
                    app.zyll.gallery.settings.LegalConsentGate(onDecline = { setResult(RESULT_CANCELED); finish() }) {
                        PickScreen(
                            controller = controller,
                            multiple = multiple,
                            accept = accept,
                            onCancel = { setResult(RESULT_CANCELED); finish() },
                            onDone = { picked, uris -> finishWith(picked, uris) },
                        )
                    }
                }
            }
        }
    }

    private fun finishWith(picked: List<MediaItem>, uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val mimes = picked.map { it.mimeType.ifBlank { if (it.isVideo) "video/*" else "image/*" } }.distinct().toTypedArray()
        val clip = ClipData(tr(R.string.pick_clip_label), mimes, ClipData.Item(uris[0]))
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        val result = Intent().apply {
            data = uris[0]
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    companion object {
        /** 对方要哪些类型：图片（image/…）、视频（video/…），或都要。认不出来时两种都给。 */
        fun acceptedTypes(intent: Intent): Set<String> {
            val raw = buildList {
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.let { addAll(it) }
                intent.type?.let { add(it) }
                // ACTION_PICK 常带的是 content://media/.../images/media 这种地址，没有类型
                if (intent.type == null) intent.data?.path?.let { p ->
                    if ("/images" in p) add("image/*")
                    if ("/video" in p) add("video/*")
                }
            }.map { it.lowercase().trim() }
            val out = HashSet<String>()
            for (t in raw) when {
                t == "*/*" || t == "*" -> { out += "image/*"; out += "video/*" }
                t.startsWith("vnd.android.cursor.dir/image") -> out += "image/*"
                t.startsWith("vnd.android.cursor.dir/video") -> out += "video/*"
                t.startsWith("image/") || t.startsWith("video/") -> out += t
            }
            return out.ifEmpty { setOf("image/*", "video/*") }
        }

        fun matches(item: MediaItem, accept: Set<String>): Boolean {
            val mime = item.mimeType.lowercase().ifBlank { if (item.isVideo) "video/*" else "image/*" }
            val major = mime.substringBefore('/')
            return accept.any { a ->
                val (am, sub) = a.substringBefore('/') to a.substringAfter('/', "*")
                am == major && (sub == "*" || a == mime)
            }
        }
    }
}

@Composable
private fun PickScreen(
    controller: app.zyll.feature.gallery.GalleryController,
    multiple: Boolean,
    accept: Set<String>,
    onCancel: () -> Unit,
    onDone: (List<MediaItem>, List<Uri>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = controller.repo
    var granted by remember { mutableStateOf(repo.hasFullAccess() || repo.hasPartialAccess()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = repo.hasFullAccess() || repo.hasPartialAccess()
    }
    LaunchedEffect(granted) { if (granted) controller.start() }

    val library by controller.library.collectAsState()
    val items = remember(library.items, accept) { library.items.filter { PickActivity.matches(it, accept) } }
    // 和相册里一样：可能敏感的照片先打码，动态照片打标记
    val hiddenState = controller.sensitive.hidden.collectAsState()
    val motionState = controller.motionIds.collectAsState()
    val flags = remember(hiddenState, motionState) {
        MediaFlags(
            hidden = hiddenState,
            backedUp = mutableStateOf(emptySet()),
            showBackup = mutableStateOf(false),
            onReveal = controller.sensitive::reveal,
            onPause = { controller.sensitive.pauseFor(10) },
            onDisable = { controller.sensitive.setEnabled(false) },
            onLeaveViewer = controller.sensitive::clearReveals,
            motion = motionState,
            livePair = { id -> controller.livePairs.value[id] },
        )
    }
    var density by remember { mutableStateOf(Density.Comfortable) }
    var selection by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    var loading by remember { mutableStateOf<Job?>(null) }
    // 下载网盘照片的进度：第几张、一共几张、这一张下了多少
    var dlIndex by remember { mutableStateOf(0) }
    var dlTotal by remember { mutableStateOf(0) }
    var dlFraction by remember { mutableStateOf(0f) }
    // 和谷歌相册一样分“照片 / 相册”两页；点进相册只看这个相册里的
    var tab by rememberSaveable { mutableStateOf(0) }
    var openAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    val albumItems = remember(library.local, accept) {
        library.local.filter { PickActivity.matches(it, accept) }.groupBy { it.bucketId }
    }
    val albums = remember(library.albums, albumItems) {
        library.albums.filter { albumItems[it.bucketId].orEmpty().isNotEmpty() }
    }
    // “仅在网盘”放在相簿列表最前面（本机已经删掉、只在网盘里的照片）
    val cloudOnly = remember(library.cloudOnly, accept) { library.cloudOnly.filter { PickActivity.matches(it, accept) } }
    val inCloudOnly = openAlbumId == CLOUD_ONLY_ID && cloudOnly.isNotEmpty()
    val openAlbum = albums.firstOrNull { it.bucketId == openAlbumId }
    val openTitle = if (inCloudOnly) tr(app.zyll.feature.gallery.R.string.quick_cloud_only) else openAlbum?.name
    val byId = remember(items, albumItems, cloudOnly) { (items + albumItems.values.flatten() + cloudOnly).associateBy { it.id } }
    val scope = rememberCoroutineScope()

    fun deliver(picked: List<MediaItem>) {
        if (picked.isEmpty() || loading != null) return
        val cloud = picked.filter { it.isCloud }
        if (cloud.isEmpty()) return onDone(picked, picked.map { it.uri })
        val files = GalleryImages.cloudFiles
        if (files == null) {
            Toast.makeText(context, tr(app.zyll.feature.gallery.R.string.cloud_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        dlIndex = 0
        dlTotal = cloud.size
        dlFraction = 0f
        loading = scope.launch {
            try {
                val got = HashMap<Long, Uri>()
                for ((i, c) in cloud.withIndex()) {
                    dlIndex = i
                    dlFraction = 0f
                    val f = files.file(c.uri, thumb = false) { p -> dlFraction = p.coerceIn(0f, 1f) }
                    got[c.id] = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                }
                onDone(picked, picked.map { got[it.id] ?: it.uri })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: tr(app.zyll.feature.gallery.R.string.cloud_unavailable), Toast.LENGTH_SHORT).show()
            } finally {
                loading = null
            }
        }
    }
    fun cancelLoading() {
        loading?.cancel()
        loading = null
    }

    BackHandler {
        when {
            loading != null -> cancelLoading()
            openTitle != null -> openAlbumId = null
            selection.isNotEmpty() -> selection = emptySet()
            else -> onCancel()
        }
    }

    @Composable
    fun Grid(list: List<MediaItem>) {
        CompositionLocalProvider(LocalMediaFlags provides flags) {
            MediaGrid(
                items = list,
                density = density,
                onDensityChange = { density = it },
                selection = selection,
                // 只能选一张时不进入多选，点一下就选好
                onSelectionChange = { s -> if (multiple) selection = s },
                onOpen = { item ->
                    if (multiple) selection = selection + item.id else deliver(listOf(item))
                },
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().topBarInsets().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (openTitle != null) {
                IconButton(onClick = { openAlbumId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr(app.zyll.feature.gallery.R.string.common_back)) }
            } else {
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, tr(R.string.pick_cancel)) }
            }
            Text(
                when {
                    selection.isNotEmpty() -> tr(R.string.pick_selected, selection.size)
                    openTitle != null -> openTitle
                    else -> tr(if (multiple) R.string.pick_title_multi else R.string.pick_title)
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (multiple && selection.isNotEmpty()) {
                TextButton(onClick = { deliver(selection.mapNotNull { byId[it] }) }) { Text(tr(R.string.pick_done)) }
            }
        }
        if (granted && openTitle == null) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr(R.string.pick_tab_photos)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr(R.string.pick_tab_albums)) })
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                !granted -> NoAccess { permLauncher.launch(pickPermissions()) }
                inCloudOnly -> Grid(cloudOnly)
                openAlbum != null -> Grid(albumItems[openAlbum.bucketId].orEmpty())
                tab == 1 -> if (library.loaded && albums.isEmpty() && cloudOnly.isEmpty()) EmptyHint(tr(R.string.pick_empty)) else LazyVerticalGrid(
                    GridCells.Adaptive(150.dp),
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (cloudOnly.isNotEmpty()) item(key = "cloud-only") {
                        PickAlbumCard(tr(app.zyll.feature.gallery.R.string.quick_cloud_only), cloudOnly.first(), cloudOnly.size) { openAlbumId = CLOUD_ONLY_ID }
                    }
                    items(albums, key = { it.bucketId }) { a ->
                        val list = albumItems[a.bucketId].orEmpty()
                        // 封面用这个相簿里第一张能选的（比如只能选图片时，封面不能是视频）
                        PickAlbumCard(a.name, list.firstOrNull() ?: a.cover, list.size) { openAlbumId = a.bucketId }
                    }
                }
                library.loaded && items.isEmpty() -> EmptyHint(tr(R.string.pick_empty))
                else -> Grid(items)
            }
            if (loading != null) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(32.dp)) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val overall = if (dlTotal > 0) ((dlIndex + dlFraction) / dlTotal).coerceIn(0f, 1f) else 0f
                            Text(
                                tr(R.string.pick_download_progress, (dlIndex + 1).coerceAtMost(dlTotal), dlTotal, (overall * 100).toInt()),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                            TextButton(onClick = ::cancelLoading, modifier = Modifier.padding(top = 8.dp)) { Text(tr(R.string.pick_cancel)) }
                        }
                    }
                }
            }
        }
    }
}

/** 选图页“仅在网盘”这一项的 id（不会和媒体库的相簿 id 撞上）。 */
private const val CLOUD_ONLY_ID = Long.MIN_VALUE

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

@Composable
private fun PickAlbumCard(name: String, cover: MediaItem, count: Int, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = thumbModel(cover),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)),
        )
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            tr(app.zyll.feature.gallery.R.string.common_items_n, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoAccess(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr(app.zyll.feature.gallery.R.string.permission_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            tr(app.zyll.feature.gallery.R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onRequest) { Text(tr(app.zyll.feature.gallery.R.string.permission_allow)) }
    }
}

private fun pickPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
