package app.zyll.motion

/**
 * 动态照片：找出照片文件里嵌着的那段短视频。规则见 spec/motion-photo.md，和网页版 src/lib/motion.ts 逐条一致，
 * 两边跑同一份测试向量（spec/test-vectors/motion）。
 *
 * - Google Motion Photo（Pixel、小米、OPPO、vivo、荣耀等）：XMP 的 Container 目录写着视频长度，视频在文件末尾；
 * - 旧的 Google MicroVideo：XMP 的 MicroVideoOffset（从文件末尾往前数）；
 * - 三星：文件末尾 SEF 目录里名为 MotionPhoto_Data 的一项；
 * - HEIC：顶层的 mpvd 盒子；
 * - 其他直接把 MP4 接在图片后面的：完整扫描（只在真要播放时做）。
 *
 * 只读，不改文件。
 */
data class MotionVideo(val offset: Long, val length: Long, val mime: String)

/** 按需读文件的一段；越界时返回较短的结果。 */
fun interface RandomRead {
    fun read(offset: Long, length: Int): ByteArray
}

object MotionPhoto {
    private const val HEAD_BYTES = 256 * 1024
    private const val SCAN_STEP = 1 shl 20
    private const val MAX_BOXES = 4096

    /**
     * 找照片里的视频。[scan] 为 false 时只看文件自己写明的位置（XMP、三星目录、HEIC 的 mpvd），读得很少，
     * 适合给格子打标记；为 true 时还会把整个文件扫一遍找接在后面的 MP4（播放、备份时用）。
     */
    fun find(read: RandomRead, size: Long, scan: Boolean = false): MotionVideo? {
        if (size < 64) return null
        val head = read.read(0, minOf(size, HEAD_BYTES.toLong()).toInt())
        val jpeg = head.size >= 3 && u8(head, 0) == 0xFF && u8(head, 1) == 0xD8 && u8(head, 2) == 0xFF
        val heif = ascii(head, 4, 4) == "ftyp"
        if (!jpeg && !heif) return null

        for (n in xmpTailLengths(ascii(head, 0, head.size))) {
            if (n >= size) continue
            videoAt(read, size, size - n)?.let { return it }
        }
        samsungStart(read, size)?.let { at -> videoAt(read, size, at)?.let { return it } }
        var scanFrom = 1L
        if (heif) {
            var pos = 0L
            var n = 0
            while (n++ < MAX_BOXES && pos < size) {
                val b = boxAt(read, size, pos) ?: break
                // Pixel 等：视频放在 mpvd 盒子里；有的机型直接把整段 MP4（从 ftyp 开始）接在后面
                val at = when {
                    b.type == "mpvd" -> pos + b.header
                    b.type == "ftyp" && pos > 0 -> pos
                    else -> -1L
                }
                if (at > 0) videoAt(read, size, at)?.let { return it }
                pos += b.length
            }
            scanFrom = maxOf(1L, pos)
        }
        if (!scan) return null
        // 扫 "ftyp"：每段多读 7 个字节，跨段的也能找到
        var off = scanFrom
        while (off < size) {
            val chunk = read.read(off, SCAN_STEP + 7)
            var i = 0
            while (i + 8 <= chunk.size) {
                if (chunk[i + 4] == 'f'.code.toByte() && chunk[i + 5] == 't'.code.toByte() &&
                    chunk[i + 6] == 'y'.code.toByte() && chunk[i + 7] == 'p'.code.toByte()
                ) {
                    videoAt(read, size, off + i)?.let { return it }
                }
                i++
            }
            off += SCAN_STEP
        }
        return null
    }

    /** 照片能不能带视频（只有 JPEG 和 HEIC/HEIF 会）。 */
    fun mayHaveMotion(mime: String, name: String = ""): Boolean =
        Regex("^image/(jpeg|jpg|heic|heif)$").matches(mime.lowercase()) || Regex("\\.(jpe?g|heic|heif)$", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** XMP 里写的“视频从文件末尾往前数多少字节”，按出现顺序、去重。 */
    fun xmpTailLengths(text: String): List<Long> {
        val out = ArrayList<Long>()
        fun add(v: String?) {
            val n = v?.toLongOrNull() ?: return
            if (n > 0 && n <= MAX_SAFE && n !in out) out += n
        }
        fun attr(name: String, s: String): String? {
            val m = Regex("$name\\s*=\\s*[\"'](\\d+)[\"']|$name>\\s*(\\d+)\\s*<").find(s) ?: return null
            return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null }
        }
        val videoItem = Regex("Item:Mime\\s*=\\s*[\"']video/|Item:Mime>\\s*video/|Item:Semantic\\s*=\\s*[\"']MotionPhoto[\"']|Item:Semantic>\\s*MotionPhoto\\s*<")
        for (li in text.split(Regex("<rdf:li[\\s>]")).drop(1)) {
            val item = li.split(Regex("</rdf:li>"))[0]
            if (videoItem.containsMatchIn(item)) add(attr("Item:Length", item))
        }
        add(attr("MicroVideoOffset", text))
        return out
    }

