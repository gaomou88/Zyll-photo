package app.zyll.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** spec/share-link.md 的向量和边界情况。 */
class ShareLinkTest {
    private val dir = File(System.getProperty("zyll.vectorsDir") ?: "../../../spec/test-vectors")
    private val v: JsonObject = Json.parseToJsonElement(File(dir, "share-link.json").readText()).jsonObject
    private fun s(k: String) = v[k]!!.jsonPrimitive.content

    @Test
    fun vectors() {
        val secret = s("secret").hexToBytes()
        assertEquals(s("salt"), ShareLink.salt(secret).toHex())
        val k = ShareLink.linkKey(secret, s("password"))
        assertEquals(s("linkKey"), k.toHex())
        // 同一个密码的 NFC 写法得到同一把钥匙
        assertArrayEquals(k, ShareLink.linkKey(secret, java.text.Normalizer.normalize(s("password"), java.text.Normalizer.Form.NFC)))
        assertEquals(s("fragmentGoogle"), ShareLink.Fragment(ShareLink.Provider.Google, "1AbC-dEf_GhIjK", secret).encode())
        val od = ShareLink.Fragment.parse("#" + s("fragmentOneDrive"))
        assertEquals(ShareLink.Provider.OneDrive, od.provider)
        assertEquals(s("oneDriveUrl"), ShareLink.decodeUrlLoc(od.loc))
        val plain = java.util.Base64.getDecoder().decode(s("manifestPlain"))
        assertArrayEquals(plain, ShareLink.openManifest(s("manifestSealed").hexToBytes(), k))
    }

    @Test
    fun wrongPasswordOrSecretFails() {
        val sealed = s("manifestSealed").hexToBytes()
        val secret = s("secret").hexToBytes()
        assertThrows<ZyllCryptoException> { ShareLink.openManifest(sealed, ShareLink.linkKey(secret, s("password") + "!")) }
        val other = secret.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows<ZyllCryptoException> { ShareLink.openManifest(sealed, ShareLink.linkKey(other, s("password"))) }
    }

    @Test
    fun parseRejectsBadLinks() {
        val good = s("fragmentGoogle")
        for (bad in listOf("", "2" + good.drop(1), good.replace(".g.", ".x."), good + ".extra", good.dropLast(2), good.replace("1AbC", "1A/C"))) {
            assertThrows<ZyllCryptoException>(bad) { ShareLink.Fragment.parse(bad) }
        }
        assertEquals(good, ShareLink.Fragment.parse(good).encode())
    }

    @Test
    fun passwordLength() {
        assertFalse(ShareLink.passwordLongEnough("1234567"))
        assertTrue(ShareLink.passwordLongEnough("12345678"))
        // 按码点数：7 个汉字不够，8 个够
        assertFalse(ShareLink.passwordLongEnough("海边海边海边海"))
        assertTrue(ShareLink.passwordLongEnough("海边海边海边海边"))
    }
}
