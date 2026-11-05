package app.zyll.crypto

import app.zyll.crypto.vectors.pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** 校验 spec/test-vectors：TS 端跑的是同一份文件。 */
class VectorsTest {
    private val dir = File(System.getProperty("zyll.vectorsDir") ?: "../../../spec/test-vectors")
    private fun load(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int

    @Test
    fun hkdf() {
        for (v in load("primitives.json")["hkdf"]!!.jsonArray.map { it.jsonObject }) {
            val okm = Primitives.hkdfSha256(v.s("ikm").hexToBytes(), v.s("salt").hexToBytes(), v.s("info").hexToBytes(), v.i("length"))
            assertEquals(v.s("okm"), okm.toHex(), v.s("name"))
        }
    }

    @Test
    fun rfc5869KnownAnswer() {
        val v = load("primitives.json")["hkdf"]!!.jsonArray[0].jsonObject
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", v.s("okm"))
    }

    @Test
    fun argon2id() {
        for (v in load("primitives.json")["argon2id"]!!.jsonArray.map { it.jsonObject }) {
            val out = Primitives.argon2id(v.s("password").hexToBytes(), v.s("salt").hexToBytes(), v.i("m"), v.i("t"), v.i("p"))
            assertEquals(v.s("output"), out.toHex(), v.s("name"))
        }
    }

    @Test
    fun zylcRoundTripAndExactBytes() {
        val root = load("zylc.json")
        val kWrap = root.s("kWrap").hexToBytes()
        for (c in root["cases"]!!.jsonArray.map { it.jsonObject }) {
            val plain = pattern(c.i("plainLen"))
            val ct = Zylc.encryptWith(plain, BlobKind.of(c.i("kind")), kWrap,
                c.s("blobId").hexToBytes(), c.s("dek").hexToBytes(), c.s("wrapNonce").hexToBytes(), c.i("chunkLog2"))
            assertEquals(c.s("ciphertextSha256"), Primitives.sha256(ct).toHex(), c.s("name"))
            c["ciphertext"]?.let { assertEquals(it.jsonPrimitive.content, ct.toHex()) }
            assertArrayEquals(plain, Zylc.decrypt(ct, kWrap), c.s("name"))
        }
    }

    @Test
    fun zylcNegativeCasesFail() {
        val root = load("zylc.json")
        for (n in root["negative"]!!.jsonArray.map { it.jsonObject }) {
            val key = (n["kWrap"] ?: root["kWrap"])!!.jsonPrimitive.content.hexToBytes()
            assertThrows<ZyllCryptoException>(n.s("name")) { Zylc.decrypt(n.s("ciphertext").hexToBytes(), key) }
        }
    }

    @Test
    fun zylcRandomAccess() {
        val kWrap = load("zylc.json").s("kWrap").hexToBytes()
        val plain = pattern(10000)
        val ct = Zylc.encrypt(plain, BlobKind.ORIGINAL, kWrap, chunkLog2 = 12)
        val r = Zylc.Reader.open(ct.copyOfRange(0, Zylc.HEADER_LEN), kWrap)
        assertEquals(3, r.header.chunkCount)
        val off = r.header.chunkOffset(1).toInt()
        assertArrayEquals(plain.copyOfRange(4096, 8192), r.decryptChunk(1, ct.copyOfRange(off, off + r.header.chunkCipherLen(1))))
    }

    @Test
    fun keybundleUnlockAndRecreate() {
        for (b in load("keybundle.json")["bundles"]!!.jsonArray.map { it.jsonObject }) {
            val text = b["bundle"].toString()
            val byPw = KeyBundle.unlockWithPassword(text, b.s("password"))
            assertEquals(b.s("masterKey"), byPw.masterKey.toHex())
            assertEquals(b.s("wrapKey"), byPw.wrapKey.toHex())
            assertEquals(b.s("masterKey"), KeyBundle.unlockWithRecoveryKey(text, b.s("recoveryKey").lowercase()).masterKey.toHex())
            assertThrows<ZyllCryptoException> { KeyBundle.unlockWithPassword(text, b.s("password") + "x") }

            val i = b["createInputs"]!!.jsonObject
            val again = KeyBundle.createWith(
                i.s("vaultId"), b.s("masterKey").hexToBytes(), b.s("password"), RecoveryKey.parse(b.s("recoveryKey")),
                Argon2Params(i.i("m"), i.i("t"), i.i("p")),
                i.s("pwSalt").hexToBytes(), i.s("pwNonce").hexToBytes(), i.s("rcSalt").hexToBytes(), i.s("rcNonce").hexToBytes(),
            )
            assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(KeyBundle.encode(again)))
        }
    }

    @Test
    fun changePasswordKeepsMasterKey() {
        val created = KeyBundle.create("old pass", Argon2Params(8192, 1, 1))
        val changed = KeyBundle.changePassword(created.json, created.vault, "new pass", Argon2Params(8192, 1, 1))
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithPassword(changed, "new pass").masterKey)
        assertArrayEquals(created.vault.masterKey, KeyBundle.unlockWithRecoveryKey(changed, created.recoveryKeyDisplay).masterKey)
        assertThrows<ZyllCryptoException> { KeyBundle.unlockWithPassword(changed, "old pass") }
    }
}
