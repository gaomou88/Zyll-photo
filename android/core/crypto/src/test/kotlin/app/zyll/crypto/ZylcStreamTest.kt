package app.zyll.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ZylcStreamTest {
    private val key = ByteArray(32) { it.toByte() }
    private val blobId = ByteArray(16) { (it + 1).toByte() }
    private val dek = ByteArray(32) { (it * 3).toByte() }
    private val nonce = ByteArray(12) { (it * 7).toByte() }

    private fun streamEncrypt(plain: ByteArray, chunkLog2: Int = 12): Pair<ByteArray, Long> {
        val s = ZylcEncryptingInputStream.with(ByteArrayInputStream(plain), plain.size.toLong(), BlobKind.ORIGINAL, key, blobId, dek, nonce, chunkLog2)
        // 用奇怪的读取长度，确认分块边界处理正确
        val out = ByteArrayOutputStream()
        val buf = ByteArray(777)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray() to s.cipherLength
    }

    @Test
    fun matchesInMemoryEncryption() {
        for (size in listOf(0, 1, 4095, 4096, 4097, 3 * 4096 + 5, 100_000)) {
            val plain = Random(size).nextBytes(size)
            val expected = Zylc.encryptWith(plain, BlobKind.ORIGINAL, key, blobId, dek, nonce, 12)
            val (actual, len) = streamEncrypt(plain)
            assertArrayEquals(expected, actual, "size $size")
            assertEquals(expected.size.toLong(), len)
        }
    }

    @Test
    fun streamDecryptRoundTrip() {
        val plain = Random(9).nextBytes(50_000)
        val (ct, _) = streamEncrypt(plain)
        val out = ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), Zylc.decryptStream(ByteArrayInputStream(ct), key, out))
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun tamperedChunkIsRejected() {
        val (ct, _) = streamEncrypt(Random(3).nextBytes(10_000))
        ct[ct.size - 20] = (ct[ct.size - 20].toInt() xor 1).toByte()
        assertThrows<Exception> { Zylc.decryptStream(ByteArrayInputStream(ct), key, ByteArrayOutputStream()) }
    }

    @Test
    fun truncatedFileIsRejected() {
        val (ct, _) = streamEncrypt(Random(4).nextBytes(10_000))
        assertThrows<ZyllCryptoException> { Zylc.decryptStream(ByteArrayInputStream(ct.copyOf(ct.size - 100)), key, ByteArrayOutputStream()) }
    }

    @Test
    fun shortSourceFails() {
        val s = ZylcEncryptingInputStream.with(ByteArrayInputStream(ByteArray(10)), 5000, BlobKind.ORIGINAL, key, blobId, dek, nonce, 12)
        assertThrows<java.io.EOFException> { s.readBytes() }
    }
}