    /**
     * 从 [at] 开始是不是一段完整的 MP4 / MOV：第一个盒子是 ftyp（16 字节到 1 KiB），之后一串合法的盒子，
     * 里面要有 moov 和 mdat。长度算到最后一个合法盒子为止（后面可以跟着别的数据，比如三星的 SEF 目录）。
     */
    fun videoAt(read: RandomRead, size: Long, at: Long): MotionVideo? {
        if (at <= 0 || at + 16 > size) return null
        val first = boxAt(read, size, at) ?: return null
        if (first.type != "ftyp" || first.length > 1024 || first.length < 16) return null
        val brand = ascii(read.read(at + first.header, 4), 0, 4)
        var pos = at
        var moov = false
        var mdat = false
        var n = 0
        while (n++ < MAX_BOXES) {
            val b = boxAt(read, size, pos) ?: break
            if (b.type == "moov") moov = true else if (b.type == "mdat") mdat = true
            pos += b.length
            if (pos >= size) break
        }
        if (!moov || !mdat) return null
        return MotionVideo(at, pos - at, if (brand == "qt  ") "video/quicktime" else "video/mp4")
    }

    private class Box(val type: String, val length: Long, val header: Int)

    private fun boxAt(read: RandomRead, size: Long, pos: Long): Box? {
        if (pos + 8 > size) return null
        val h = read.read(pos, 16)
        if (h.size < 8 || !isBoxType(h, 4)) return null
        var len = u32be(h, 0)
        var header = 8
        if (len == 1L) {
            if (h.size < 16) return null
            val hi = u32be(h, 8)
            if (hi > 0x1fffff) return null
            len = hi * (1L shl 32) + u32be(h, 12)
            header = 16
        } else if (len == 0L) {
            len = size - pos
        }
        if (len < header || pos + len > size) return null
        return Box(ascii(h, 4, 4), len, header)
    }

    /** 三星 SEF：文件以 "SEFT" 结尾，目录里名字是 MotionPhoto_Data 的那项就是视频。返回视频开始的位置。 */
    private fun samsungStart(read: RandomRead, size: Long): Long? {
        if (size < 32) return null
        val tail = read.read(size - 8, 8)
        if (tail.size < 8 || ascii(tail, 4, 4) != "SEFT") return null
        val dirLen = u32le(tail, 0)
        val dir = size - 8 - dirLen
        if (dirLen < 12 || dir < 0) return null
        val d = read.read(dir, minOf(dirLen, 12L + 64 * 12).toInt())
        if (d.size < 12 || ascii(d, 0, 4) != "SEFH") return null
        val count = minOf(u32le(d, 8), 64L).toInt()
        for (i in 0 until count) {
            val e = 12 + i * 12
            if (e + 12 > d.size) break
            val back = u32le(d, e + 4)
            val len = u32le(d, e + 8)
            val data = dir - back
            if (back == 0L || data < 0 || data + len > dir) continue
            val h = read.read(data, 8 + 64)
            if (h.size < 8) continue
            val nameLen = u32le(h, 4)
            if (nameLen == 0L || nameLen > 64 || 8 + nameLen > len) continue
            if (ascii(h, 8, nameLen.toInt()) == "MotionPhoto_Data") return data + 8 + nameLen
        }
        return null
    }

    private const val MAX_SAFE = 9007199254740991L // 和网页版的 Number.isSafeInteger 一致

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u32be(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun u32le(b: ByteArray, i: Int): Long =
        u8(b, i).toLong() or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
    private fun isBoxType(b: ByteArray, at: Int): Boolean = (at until at + 4).all { u8(b, it) in 0x20..0x7e }

    /** 按 Latin-1 把字节当字符（和网页版的 String.fromCharCode 一致）。 */
    private fun ascii(b: ByteArray, at: Int, n: Int): String {
        if (at >= b.size) return ""
        val end = minOf(b.size, at + n)
        val chars = CharArray(end - at) { (b[at + it].toInt() and 0xFF).toChar() }
        return String(chars)
    }
}
