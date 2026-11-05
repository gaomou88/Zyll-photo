package app.zyll.crypto

/** 容器里装的是什么。数值写入头部，见 spec/crypto-format.md。 */
enum class BlobKind(val code: Int) {
    THUMBNAIL(0), PREVIEW(1), ORIGINAL(2), INDEX_SNAPSHOT(3), JOURNAL(4), OTHER(5);

    companion object {
        fun of(code: Int): BlobKind = entries.firstOrNull { it.code == code }
            ?: throw ZyllCryptoException("unknown blob kind")
    }
}

/** ZYLC v1 头部（92 字节）。 */
class ZylcHeader(
    val chunkLog2: Int,
    val kind: BlobKind,
    val blobId: ByteArray,
    val wrapNonce: ByteArray,
    val wrappedDek: ByteArray,
    val plainLen: Long,
) {
    val chunkSize: Int get() = 1 shl chunkLog2
    val chunkCount: Long get() = maxOf(1L, (plainLen + chunkSize - 1) / chunkSize)
    val totalLen: Long get() = Zylc.HEADER_LEN + plainLen + Primitives.TAG_LEN * chunkCount
    val blobIdHex: String get() = blobId.toHex()

    fun encode(): ByteArray {
        val out = ByteArray(Zylc.HEADER_LEN)
        Zylc.MAGIC.copyInto(out, 0)
        out[4] = Zylc.VERSION.toByte()
        out[5] = Zylc.ALG_AES256GCM_CHUNKED.toByte()
        out[6] = chunkLog2.toByte()
        out[7] = kind.code.toByte()
        blobId.copyInto(out, 8)
        wrapNonce.copyInto(out, 24)
        wrappedDek.copyInto(out, 36)
        u64(plainLen).copyInto(out, 84)
        return out
    }

    /** 第 i 块在整个文件中的起始偏移。 */
    fun chunkOffset(i: Long): Long = Zylc.HEADER_LEN + i * (chunkSize + Primitives.TAG_LEN)

    /** 第 i 块密文（含 tag）的长度。 */
    fun chunkCipherLen(i: Long): Int {
        val start = i * chunkSize
        val plain = minOf(chunkSize.toLong(), plainLen - start).coerceAtLeast(0)
        return plain.toInt() + Primitives.TAG_LEN
    }

    companion object {
        fun decode(bytes: ByteArray): ZylcHeader {
            if (bytes.size < Zylc.HEADER_LEN) throw ZyllCryptoException("header too short")
            if (!bytes.copyOfRange(0, 4).contentEquals(Zylc.MAGIC)) throw ZyllCryptoException("bad magic")
            if (bytes[4].toInt() != Zylc.VERSION) throw ZyllCryptoException("unsupported version")
            if (bytes[5].toInt() != Zylc.ALG_AES256GCM_CHUNKED) throw ZyllCryptoException("unsupported alg")
            val chunkLog2 = bytes[6].toInt()
            if (chunkLog2 !in 12..24) throw ZyllCryptoException("bad chunk size")
            val plainLen = readU64(bytes, 84)
            if (plainLen < 0) throw ZyllCryptoException("bad length")
            return ZylcHeader(
                chunkLog2 = chunkLog2,
                kind = BlobKind.of(bytes[7].toInt() and 0xff),
                blobId = bytes.copyOfRange(8, 24),
                wrapNonce = bytes.copyOfRange(24, 36),
                wrappedDek = bytes.copyOfRange(36, 84),
                plainLen = plainLen,
            )
        }
    }
}

/**
 * ZYLC v1：每个文件一把随机 DEK，按块做 AES-256-GCM，支持按块随机访问。
 */
object Zylc {
    const val HEADER_LEN = 92
    const val VERSION = 1
    const val ALG_AES256GCM_CHUNKED = 1
    const val DEFAULT_CHUNK_LOG2 = 16
    val MAGIC = "ZYLC".toByteArray(Charsets.US_ASCII)

    fun encrypt(
        plain: ByteArray,
        kind: BlobKind,
        kWrap: ByteArray,
        random: RandomSource = SecureRandomSource,
        chunkLog2: Int = DEFAULT_CHUNK_LOG2,
    ): ByteArray = encryptWith(
        plain, kind, kWrap,
        blobId = random.nextBytes(16),
        dek = random.nextBytes(Primitives.KEY_LEN),
        wrapNonce = random.nextBytes(Primitives.NONCE_LEN),
        chunkLog2 = chunkLog2,
    )

