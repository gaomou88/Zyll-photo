package app.zyll.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 所有解密/校验失败都抛这个异常，调用方不应区分具体原因（避免成为 oracle）。 */
class ZyllCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 随机数来源。生产环境用 [SecureRandomSource]，测试向量注入固定值。 */
fun interface RandomSource {
    fun nextBytes(size: Int): ByteArray
}

object SecureRandomSource : RandomSource {
    private val rng = SecureRandom()
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(rng::nextBytes)
}

/** 规范中用到的底层原语，全部基于 JCA，Argon2id 用 Bouncy Castle（纯 Java）。 */
object Primitives {
    const val KEY_LEN = 32
    const val NONCE_LEN = 12
    const val TAG_LEN = 16

    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_LEN) { "GCM nonce must be 12 bytes" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherAndTag: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_LEN) { "AES-256 key must be 32 bytes" }
        require(nonce.size == NONCE_LEN) { "GCM nonce must be 12 bytes" }
        if (cipherAndTag.size < TAG_LEN) throw ZyllCryptoException("ciphertext too short")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            c.updateAAD(aad)
            c.doFinal(cipherAndTag)
        } catch (e: GeneralSecurityException) {
            throw ZyllCryptoException("authentication failed", e)
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    /** HKDF-SHA256（RFC 5869）。空 salt 等价于 32 个 0 字节。 */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(realSalt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** Argon2id v1.3（RFC 9106）。[memoryKiB] 单位 KiB。 */
    fun argon2id(password: ByteArray, salt: ByteArray, memoryKiB: Int, iterations: Int, parallelism: Int, length: Int = KEY_LEN): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(length)
        gen.generateBytes(password, out)
        return out
    }

    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        data.forEach(mac::update)
        return mac.doFinal()
    }
}

internal fun u64(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

internal fun readU64(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
    return v
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
