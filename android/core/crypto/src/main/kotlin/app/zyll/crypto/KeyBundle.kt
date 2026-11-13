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
    /** 修改次数的先后（毫秒时间，单调递增）。旧版本没有这个字段，按 0 算。见 spec/keybundle.md §5。 */
    val rev: Long? = null,
    /** 用 K_wrap 派生的密钥对 vaultId、rev 和全部槽位算的 HMAC，证明这份是手里有这套密钥的设备写的。 */
    val mac: String? = null,
)

/** Argon2id 参数。默认值见 spec/keybundle.md。 */
data class Argon2Params(val memoryKiB: Int = 65536, val iterations: Int = 3, val parallelism: Int = 1) {
    init {
        require(memoryKiB >= 8192) { "m must be >= 8192 KiB" }
        // 上限：网盘上的密钥包可能被改过，参数太大会让解锁时内存耗尽闪退、或者卡很久
        require(memoryKiB <= MAX_MEMORY_KIB) { "m must be <= $MAX_MEMORY_KIB KiB" }
        require(iterations >= 1) { "t must be >= 1" }
        require(iterations <= MAX_ITERATIONS) { "t must be <= $MAX_ITERATIONS" }
        require(parallelism == 1) { "p must be 1 (browser compatibility)" }
    }

    companion object {
        const val MAX_MEMORY_KIB = 1 shl 20 // 1 GiB
        const val MAX_ITERATIONS = 10
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
        val vault = UnlockedVault(vaultId, mk)
        // 新建时就带上 rev 和 mac：以后别的设备据此判断哪份更新、是不是同一套密钥写的
        return Created(encode(sign(file.copy(rev = System.currentTimeMillis()), vault.wrapKey)), vault, recovery)
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
    fun changePassword(text: String, vault: UnlockedVault, newPassword: String, params: Argon2Params = Argon2Params(), random: RandomSource = SecureRandomSource): String =
        rewrap(text, vault, newPassword = newPassword, newRecoveryKey = null, minRev = 0, params = params, random = random)

    /**
     * 重新包裹主密钥：[newPassword] 不为空时换掉 password 槽位，[newRecoveryKey] 不为空时换掉 recovery 槽位，
     * 其余槽位原样保留。MK 不变，所有已上传的照片都不用重新加密。
     * 新的 rev 取“现在”和 [minRev] + 1 里较大的那个，再附上 mac（见 spec/keybundle.md §5）。
     */
    fun rewrap(
        text: String, vault: UnlockedVault, newPassword: String?, newRecoveryKey: ByteArray?, minRev: Long,
        params: Argon2Params = Argon2Params(), random: RandomSource = SecureRandomSource, now: Long = System.currentTimeMillis(),
    ): String {
        val f = parse(text)
        require(f.vaultId == vault.vaultId) { "vault mismatch" }
        var slots = f.slots
        if (newPassword != null) {
            val s = passwordSlot(f.vaultId, vault.masterKey, newPassword, params, random.nextBytes(16), random.nextBytes(12))
            slots = if (slots.any { it.type == SLOT_PASSWORD }) slots.map { if (it.type == SLOT_PASSWORD) s else it } else slots + s
        }
        if (newRecoveryKey != null) {
            val s = recoverySlot(f.vaultId, vault.masterKey, newRecoveryKey, random.nextBytes(16), random.nextBytes(12))
            slots = if (slots.any { it.type == SLOT_RECOVERY }) slots.map { if (it.type == SLOT_RECOVERY) s else it } else slots + s
        }
        val rev = maxOf(now, maxOf(minRev, f.rev ?: 0) + 1)
        return encode(sign(f.copy(slots = slots, rev = rev), vault.wrapKey))
    }

    /** 这份密钥包的修改先后；旧版本没有 rev 时为 0。 */
    fun revOf(f: KeyBundleFile): Long = f.rev ?: 0

    /** 附上 mac。 */
    fun sign(f: KeyBundleFile, wrapKey: ByteArray): KeyBundleFile = f.copy(mac = b64.encodeToString(macOf(f, wrapKey)))

    /** mac 对得上 [wrapKey]（也就是这份是手里有同一套密钥的设备写的）。没有 mac 的旧密钥包返回 false。 */
    fun verify(f: KeyBundleFile, wrapKey: ByteArray): Boolean {
        val mac = f.mac ?: return false
        val got = try { b64d.decode(mac) } catch (_: IllegalArgumentException) { return false }
        return java.security.MessageDigest.isEqual(got, macOf(f, wrapKey))
    }

    private fun macOf(f: KeyBundleFile, wrapKey: ByteArray): ByteArray {
        val key = Primitives.hkdfSha256(wrapKey, ByteArray(0), "zyll/v1/keybundle-mac".toByteArray(), 32)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(macInput(f))
    }

    /** 参与 mac 的内容，逐项用换行隔开；没有的数字字段写空串。和网页端 keybundle.ts 的 macInput 一致。 */
    fun macInput(f: KeyBundleFile): ByteArray {
        val sb = StringBuilder("zyll-keybundle-mac/v1\n").append(f.vaultId).append('\n').append(f.rev ?: 0).append('\n')
        for (s in f.slots) {
            val k = s.kdf
            listOf(s.type, k.alg, k.v?.toString() ?: "", k.m?.toString() ?: "", k.t?.toString() ?: "", k.p?.toString() ?: "", k.salt, s.nonce, s.wrappedMk)
                .forEach { sb.append(it).append('\n') }
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** 新生成一个恢复密钥（32 字节随机数）。 */
    fun newRecoveryKey(random: RandomSource = SecureRandomSource): ByteArray = random.nextBytes(32)

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

    internal fun base32(data: ByteArray): String {
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
