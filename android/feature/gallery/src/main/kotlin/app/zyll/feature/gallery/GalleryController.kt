package app.zyll.feature.gallery

import android.content.Context
import app.zyll.ai.AiSearch
import app.zyll.media.Album
import app.zyll.media.MediaItem
import app.zyll.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class LibraryState(
    val loaded: Boolean = false,
    /** 时间线上显示的：本机照片（不含已归档的）+ 只在网盘里的照片，按拍摄时间倒序。 */
    val items: List<MediaItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    /** 已归档：不在时间线上显示，在“相簿 → 归档”里。 */
    val archived: List<MediaItem> = emptyList(),
    /** 本机已经删掉、只在网盘里的照片。 */
    val cloudOnly: List<MediaItem> = emptyList(),
    /** 本机的全部照片（含归档）。 */
    val local: List<MediaItem> = emptyList(),
) {
    /** id → 照片，按需建一次。 */
    val byId: Map<Long, MediaItem> by lazy { (items + archived).associateBy { it.id } }
}

/**
 * 相册数据的唯一来源：读取媒体库、监听变化、把新照片交给 AI 建索引。
 * 由 App 持有（跨界面共享），界面只读 [library]。
 */
class GalleryController(
    private val context: Context,
    val repo: MediaRepository,
    val ai: AiSearch,
    private val scope: CoroutineScope,
    val sensitive: SensitiveFilter,
) {
    private val prefs = context.applicationContext.getSharedPreferences("gallery-archive", Context.MODE_PRIVATE)

    private val _local = MutableStateFlow<List<MediaItem>?>(null)
    /** 本机照片（不含网盘照片），给网盘模块用来判断哪些照片本机已经没了。 */
    val localItems: StateFlow<List<MediaItem>?> = _local.asStateFlow()

    // null = 网盘列表还没拿到（刚启动 / 未登录）；非 null = 已知（可能是空列表）
    private val _cloud = MutableStateFlow<List<MediaItem>?>(null)
    private val _archived = MutableStateFlow(prefs.getStringSet(KEY, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet())
    val archivedIds: StateFlow<Set<Long>> = _archived.asStateFlow()
    /** 手动取消归档过的，自动归档时不再动它们。 */
    private var kept = prefs.getStringSet(KEY_KEPT, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    private val _screenshotDays = MutableStateFlow(prefs.getInt(KEY_SCREENSHOT_DAYS, 0))
    /** 截图拍下多少天后自动归档；0 表示不自动归档。 */
    val screenshotArchiveDays: StateFlow<Int> = _screenshotDays.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private var watchJob: Job? = null
    private var cloudJob: Job? = null

    init {
        // 让 AI 能扫描只在网盘里的照片：把网盘缩略图解码成 Bitmap
        ai.cloudThumbnailLoader = loader@{ item ->
            val files = app.zyll.feature.gallery.ui.GalleryImages.cloudFiles ?: return@loader null
            val f = runCatching { files.file(item.uri, thumb = true) }.getOrNull() ?: return@loader null
            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        }
        // 敏感遮蔽需要知道哪些是网盘照片，好在扫描完成前先遮住
        sensitive.attachCloudIds(_cloud.map { list ->
            // Android 10 以下扫不了视频，这些会永远扫不到，就别拿它们当“待扫描”去遮
            list.orEmpty().filter { !it.isVideo || android.os.Build.VERSION.SDK_INT >= 29 }.mapTo(HashSet()) { it.id }
        })
        scope.launch {
            combine(_local, _cloud, _archived) { local, cloudOrNull, archived ->
                if (local == null) return@combine LibraryState()
                val cloud = cloudOrNull.orEmpty()
                val (hidden, visible) = local.partition { it.id in archived }
                val merged = if (cloud.isEmpty()) visible else (visible + cloud).sortedByDescending { it.takenAt }
                LibraryState(true, merged, repo.albums(local), hidden, cloud, local)
            }.flowOn(Dispatchers.Default).collect { _library.value = it }
        }
    }

    /** 拿到读取权限后调用。 */
    @OptIn(FlowPreview::class)
    fun start() {
        started = true
        refresh()
        watch()
    }

    private var started = false

    @OptIn(FlowPreview::class)
    private fun watch() {
        if (watchJob == null) {
            watchJob = scope.launch { repo.changes().debounce(600).collect { refresh() } }
        }
    }

    /** 回到前台：继续建索引，重新监听媒体库，并补扫一次离开期间的变化。 */
    fun onAppForeground() {
        powerReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        powerReceiver = null
        ai.backgroundPaused.value = false
        if (!started) return
        watch()
        refresh()
    }

    /** 退到后台：停止监听媒体库；没在充电就暂停建索引，省电。 */
    fun onAppBackground() {
        watchJob?.cancel()
        watchJob = null
        // 退到后台就把临时放行清掉：再回来时敏感照片重新打码
        sensitive.clearReveals()
        ai.backgroundPaused.value = !isCharging()
        // 在后台期间插拔充电器时随时切换：插上继续建索引，拔掉就暂停
        if (powerReceiver == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    ai.backgroundPaused.value = intent.action != android.content.Intent.ACTION_POWER_CONNECTED
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                context.applicationContext, r, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            powerReceiver = r
        }
    }

    private var powerReceiver: android.content.BroadcastReceiver? = null

    private fun isCharging(): Boolean =
        context.applicationContext.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true

    fun refresh() {
        scope.launch {
            val items = runCatching { repo.loadAll() }.getOrDefault(emptyList())
            applyPendingArchive(items)
            autoArchive(items)
            _local.value = items
            ai.startIndexing(items + _cloud.value.orEmpty())
        }
    }

    /** 网盘模块提供“只在网盘里的照片”。 */
    fun attachCloud(items: Flow<List<MediaItem>>) {
        cloudJob?.cancel()
        cloudJob = scope.launch {
            items.collect {
                _cloud.value = it
                if (started) ai.startIndexing((_local.value ?: emptyList()) + it)
            }
        }
    }

    fun setArchived(ids: Collection<Long>, archived: Boolean) {
        val next = if (archived) _archived.value + ids else _archived.value - ids.toSet()
        kept = if (archived) kept - ids.toSet() else kept + ids
        _archived.value = next
        prefs.edit()
            .putStringSet(KEY, next.mapTo(HashSet()) { it.toString() })
            .putStringSet(KEY_KEPT, kept.mapTo(HashSet()) { it.toString() })
            .apply()
    }

    fun setScreenshotArchiveDays(days: Int) {
        _screenshotDays.value = days.coerceAtLeast(0)
        prefs.edit().putInt(KEY_SCREENSHOT_DAYS, _screenshotDays.value).apply()
        _local.value?.let { autoArchive(it) }
    }

    /** 把超过设定天数的截图归档（手动取消归档过的除外）。 */
    private fun autoArchive(items: List<MediaItem>) {
        val days = _screenshotDays.value
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val archived = _archived.value
        val due = items.filter { it.isScreenshot && it.takenAt < cutoff && it.id !in archived && it.id !in kept }
        if (due.isEmpty()) return
        val next = archived + due.map { it.id }
        _archived.value = next
        prefs.edit().putStringSet(KEY, next.mapTo(HashSet()) { it.toString() }).apply()
    }

    /**
     * 换手机时随网盘备份目录一起保存/恢复的相册设置：截图自动归档天数、快捷入口、归档了哪些照片。
     * 新手机上照片 id 不一样，归档按“文件名|拍摄时间”认。
     */
    fun exportSettings(): org.json.JSONObject {
        val byId = _local.value?.associateBy { it.id }.orEmpty()
        val keys = org.json.JSONArray()
        _archived.value.forEach { id -> byId[id]?.let { keys.put(archiveKey(it)) } }
        val quick = context.applicationContext.getSharedPreferences("gallery", Context.MODE_PRIVATE).getString(KEY_QUICK, null)
        return org.json.JSONObject()
            .put("screenshotDays", _screenshotDays.value)
            .put("archive", keys)
            .apply { if (quick != null) put("quick", quick) }
    }

    fun importSettings(o: org.json.JSONObject) {
        if (o.has("screenshotDays")) {
            _screenshotDays.value = o.optInt("screenshotDays").coerceAtLeast(0)
            prefs.edit().putInt(KEY_SCREENSHOT_DAYS, _screenshotDays.value).apply()
        }
        o.optString("quick").takeIf { it.isNotEmpty() }?.let { q ->
            context.applicationContext.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().putString(KEY_QUICK, q).apply()
        }
        o.optJSONArray("archive")?.let { arr ->
            val keys = (0 until arr.length()).mapTo(HashSet()) { arr.optString(it) }
            // 本机照片可能还没读完，先存下来，读到照片时再对上
            prefs.edit().putStringSet(KEY_PENDING_ARCHIVE, keys).apply()
        }
        _local.value?.let { items ->
            applyPendingArchive(items)
            autoArchive(items)
        }
    }

    private fun applyPendingArchive(items: List<MediaItem>) {
        val pending = prefs.getStringSet(KEY_PENDING_ARCHIVE, null) ?: return
        val hits = items.filter { archiveKey(it) in pending }.map { it.id }
        val next = _archived.value + hits
        _archived.value = next
        prefs.edit().putStringSet(KEY, next.mapTo(HashSet()) { it.toString() }).remove(KEY_PENDING_ARCHIVE).apply()
    }

    private fun archiveKey(item: MediaItem) = item.displayName + "|" + item.takenAt

    suspend fun trash(): List<MediaItem> = repo.loadTrash()

    fun byIds(ids: List<Long>): List<MediaItem> {
        val map = _library.value.byId
        return ids.mapNotNull { map[it] }
    }

    private companion object {
        const val KEY = "archived"
        const val KEY_KEPT = "keptOut"
        const val KEY_SCREENSHOT_DAYS = "screenshotDays"
        const val KEY_PENDING_ARCHIVE = "pendingArchive"
        const val KEY_QUICK = "quickCollections"
    }
}
