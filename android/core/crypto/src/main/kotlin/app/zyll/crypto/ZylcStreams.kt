package app.zyll.crypto

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * 边读边加密的 ZYLC 流：内存里同时只有一块（默认 64 KiB），几 GB 的视频也能直接上传。
 * 输出和 [Zylc.encryptWith] 逐字节一致；总长度在开始前就能算出（[cipherLength]），上传时可以先告诉服务器。
 */
class ZylcEncryptingInputStream internal constructor(
    private val source: InputStream,
    private val plainLen: Long,
    kind: BlobKind,
    kWrap: ByteArray,
    blobId: ByteArray,
    private val dek: ByteArray,
    wrapNonce: ByteArray,
    chunkLog2: Int,
) : InputStream() {
    constructor(
        source: InputStream,
        plainLen: Long,
        kind: BlobKind,
        kWrap: ByteArray,
        random: RandomSource = SecureRandomSource,
        chunkLog2: Int = Zylc.DEFAULT_CHUNK_LOG2,
    ) : this(
        source, plainLen, kind, kWrap,
        blobId = random.nextBytes(16),
        dek = random.nextBytes(Primitives.KEY_LEN),
        wrapNonce = random.nextBytes(Primitives.NONCE_LEN),
        chunkLog2 = chunkLog2,
    )

    val header: ZylcHeader
    private val headerHash: ByteArray
    private var buf: ByteArray
    private var pos = 0
    private var chunk = 0L

    init {
        require(chunkLog2 in 12..24)
        require(plainLen >= 0)
        val prefix = ByteArray(24).also {
            Zylc.MAGIC.copyInto(it, 0)
            it[4] = Zylc.VERSION.toByte(); it[5] = Zylc.ALG_AES256GCM_CHUNKED.toByte()
            it[6] = chunkLog2.toByte(); it[7] = kind.code.toByte()
            blobId.copyInto(it, 8)
        }
        val wrappedDek = Primitives.aesGcmEncrypt(kWrap, wrapNonce, dek, prefix)
        header = ZylcHeader(chunkLog2, kind, blobId, wrapNonce, wrappedDek, plainLen)
        buf = header.encode()
        headerHash = Primitives.sha256(buf)
    }

    /** 密文总长度（字节）。 */
    val cipherLength: Long get() = header.totalLen

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) {
            if (!nextChunk()) return -1
        }
        val n = minOf(len, buf.size - pos)
        buf.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    private fun nextChunk(): Boolean {
        val count = header.chunkCount
        if (chunk >= count) return false
        val start = chunk * header.chunkSize
        val size = minOf(header.chunkSize.toLong(), plainLen - start).toInt()
        val plain = ByteArray(size)
        var got = 0
        while (got < size) {
            val n = source.read(plain, got, size - got)
            if (n < 0) throw EOFException("source ended after ${start + got} of $plainLen bytes")
            got += n
        }
        buf = Primitives.aesGcmEncrypt(dek, Zylc.chunkNonce(chunk), plain, Zylc.chunkAad(headerHash, chunk, chunk == count - 1))
        pos = 0
        chunk++
        return true
    }

    override fun close() = source.close()

    companion object {
        /** 确定性版本，测试用。 */
        fun with(
            source: InputStream, plainLen: Long, kind: BlobKind, kWrap: ByteArray,
            blobId: ByteArray, dek: ByteArray, wrapNonce: ByteArray, chunkLog2: Int = Zylc.DEFAULT_CHUNK_LOG2,
        ) = ZylcEncryptingInputStream(source, plainLen, kind, kWrap, blobId, dek, wrapNonce, chunkLog2)
    }
}

/**
 * 流式解密：逐块读、逐块校验、逐块写出。返回明文字节数。
 * [expect] 不为空时要求文件类型一致（比如原图位置不能被换成一张缩略图的密文）。
 */
fun Zylc.decryptStream(input: InputStream, kWrap: ByteArray, out: OutputStream, expect: BlobKind? = null): Long {
    fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw ZyllCryptoException("truncated file")
            got += r
        }
        return b
    }
    val reader = Zylc.Reader.open(readFully(HEADER_LEN), kWrap)
    val h = reader.header
    if (expect != null && h.kind != expect) throw ZyllCryptoException("unexpected blob kind ${h.kind}")
    var written = 0L
    for (i in 0 until h.chunkCount) {
        val plain = reader.decryptChunk(i, readFully(h.chunkCipherLen(i)))
        out.write(plain)
        written += plain.size
    }
    if (input.read() != -1) throw ZyllCryptoException("trailing data")
    return written
}
