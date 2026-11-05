package app.zyll.cloud

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import app.zyll.media.tr
import app.zyll.core.cloud.R

/** 一张备份过的照片在本机的身份，给 AI 整理结果同步用。[mediaId] 的含义同 [CloudEntry.mediaId]。 */
data class AiPhotoRef(val mediaId: Long, val modified: Long, val takenAt: Long)

/**
 * App 提供：AI 整理结果（每张照片的向量）的导出和导入。
 *
 * 端到端加密开着时，网盘备份把结果按“照片内容指纹（SHA-256）→ 向量”加密存一份到网盘，
 * 换手机、重装后直接用，不用重新下载缩略图再整理一遍。按内容指纹而不是照片 id 对应，
 * 换了手机 id 全变了也能配上。没开加密时不同步（向量能反推出照片大概内容，不能明文放网盘）。
 */
interface AiResultsSync {
    /** 本机整理结果的版本号：有新结果就变大。 */
    fun version(): Long

    /** 当前模型（含版本）；没装模型、还没加载好时为 null。 */
    fun model(): String?

    /** 导出 [model] 下这些照片的结果：内容指纹（小写 hex）→ 压缩后的向量。 */
    suspend fun export(model: String, photos: Map<String, List<AiPhotoRef>>): Map<String, ByteArray>

    /** 导入别的手机算好的一批结果（内容指纹 → 向量），会分批多次调用。返回导入了几张。 */
    suspend fun import(model: String, results: Map<String, ByteArray>, photos: Map<String, List<AiPhotoRef>>): Int

    /** 这一轮的几批都导入完了（有导入时才调用）：可以统一刷新敏感判定等。 */
    suspend fun importFinished(model: String) {}
}

/**
 * 网盘上那份结果文件（加密前）的二进制格式，全部大端序：
 * ```
 * u32 magic "ZYAI" | u32 version=1 | u32 节数
 * 每节：UTF 模型名 | i64 最后更新毫秒 | u32 每条向量字节数 L | u32 条数 N | N × (32 字节 SHA-256 + L 字节向量)
 * ```
 * 几台手机用的模型档位可能不同，每个模型各存一节，最多保留最近更新的 [MAX_MODELS] 节。
 * 读写都是边读边处理，不把整个文件读进内存：几万张照片也只占一份本机导出的结果。
 */
internal object AiResultsFile {
    const val MAX_MODELS = 3
    private const val MAGIC = 0x5A594149 // "ZYAI"
    private const val VERSION = 1
    const val HASH_LEN = 32
    private const val MAX_REC = 64 * 1024

    class Section(val model: String, val at: Long, val recLen: Int, val count: Int, /** 记录在明文文件里的起始位置。 */ val offset: Long) {
        val bytes: Long get() = count.toLong() * (HASH_LEN + recLen)
    }

    /** 读出各节的位置（不读内容）。格式不对抛 [IOException]。 */
    fun sections(file: File): List<Section> = Counting(BufferedInputStream(FileInputStream(file))).use { c ->
        val d = DataInputStream(c)
        if (d.readInt() != MAGIC || d.readInt() != VERSION) throw IOException(tr(R.string.ai_results_unknown))
        val n = d.readInt()
        if (n !in 0..64) throw IOException(tr(R.string.ai_results_corrupt))
        List(n) {
            val model = d.readUTF()
            val at = d.readLong()
            val recLen = d.readInt()
            val count = d.readInt()
            if (recLen !in 1..MAX_REC || count < 0) throw IOException(tr(R.string.ai_results_corrupt))
            val s = Section(model, at, recLen, count, c.pos)
            skipFully(d, s.bytes)
            s
        }.also { if (d.read() != -1) throw IOException(tr(R.string.ai_results_trailing)) }
    }

    /** 逐条读一节：(小写 hex 指纹, 32 字节指纹, 向量)。 */
    inline fun forEach(file: File, s: Section, block: (String, ByteArray, ByteArray) -> Unit) {
        open(file, s.offset).use { d ->
            repeat(s.count) {
                val h = ByteArray(HASH_LEN)
                d.readFully(h)
                val v = ByteArray(s.recLen)
                d.readFully(v)
                block(h.hex(), h, v)
            }
        }
    }

    /**
     * 把本机结果 [mine]（hex 指纹 → 向量）合进旧文件 [old] 的 [model] 那一节，写到 [out]。
     * 本机的优先，别的手机传的、本机没有的照样保留；节数超过上限时丢掉最久没更新的。
     * 没有任何变化时不写，返回 false。
     */
    fun merge(old: File?, oldSections: List<Section>, model: String, mine: Map<String, ByteArray>, now: Long, out: File): Boolean {
        val valid = mine.filter { (h, v) -> h.length == HASH_LEN * 2 && h.all { it in '0'..'9' || it in 'a'..'f' } && v.isNotEmpty() }
        val recLen = valid.values.firstOrNull()?.size ?: oldSections.firstOrNull { it.model == model }?.recLen ?: return false
        val mineOk = valid.filterValues { it.size == recLen }
        // 同一个模型的向量长度应该一样；对不上的旧节（损坏）不要了
        val cur = oldSections.firstOrNull { it.model == model && it.recLen == recLen }
        val others = oldSections.filter { it.model != model }
        val keep = others.sortedByDescending { it.at }.take(MAX_MODELS - 1)
        var changed = keep.size < others.size || (cur == null && oldSections.any { it.model == model })

        // 第一遍：旧节里已有哪些本机的指纹、内容一不一样
        val present = HashSet<String>()
        if (cur != null && old != null) forEach(old, cur) { hex, _, v ->
            mineOk[hex]?.let { m ->
                present += hex
                if (!m.contentEquals(v)) changed = true
            }
        }
        if (mineOk.size > present.size) changed = true
        if (!changed) return false

        DataOutputStream(BufferedOutputStream(out.outputStream())).use { o ->
            val count = (cur?.count ?: 0) + (mineOk.size - present.size)
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeInt(keep.size + if (count > 0) 1 else 0)
            for (s in keep) {
                o.writeUTF(s.model); o.writeLong(s.at); o.writeInt(s.recLen); o.writeInt(s.count)
                open(old!!, s.offset).use { d -> copy(d, o, s.bytes) }
            }
            if (count > 0) {
                o.writeUTF(model); o.writeLong(now); o.writeInt(recLen); o.writeInt(count)
                if (cur != null && old != null) forEach(old, cur) { hex, raw, v ->
                    o.write(raw)
                    o.write(mineOk[hex] ?: v)
                }
                for ((hex, v) in mineOk) if (hex !in present) {
                    o.write(unhex(hex))
                    o.write(v)
                }
            }
        }
        return true
    }

    fun open(file: File, offset: Long): DataInputStream {
        val input = FileInputStream(file)
        input.channel.position(offset)
        return DataInputStream(BufferedInputStream(input, 1 shl 16))
    }

    private fun skipFully(d: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = d.skip(left)
            if (s <= 0) {
                if (d.read() < 0) throw EOFException(tr(R.string.ai_results_truncated))
                left--
            } else {
                left -= s
            }
        }
    }

    private fun copy(d: DataInputStream, o: DataOutputStream, n: Long) {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val r = d.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException(tr(R.string.ai_results_truncated))
            o.write(buf, 0, r)
            left -= r
        }
    }

    fun ByteArray.hex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** 记着读到了第几个字节。 */
    private class Counting(input: InputStream) : FilterInputStream(input) {
        var pos = 0L
        override fun read(): Int = super.read().also { if (it >= 0) pos++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) pos += it }
        override fun skip(n: Long): Long = super.skip(n).also { pos += it }
    }
}
