package app.zyll.feature.gallery

import android.content.Context
import app.zyll.media.MediaItem
import app.zyll.media.MotionPhotos
import app.zyll.motion.LivePhoto
import app.zyll.motion.MotionPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 哪些照片是动态照片（给格子打标记、大图里显示“动态”按钮）：
 * - 本机照片：后台逐张看一眼文件写明的位置（XMP、三星目录、HEIC 的 mpvd，只读开头和结尾一点点），
 *   结果按“id + 修改时间 + 大小”记在文件里，照片没变就不再看；
 * - 只在网盘里的照片：用备份目录的 mo 标记；
 * - iPhone 实况照片：同相簿同名的照片和 MOV 配成一对。
 * 都只是提示：大图里播放时还会按内容完整找一遍。
 */
internal class MotionIndex(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "motion-scan.v1")
    /** 已看过的本机照片：key → 是不是动态照片。 */
    private val seen = HashMap<String, Boolean>()
    private var loaded = false
    private var job: Job? = null

    private val _local = MutableStateFlow<Set<Long>>(emptySet())
    /** 本机的动态照片 id。 */
    val local: StateFlow<Set<Long>> = _local.asStateFlow()

    private fun key(i: MediaItem) = "${i.id}:${i.modifiedAt}:${i.sizeBytes}"

    /** 本机照片列表变了：没看过的接着看。 */
    fun scan(items: List<MediaItem>) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            // 任意两轮都不同时跑（上一轮可能还没注意到自己被取消），seen 只有一轮在改
            mutex.withLock { scanLocked(items) }
        }
    }

    private val mutex = Mutex()

    private suspend fun scanLocked(items: List<MediaItem>): Unit = coroutineScope {
        run {
            if (!loaded) {
                runCatching {
                    file.forEachLine { line ->
                        val k = line.substringBeforeLast(' ', "")
                        if (k.isNotEmpty()) seen[k] = line.endsWith(" 1")
                    }
                }
                loaded = true
            }
            val candidates = items.filter { !it.isVideo && MotionPhoto.mayHaveMotion(it.mimeType, it.displayName) }
            fun publish() { _local.value = candidates.filter { seen[key(it)] == true }.mapTo(HashSet()) { it.id } }
            publish()
            var dirty = false
            var n = 0
            for (item in candidates) {
                if (!isActive) break
                val k = key(item)
                if (k in seen) continue
                seen[k] = MotionPhotos.find(app, item.uri, scan = false) != null
                dirty = true
                if (++n % 100 == 0) { publish(); save(candidates) }
            }
            publish()
            if (dirty) save(candidates)
        }
        Unit
    }

    /** 只留还在的照片，写回文件（先写临时文件再换上去）。 */
    private fun save(current: List<MediaItem>) {
        val keep = current.mapTo(HashSet(), ::key)
        val tmp = File(file.path + ".tmp")
        runCatching {
            tmp.bufferedWriter().use { w -> seen.forEach { (k, v) -> if (k in keep) w.write("$k ${if (v) 1 else 0}\n") } }
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    companion object {
        /**
         * 实况照片配对：静态照片 id → 那段 MOV。相簿按名字比（网盘里的照片只有相簿名）。
         * 同一个键有多段 MOV 时用第一段（列表按时间倒序）。
         */
        fun livePairs(items: List<MediaItem>): Map<Long, MediaItem> {
            val movs = HashMap<String, MediaItem>()
            for (i in items) {
                val k = LivePhoto.pairKey(i.bucketName, i.displayName, i.mimeType) ?: continue
                if (!k.still) movs.putIfAbsent(k.key, i)
            }
            if (movs.isEmpty()) return emptyMap()
            val out = HashMap<Long, MediaItem>()
            for (i in items) {
                val k = LivePhoto.pairKey(i.bucketName, i.displayName, i.mimeType) ?: continue
                if (k.still) movs[k.key]?.let { out[i.id] = it }
            }
            return out
        }
    }
}