    /** 确定性版本：所有“随机”输入显式给出。测试向量用它保证两端逐字节一致。 */
    fun encryptWith(
        plain: ByteArray,
        kind: BlobKind,
        kWrap: ByteArray,
        blobId: ByteArray,
        dek: ByteArray,
        wrapNonce: ByteArray,
        chunkLog2: Int = DEFAULT_CHUNK_LOG2,
    ): ByteArray {
        require(chunkLog2 in 12..24)
        require(blobId.size == 16 && dek.size == Primitives.KEY_LEN && wrapNonce.size == Primitives.NONCE_LEN)
        val prefix = ByteArray(24).also {
            MAGIC.copyInto(it, 0)
            it[4] = VERSION.toByte(); it[5] = ALG_AES256GCM_CHUNKED.toByte()
            it[6] = chunkLog2.toByte(); it[7] = kind.code.toByte()
            blobId.copyInto(it, 8)
        }
        val wrappedDek = Primitives.aesGcmEncrypt(kWrap, wrapNonce, dek, prefix)
        val header = ZylcHeader(chunkLog2, kind, blobId, wrapNonce, wrappedDek, plain.size.toLong())
        val headerBytes = header.encode()
        val headerHash = Primitives.sha256(headerBytes)

        val out = ByteArray(header.totalLen.toInt())
        headerBytes.copyInto(out, 0)
        val n = header.chunkCount
        for (i in 0 until n) {
            val start = (i * header.chunkSize).toInt()
            val end = minOf(start + header.chunkSize, plain.size)
            val ct = Primitives.aesGcmEncrypt(dek, chunkNonce(i), plain.copyOfRange(start, end), chunkAad(headerHash, i, i == n - 1))
            ct.copyInto(out, header.chunkOffset(i).toInt())
        }
        return out
    }

    /** 解密并核对内容类型（比如不能把一张缩略图当成备份目录读）。 */
    fun decrypt(data: ByteArray, kWrap: ByteArray, expect: BlobKind): ByteArray {
        val h = ZylcHeader.decode(data.copyOfRange(0, minOf(HEADER_LEN, data.size)))
        if (h.kind != expect) throw ZyllCryptoException("unexpected blob kind ${h.kind}")
        return decrypt(data, kWrap)
    }

    fun decrypt(data: ByteArray, kWrap: ByteArray): ByteArray {
        val reader = Reader.open(data.copyOfRange(0, minOf(HEADER_LEN, data.size)), kWrap)
        val h = reader.header
        if (data.size.toLong() != h.totalLen) throw ZyllCryptoException("length mismatch")
        if (h.plainLen > Int.MAX_VALUE) throw ZyllCryptoException("too large for in-memory decrypt")
        val out = ByteArray(h.plainLen.toInt())
        for (i in 0 until h.chunkCount) {
            val off = h.chunkOffset(i).toInt()
            val chunk = reader.decryptChunk(i, data.copyOfRange(off, off + h.chunkCipherLen(i)))
            chunk.copyInto(out, (i * h.chunkSize).toInt())
        }
        return out
    }

    /**
     * 随机访问解密：先只读 92 字节头部解开 DEK，之后可按需解任意块。
     * 用于 Range 下载、CloudMediaProvider 的 openProxyFileDescriptor、大图分区解码。
     */
    class Reader private constructor(val header: ZylcHeader, private val dek: ByteArray, private val headerHash: ByteArray) {
        fun decryptChunk(i: Long, cipherAndTag: ByteArray): ByteArray {
            if (i < 0 || i >= header.chunkCount) throw ZyllCryptoException("chunk out of range")
            if (cipherAndTag.size != header.chunkCipherLen(i)) throw ZyllCryptoException("chunk length mismatch")
            return Primitives.aesGcmDecrypt(dek, chunkNonce(i), cipherAndTag, chunkAad(headerHash, i, i == header.chunkCount - 1))
        }

        companion object {
            fun open(headerBytes: ByteArray, kWrap: ByteArray): Reader {
                val h = ZylcHeader.decode(headerBytes)
                val dek = Primitives.aesGcmDecrypt(kWrap, h.wrapNonce, h.wrappedDek, headerBytes.copyOfRange(0, 24))
                return Reader(h, dek, Primitives.sha256(headerBytes.copyOfRange(0, HEADER_LEN)))
            }
        }
    }

    internal fun chunkNonce(i: Long): ByteArray = ByteArray(4) + u64(i)

    internal fun chunkAad(headerHash: ByteArray, i: Long, last: Boolean): ByteArray =
        headerHash + u64(i) + byteArrayOf(if (last) 1 else 0)
}
