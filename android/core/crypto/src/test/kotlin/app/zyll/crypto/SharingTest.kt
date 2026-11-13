package app.zyll.crypto

import app.zyll.crypto.vectors.pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.Base64

/** 校验 spec/test-vectors/sharing.json，网页端跑同一份。 */
class SharingTest {
    private val dir = File(System.getProperty("zyll.vectorsDir") ?: "../../../spec/test-vectors")
    private val v = Json.parseToJsonElement(File(dir, "sharing.json").readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun id(name: String) = v.o("identities").o(name).let { ShareIdentity(it.s("xPriv").hexToBytes(), it.s("edPriv").hexToBytes()) }

    @Test
    fun identities() {
        for (name in listOf("owner", "member")) {
            val e = v.o("identities").o(name)
            val id = id(name)
            assertEquals(e.s("xPub"), id.xPub.toHex())
            assertEquals(e.s("edPub"), id.edPub.toHex())
            assertEquals(e.s("fingerprint"), id.fpHex)
            assertEquals(e.s("safetyCode"), Sharing.safetyCode(id.fingerprint))
        }
    }

    @Test
    fun sealAndOpen() {
        val seal = v.o("seal")
        val member = id("member")
        val albumId = v.s("albumId")
        val epoch = v["epoch"]!!.jsonPrimitive.int
        val ak = v.s("albumKey").hexToBytes()
        val again = Sharing.sealAlbumKeyWith(ak, albumId, epoch, member.xPub, member.fpHex, seal.s("ephPriv").hexToBytes(), b64(seal.s("nonce")))
        assertEquals(seal.s("ct"), Base64.getEncoder().encodeToString(again.ct))
        assertEquals(seal.s("epk"), Base64.getEncoder().encodeToString(again.epk))

        val sealed = SealedKey(b64(seal.s("epk")), b64(seal.s("nonce")), b64(seal.s("ct")))
        assertArrayEquals(ak, Sharing.openAlbumKey(sealed, albumId, epoch, member))
        assertEquals(v.s("albumWrapKey"), Sharing.albumWrapKey(ak, albumId).toHex())
        // 别人、别的相册、别的 epoch 都打不开
        assertThrows<ZyllCryptoException> { Sharing.openAlbumKey(sealed, albumId, epoch, id("owner")) }
        assertThrows<ZyllCryptoException> { Sharing.openAlbumKey(sealed, albumId + "x", epoch, member) }
        assertThrows<ZyllCryptoException> { Sharing.openAlbumKey(sealed, albumId, epoch + 1, member) }
        assertThrows<ZyllCryptoException> { Sharing.openAlbumKey(SealedKey(ByteArray(32), sealed.nonce, sealed.ct), albumId, epoch, member) }
    }

    @Test
    fun manifestSignature() {
        val m = v.o("manifest")
        val body = b64(m.s("body"))
        val sig = b64(m.s("sig"))
        assertEquals(m.s("sig"), Base64.getEncoder().encodeToString(Sharing.signManifest(body, id("owner").edPriv)))
        assertTrue(Sharing.verifyManifest(body, sig, id("owner").edPub))
        assertFalse(Sharing.verifyManifest(body, sig, id("member").edPub))
        assertFalse(Sharing.verifyManifest(body + byteArrayOf(32), sig, id("owner").edPub))
    }

    @Test
    fun memberDecryptsWithDek() {
        val p = v.o("photo")
        val ct = p.s("ciphertext").hexToBytes()
        val dek = p.s("dek").hexToBytes()
        val plain = pattern(p["plainLen"]!!.jsonPrimitive.int)
        assertArrayEquals(plain, Zylc.decryptWithDek(ct, dek, BlobKind.ORIGINAL))
        assertArrayEquals(dek, Zylc.unwrapDek(ct.copyOfRange(0, Zylc.HEADER_LEN), p.s("kWrap").hexToBytes()))
        assertThrows<ZyllCryptoException> { Zylc.decryptWithDek(ct, dek, BlobKind.THUMBNAIL) }
        assertThrows<ZyllCryptoException> { Zylc.decryptWithDek(ct, ByteArray(32), BlobKind.ORIGINAL) }
        // 改了头部（哪怕只是包裹的 DEK）块校验也会失败
        assertThrows<ZyllCryptoException> { Zylc.decryptWithDek(ct.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }, dek, BlobKind.ORIGINAL) }
    }
}
