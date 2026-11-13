package app.zyll.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** 一个库的共享身份：X25519 收相册钥匙，Ed25519 签相册清单。见 spec/sharing.md 第 2 节。 */
class ShareIdentity(val xPriv: ByteArray, val edPriv: ByteArray) {
    init {
        require(xPriv.size == 32 && edPriv.size == 32) { "identity keys must be 32 bytes" }
    }

    val xPub: ByteArray by lazy { Sharing.x25519Public(xPriv) }
    val edPub: ByteArray by lazy { Sharing.ed25519Public(edPriv) }
    val fingerprint: ByteArray by lazy { Sharing.fingerprint(xPub, edPub) }
    val fpHex: String get() = fingerprint.toHex()

    companion object {
        fun generate(random: RandomSource = SecureRandomSource) =
            ShareIdentity(random.nextBytes(32), random.nextBytes(32))
    }
}

/** 封装给某个成员的相册钥匙。 */
class SealedKey(val epk: ByteArray, val nonce: ByteArray, val ct: ByteArray)

object Sharing {
    fun x25519Public(priv: ByteArray): ByteArray = X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun ed25519Public(priv: ByteArray): ByteArray = Ed25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun fingerprint(xPub: ByteArray, edPub: ByteArray): ByteArray =
        Primitives.sha256("zyll-share-id/v1".toByteArray(), xPub, edPub)

    /** 给人核对的安全码：指纹前 10 字节的 Base32，每 4 个字符一组。 */
    fun safetyCode(fingerprint: ByteArray): String =
        RecoveryKey.base32(fingerprint.copyOfRange(0, 10)).chunked(4).joinToString(" ")

    fun albumWrapKey(albumKey: ByteArray, albumId: String): ByteArray {
        require(albumKey.size == 32) { "album key must be 32 bytes" }
        return Primitives.hkdfSha256(albumKey, ByteArray(0), "zyll/v1/album-wrap|$albumId".toByteArray(), 32)
    }

    private fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(32)
        try {
            X25519Agreement().apply { init(X25519PrivateKeyParameters(priv, 0)) }
                .calculateAgreement(X25519PublicKeyParameters(pub, 0), out, 0)
        } catch (e: IllegalStateException) {
            throw ZyllCryptoException("bad public key", e)
        }
        if (out.all { it.toInt() == 0 }) throw ZyllCryptoException("bad public key")
        return out
    }

    private fun sealAad(albumId: String, epoch: Int, recipientFpHex: String) =
        "zyll-share/v1|$albumId|$epoch|$recipientFpHex".toByteArray()

    private fun sealKey(ss: ByteArray, ephPub: ByteArray, recipientXPub: ByteArray) =
        Primitives.hkdfSha256(ss, ephPub + recipientXPub, "zyll/v1/share-seal".toByteArray(), 32)

    fun sealAlbumKey(
        albumKey: ByteArray, albumId: String, epoch: Int,
        recipientXPub: ByteArray, recipientFpHex: String,
        random: RandomSource = SecureRandomSource,
    ): SealedKey = sealAlbumKeyWith(albumKey, albumId, epoch, recipientXPub, recipientFpHex, random.nextBytes(32), random.nextBytes(12))

    /** 确定性版本，供测试向量使用。 */
    fun sealAlbumKeyWith(
        albumKey: ByteArray, albumId: String, epoch: Int,
        recipientXPub: ByteArray, recipientFpHex: String,
        ephPriv: ByteArray, nonce: ByteArray,
    ): SealedKey {
        require(albumKey.size == 32 && recipientXPub.size == 32)
        val ephPub = x25519Public(ephPriv)
        val k = sealKey(x25519(ephPriv, recipientXPub), ephPub, recipientXPub)
        val ct = Primitives.aesGcmEncrypt(k, nonce, albumKey, sealAad(albumId, epoch, recipientFpHex))
        return SealedKey(ephPub, nonce, ct)
    }

    fun openAlbumKey(sealed: SealedKey, albumId: String, epoch: Int, me: ShareIdentity): ByteArray {
        if (sealed.epk.size != 32 || sealed.nonce.size != 12) throw ZyllCryptoException("bad sealed key")
        val k = sealKey(x25519(me.xPriv, sealed.epk), sealed.epk, me.xPub)
        return Primitives.aesGcmDecrypt(k, sealed.nonce, sealed.ct, sealAad(albumId, epoch, me.fpHex))
    }

    private fun manifestMessage(body: ByteArray) = "zyll-shared-album/v1|".toByteArray() + body

    fun signManifest(body: ByteArray, edPriv: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(edPriv, 0))
        val m = manifestMessage(body)
        s.update(m, 0, m.size)
        return s.generateSignature()
    }

    fun verifyManifest(body: ByteArray, sig: ByteArray, edPub: ByteArray): Boolean {
        if (sig.size != 64 || edPub.size != 32) return false
        return try {
            val v = Ed25519Signer()
            v.init(false, Ed25519PublicKeyParameters(edPub, 0))
            val m = manifestMessage(body)
            v.update(m, 0, m.size)
            v.verifySignature(sig)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
