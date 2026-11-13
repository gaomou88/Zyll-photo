package app.zyll.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.zyll.motion.MotionPhoto
import app.zyll.motion.MotionVideo
import app.zyll.motion.RandomRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 动态照片在安卓上的读写：识别规则在 core/motion（纯 JVM），这里负责按位置读文件、把视频切出来给播放器。
 * 只读原文件，不改它；切出来的视频放在缓存里，超过上限从最久没用的删。
 */
object MotionPhotos {
    private const val CACHE_BYTES = 200L shl 20

    /** 本机照片（content:// 或 file://）里的视频位置；不是动态照片或读不了时返回 null。 */
    suspend fun find(context: Context, uri: Uri, scan: Boolean): MotionVideo? = withContext(Dispatchers.IO) {
        runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            // 关通道时连带关掉文件描述符（只关一次）
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { ch -> MotionPhoto.find(reader(ch), ch.size(), scan) }
        }.getOrNull()
    }

    /** 本地文件（比如从网盘下载到缓存的原图）里的视频位置。 */
    suspend fun find(file: File, scan: Boolean): MotionVideo? = withContext(Dispatchers.IO) {
        runCatching { RandomAccessFile(file, "r").use { f -> MotionPhoto.find(reader(f.channel), f.length(), scan) } }.getOrNull()
    }

    /**
     * 把视频切到缓存文件里，返回它（同一张照片第二次直接用缓存）。[key] 要能区分照片和版本，比如 id + 修改时间。
     * [open] 打开原文件的读通道。
     */
    suspend fun extract(context: Context, key: String, video: MotionVideo, open: () -> FileChannel): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val ext = if (video.mime == "video/quicktime") "mov" else "mp4"
        val out = File(dir, safeName(key) + ".$ext")
        // 同一张照片同时切两次（快速点两下、翻走又翻回来）：排队，第二次直接用第一次的结果
        val lock = synchronized(locks) { locks.getOrPut(out.name) { Mutex() } }
        lock.withLock { extractLocked(dir, out, video, open) }
    }

    private fun extractLocked(dir: File, out: File, video: MotionVideo, open: () -> FileChannel): File {
        if (out.isFile && out.length() == video.length) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        // 每次一个自己的临时文件，不会和别的写入混在一起
        val tmp = File.createTempFile(out.name, ".part", dir)
        try {
            open().use { src ->
                tmp.outputStream().channel.use { dst ->
                    var pos = video.offset
                    val end = video.offset + video.length
                    while (pos < end) {
                        val n = src.transferTo(pos, end - pos, dst)
                        if (n <= 0) throw IOException("short read")
                        pos += n
                    }
                }
            }
            if (tmp.length() != video.length || !tmp.renameTo(out)) throw IOException("write failed")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        trim(dir)
        return out
    }

    private val locks = HashMap<String, Mutex>()
    private const val DIR = "motion"
    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** 网盘照片切出来的视频缓存名的前缀；[provider] 为 null 时匹配所有网盘照片。 */
    fun cloudPrefix(provider: String?): String = safeName("c" + (provider?.let { "$it-" } ?: ""))

    /** 网盘照片的缓存 key：带上账号，退出账号时能按前缀删掉。 */
    fun cloudKey(provider: String, remoteKey: String, size: Long): String = "c$provider-${remoteKey.hashCode()}-$size"

    /** 删掉名字以 [prefix] 开头的缓存视频（退出账号、删掉密钥时：加密照片切出来的视频是明文）。 */
    fun clearCache(context: Context, prefix: String) {
        File(context.cacheDir, DIR).listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    /** 删掉 [key] 这一张切出来的视频（照片从网盘删掉时：缓存里的是明文）。 */
    fun forget(context: Context, key: String) {
        val name = safeName(key)
        File(context.cacheDir, DIR).listFiles()?.filter { it.name.startsWith("$name.") }?.forEach { it.delete() }
    }

    /** 本机照片：找到视频并切出来；不是动态照片返回 null。 */
    suspend fun extract(context: Context, item: MediaItem): File? {
        val v = find(context, item.uri, scan = true) ?: return null
        return extract(context, "l${item.id}-${item.modifiedAt}-${item.sizeBytes}", v) {
            // 关通道时连带关掉文件描述符
            ParcelFileDescriptor.AutoCloseInputStream(context.contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("open failed")).channel
        }
    }

    /** 本地文件（网盘原图的缓存）：找到视频并切出来。 */
    suspend fun extract(context: Context, key: String, file: File): File? {
        val v = find(file, scan = true) ?: return null
        return extract(context, key, v) { RandomAccessFile(file, "r").channel }
    }

    private fun reader(ch: FileChannel) = RandomRead { off, len ->
        val buf = ByteBuffer.allocate(len)
        var pos = off
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        buf.array().copyOf(buf.position())
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
