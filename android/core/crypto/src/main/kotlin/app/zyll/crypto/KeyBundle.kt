package app.zyll.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Base64

@Serializable
data class KdfParams(
    val alg: String,
    val v: Int? = null,
    val m: Int? = null,
    val t: Int? = null,
    val p: Int? = null,
    val salt: String,
)

@Serializable
data class KeySlot(
    val type: String,
    val kdf: KdfParams,
    val nonce: String,
    val wrappedMk: String,
)

@Serializable
data class KeyBundleFile(
    val format: String = KeyBundle.FORMAT,
    val version: Int = 1,
    val vaultId: String,
    val slots: List<KeySlot>,
)

/** Argon2id 参数。默认值见 spec/keybundle.md。 */
data class Argon2Params(val memoryKiB: Int = 65536, val iterations: Int = 3, val parallelism: Int = 1) {
    init {
        require(memoryKiB >= 8192) { "m must be >= 8192 KiB" }
        require(iterations >= 1) { "t must be >= 1" }
        require(parallelism == 1) { "p must be 1 (browser compatibility)" }
    }
}

/** 主密钥 MK 解锁后的结果。 */
class UnlockedVault(val vaultId: String, val masterKey: ByteArray) {
    /** 用来包裹每个文件 DEK 的子密钥。 */
    val wrapKey: ByteArray by lazy { Keys.deriveWrapKey(masterKey) }
}

object Keys {
    fun deriveWrapKey(mk: ByteArray): ByteArray =
        Primitives.hkdfSha256(mk, ByteArray(0), "zyll/v1/wrap".toByteArray(), 32)
}

object KeyBundle {
    const val FORMAT = "zyll-keybundle"
    const val SLOT_PASSWORD = "password"
    const val SLOT_RECOVERY = "recovery"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    /** 首次使用：生成 MK 与恢复密钥，返回密钥包 JSON、MK、恢复密钥（展示给用户抄写）。 */
    class Created(val json: String, val vault: UnlockedVault, val recoveryKey: ByteArray) {
        val recoveryKeyDisplay: String get() = RecoveryKey.format(recoveryKey)
    }

    fun create(password: String, params: Argon2Params = Argon2Params(), random: RandomSource = SecureRandomSource): Created {
        val vaultId = java.util.UUID.randomUUID().toString()
        val mk = random.nextBytes(32)
        val recovery = random.nextBytes(32)
        val file = createWith(
            vaultId, mk, password, recovery, params,
            pwSalt = random.nextBytes(16), pwNonce = random.nextBytes(12),
            rcSalt = random.nextBytes(16), rcNonce = random.nextBytes(12),
        )
        return Created(encode(file), UnlockedVault(vaultId, mk), recovery)
    }

    /** 确定性版本，供测试向量使用。 */
    fun createWith(
        vaultId: String, mk: ByteArray, password: String, recoveryKey: ByteArray, params: Argon2Params,
        pwSalt: ByteArray, pwNonce: ByteArray, rcSalt: ByteArray, rcNonce: ByteArray,
    ): KeyBundleFile = KeyBundleFile(
        vaultId = vaultId,
        slots = listOf(
            passwordSlot(vaultId, mk, password, params, pwSalt, pwNonce),
            recoverySlot(vaultId, mk, recoveryKey, rcSalt, rcNonce),
        ),
    )

    fun passwordSlot(vaultId: String, mk: ByteArray, password: String, params: Argon2Params, salt: ByteArray, nonce: ByteArray): KeySlot {
        val kek = Primitives.argon2id(passwordBytes(password), salt, params.memoryKiB, params.iterations, params.parallelism)
        return KeySlot(
            type = SLOT_PASSWORD,
            kdf = KdfParams("argon2id", 19, params.memoryKiB, params.iterations, params.parallelism, b64.encodeToString(salt)),
            nonce = b64.encodeToString(nonce),
            wrappedMk = b64.encodeToString(Primitives.aesGcmEncrypt(kek, nonce, mk, aad(vaultId, SLOT_PASSWORD))),
        )
    }

