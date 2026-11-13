package app.zyll.crypto

import java.text.Normalizer
import java.util.Base64

/**
 * 外链分享（spec/share-link.md）：链接格式和钥匙。
 * 钥匙 = 链接里的随机片段 S + 密码（Argon2id）一起算出来，只拿到其中一样打不开。
 */
object ShareLink {
    const val VERSION = 1
    const val SECRET_LEN = 32
    const val MIN_PASSWORD = 8

    /** 密码的 Argon2id 参数（固定，不写进链接或清单）。 */
    val ARGON2 = Argon2Params(memoryKiB = 65536, iterations = 3, parallelism = 1)

    enum class Provider(val code: String) { Google("g"), OneDrive("o") }

    /** 链接 `#` 后面的部分。[loc]：Google 是清单文件 id，OneDrive 是清单匿名链接 webUrl 的 base64url。 */
    data class Fragment(val provider: Provider, val loc: String, val secret: ByteArray) {
        init {
            require(secret.size == SECRET_LEN) { "secret must be $SECRET_LEN bytes" }
            require(loc.isNotEmpty() && '.' !in loc && loc.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) { "bad loc" }
        }

        fun encode(): String = "$VERSION.${provider.code}.$loc.${b64url(secret)}"

        override fun equals(other: Any?) = other is Fragment && other.provider == provider && other.loc == loc && other.secret.contentEquals(secret)
        override fun hashCode() = 31 * (31 * provider.hashCode() + loc.hashCode()) + secret.contentHashCode()

        companion object {
            /** 解析 `#` 后面的部分（可以带不带 `#`）。格式不对抛 [ZyllCryptoException]。 */
            fun parse(text: String): Fragment {
                val parts = text.removePrefix("#").split('.')
                if (parts.size != 4 || parts[0] != VERSION.toString()) throw ZyllCryptoException("bad link")
                val provider = Provider.entries.firstOrNull { it.code == parts[1] } ?: throw ZyllCryptoException("bad link")
                val secret = try {
                    Base64.getUrlDecoder().decode(parts[3])
                } catch (e: IllegalArgumentException) {
                    throw ZyllCryptoException("bad link", e)
                }
                return try {
                    Fragment(provider, parts[2], secret)
                } catch (e: IllegalArgumentException) {
                    throw ZyllCryptoException("bad link", e)
                }
            }
        }
    }

    /** OneDrive 的清单位置：把匿名链接的 webUrl 编成 base64url。 */
    fun encodeUrlLoc(webUrl: String): String = b64url(webUrl.toByteArray(Charsets.UTF_8))

    fun decodeUrlLoc(loc: String): String = String(Base64.getUrlDecoder().decode(loc), Charsets.UTF_8)

    fun salt(secret: ByteArray): ByteArray =
        Primitives.sha256("zyll-link/v1/salt".toByteArray(), secret).copyOfRange(0, 16)

    /** 密码按 Unicode 码点数（NFC 之后）够不够长。 */
    fun passwordLongEnough(password: String): Boolean {
        val n = Normalizer.normalize(password, Normalizer.Form.NFC)
        return n.codePointCount(0, n.length) >= MIN_PASSWORD
    }

    /** 清单的包裹密钥 K_link。Argon2id 要 64 MiB 内存、几百毫秒：不要在主线程调用。 */
    fun linkKey(secret: ByteArray, password: String): ByteArray {
        require(secret.size == SECRET_LEN)
        val pw = Primitives.argon2id(
            Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8),
            salt(secret), ARGON2.memoryKiB, ARGON2.iterations, ARGON2.parallelism,
        )
        return Primitives.hkdfSha256(secret + pw, ByteArray(0), "zyll/v1/link-wrap".toByteArray(), 32)
    }

    /** 加密清单（ZYLC kind = 其他）。 */
    fun sealManifest(json: ByteArray, linkKey: ByteArray, random: RandomSource = SecureRandomSource): ByteArray =
        Zylc.encrypt(json, BlobKind.OTHER, linkKey, random)

    /** 解开清单；密码错时抛 [ZyllCryptoException]。 */
    fun openManifest(data: ByteArray, linkKey: ByteArray): ByteArray = Zylc.decrypt(data, linkKey, BlobKind.OTHER)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