    fun recoverySlot(vaultId: String, mk: ByteArray, recoveryKey: ByteArray, salt: ByteArray, nonce: ByteArray): KeySlot {
        val kek = recoveryKek(recoveryKey, salt)
        return KeySlot(
            type = SLOT_RECOVERY,
            kdf = KdfParams(alg = "hkdf-sha256", salt = b64.encodeToString(salt)),
            nonce = b64.encodeToString(nonce),
            wrappedMk = b64.encodeToString(Primitives.aesGcmEncrypt(kek, nonce, mk, aad(vaultId, SLOT_RECOVERY))),
        )
    }

    fun parse(text: String): KeyBundleFile {
        val f = try { json.decodeFromString(KeyBundleFile.serializer(), text) } catch (e: Exception) {
            throw ZyllCryptoException("malformed keybundle", e)
        }
        if (f.format != FORMAT || f.version != 1) throw ZyllCryptoException("unsupported keybundle")
        return f
    }

    fun encode(f: KeyBundleFile): String = json.encodeToString(KeyBundleFile.serializer(), f)

    fun unlockWithPassword(text: String, password: String): UnlockedVault {
        val f = parse(text)
        val slot = f.slots.firstOrNull { it.type == SLOT_PASSWORD } ?: throw ZyllCryptoException("no password slot")
        val k = slot.kdf
        if (k.alg != "argon2id" || k.v != 19) throw ZyllCryptoException("unsupported kdf")
        val params = try { Argon2Params(k.m ?: 0, k.t ?: 0, k.p ?: 0) } catch (e: IllegalArgumentException) {
            throw ZyllCryptoException("bad kdf params", e)
        }
        val kek = Primitives.argon2id(passwordBytes(password), b64d.decode(k.salt), params.memoryKiB, params.iterations, params.parallelism)
        val mk = Primitives.aesGcmDecrypt(kek, b64d.decode(slot.nonce), b64d.decode(slot.wrappedMk), aad(f.vaultId, SLOT_PASSWORD))
        return UnlockedVault(f.vaultId, mk)
    }

    fun unlockWithRecoveryKey(text: String, recoveryKeyDisplay: String): UnlockedVault {
        val f = parse(text)
        val slot = f.slots.firstOrNull { it.type == SLOT_RECOVERY } ?: throw ZyllCryptoException("no recovery slot")
        if (slot.kdf.alg != "hkdf-sha256") throw ZyllCryptoException("unsupported kdf")
        val kek = recoveryKek(RecoveryKey.parse(recoveryKeyDisplay), b64d.decode(slot.kdf.salt))
        val mk = Primitives.aesGcmDecrypt(kek, b64d.decode(slot.nonce), b64d.decode(slot.wrappedMk), aad(f.vaultId, SLOT_RECOVERY))
        return UnlockedVault(f.vaultId, mk)
    }

    /** 改密码：只替换 password 槽位，MK 不变。 */
    fun changePassword(text: String, vault: UnlockedVault, newPassword: String, params: Argon2Params = Argon2Params(), random: RandomSource = SecureRandomSource): String {
        val f = parse(text)
        val newSlot = passwordSlot(f.vaultId, vault.masterKey, newPassword, params, random.nextBytes(16), random.nextBytes(12))
        return encode(f.copy(slots = f.slots.map { if (it.type == SLOT_PASSWORD) newSlot else it }))
    }

    fun passwordBytes(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    private fun recoveryKek(recoveryKey: ByteArray, salt: ByteArray): ByteArray {
        if (recoveryKey.size != 32) throw ZyllCryptoException("bad recovery key")
        return Primitives.hkdfSha256(recoveryKey, salt, "zyll/v1/recovery-kek".toByteArray(), 32)
    }

    private fun aad(vaultId: String, type: String) = "zyll-keybundle/v1|$vaultId|$type".toByteArray(Charsets.UTF_8)
}

/** 恢复密钥：RFC 4648 Base32，大写无填充，每 4 字符一组。 */
object RecoveryKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun format(key: ByteArray): String = base32(key).chunked(4).joinToString("-")

    fun parse(text: String): ByteArray {
        val clean = text.uppercase().filter { it != '-' && !it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) throw ZyllCryptoException("bad recovery key character")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }
}
